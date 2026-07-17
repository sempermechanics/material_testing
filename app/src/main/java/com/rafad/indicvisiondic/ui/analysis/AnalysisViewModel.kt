package com.rafad.indicvisiondic.ui.analysis
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.IndicVisionNativeLib
import com.rafad.indicvisiondic.ProgressCallback
import com.rafad.indicvisiondic.data.CloudSync
import com.rafad.indicvisiondic.data.DicSettings
import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.data.SessionStore
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.report.EngineStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Holds analysis inputs/state across configuration changes and runs the
 * batch solve: streams each deformed frame through the native engine
 * ([IndicVisionNativeLib]) on a dedicated thread, writes per-frame `.dat`
 * results, and enqueues cloud sync via DicUploadWorker.
 */
class AnalysisViewModel : ViewModel() {

    companion object {
        /** Outcome code for a user-cancelled run (not an engine failure). */
        const val ERROR_CANCELLED = -99

        /** Outcome code when a new session would exceed the account quota. */
        const val ERROR_SESSION_LIMIT = -98

        /** Session-dir subfolder holding the persisted raw deformed originals. */
        const val RAW_DEFORMED_SUBDIR = "raw_deformed"
    }

    // NATIVE THREAD PINNING: A single persistent OS thread for ALL JNI/OpenMP calls.
    val nativeExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "IndicVision-NativeThread").also { it.isDaemon = true }
    }

    override fun onCleared() {
        super.onCleared()
        nativeExecutor.shutdown()
    }

    var refBytes: ByteArray? = null
    var roiMaskBytes: ByteArray? = null
    var defFilePaths: List<String> = emptyList()
    /** Original picked filenames, index-aligned with [defFilePaths]. */
    var defOriginalNames: List<String> = emptyList()

    /**
     * Whether [defFilePaths] came from sampling a video rather than picked
     * images. Video frames are extracted to image files, so the two sources are
     * indistinguishable by the time the UI shows them — this is what lets the
     * deformed-frames card show an icon that matches what the user chose.
     */
    var defFromVideo: Boolean = false

    var realRefWidth: Int = 0
    var realRefHeight: Int = 0
    var refName: String = "No image selected"

    val defCount: Int get() = defFilePaths.size
    val batchMode: Boolean get() = defFilePaths.size > 1

    var hasCustomRoi: Boolean = false
    var roiX: Int = 0
    var roiY: Int = 0
    var roiW: Int = 0
    var roiH: Int = 0

    var lastStep: Int = 5
    var lastBatchDirPath: String? = null
    var lastRefPath: String? = null
    var lastDefPath: String? = null
    var hasCompletedAnalysis: Boolean = false

    var currentSessionId: String? = null
    var engineStatsArray: FloatArray? = null

    var wizardStep: Int = 1
    var settingsReviewed: Boolean = false

    fun isReadyToCompute(): Boolean = refBytes != null && defFilePaths.isNotEmpty()

    fun getDefDisplayName(): String = when {
        defFilePaths.isEmpty() -> "No images selected"
        defFilePaths.size == 1 -> "Def: ${defFilePaths[0].substringAfterLast('/')}"
        else -> "${defFilePaths.size} images selected"
    }

    fun clearPreviousResults() {
        lastBatchDirPath = null
        lastRefPath = null
        lastDefPath = null
        hasCompletedAnalysis = false
        workingLocalId = null
    }

    /**
     * Identity of the working session on the Home list. Re-runs reuse it so
     * the row updates in place; with "Keep every re-run" enabled each run gets
     * a fresh id (its own row). New inputs reset it via [clearPreviousResults].
     */
    var workingLocalId: String? = null

    /** True when the next completed run would create a new Home-list row. */
    fun wouldCreateNewSession(appContext: Context): Boolean =
        workingLocalId == null || DicSettings.keepEveryRerun(appContext)

    private fun resolveLocalSessionId(appContext: Context): String {
        val current = workingLocalId
        return if (current == null || DicSettings.keepEveryRerun(appContext)) {
            UUID.randomUUID().toString().take(12).also { workingLocalId = it }
        } else {
            current
        }
    }

    data class BatchAnalysisParams(
        val cacheDir: File,
        val subset: Int,
        val step: Int,
        val strainWin: Int,
        val finalRectX: Int,
        val finalRectY: Int,
        val finalRectW: Int,
        val finalRectH: Int,
        val applyBlur: Boolean,
        val useNlvc: Boolean,
        val use6x6: Boolean,
        val maskData: ByteArray,
        val debugDir: File,
        val processingStartTime: Long,
    )

    /** Cooperative cancel: checked between frames (the native solve itself is not interruptible). */
    @Volatile
    var cancelRequested = false

    data class BatchProgressUpdate(
        val percent: Int,
        val status: String,
        val timerText: String,
        // Live overlay tiles; -1 = no update this tick
        val pointsSolved: Int = -1,
        val convergencePercent: Float = -1f,
    )

    data class BatchAnalysisOutcome(
        val engineErrorCode: Int,
        val firstFrameValidPoints: Int,
        val totalFrames: Int,
        val executionTimeMs: Int,
        val batchDirPath: String,
    )

    /**
     * Full-field batch compute + offline upload queue. All JNI calls run on [nativeExecutor].
     */
    suspend fun runBatchAnalysis(
        appContext: Context,
        params: BatchAnalysisParams,
        onProgress: (BatchProgressUpdate) -> Unit,
    ): BatchAnalysisOutcome = withContext(nativeExecutor.asCoroutineDispatcher()) {
        // Hard stop before any native work: new sessions cannot exceed the quota.
        // Re-runs of an existing workingLocalId are still allowed.
        if (wouldCreateNewSession(appContext)) {
            val localCount = SessionStore.list(appContext).size
            TokenStore.refreshSessionLimit(appContext, localCount)
            if (TokenStore.isSessionLimitReached(appContext)) {
                Timber.w("Hard stop: analysis blocked at session limit")
                return@withContext BatchAnalysisOutcome(
                    engineErrorCode = ERROR_SESSION_LIMIT,
                    firstFrameValidPoints = 0,
                    totalFrames = defFilePaths.size,
                    executionTimeMs = 0,
                    batchDirPath = "",
                )
            }
        }

        // Results live in app-private persistent storage (NOT cacheDir, which
        // the OS may evict): one directory per Home-list session.
        val localSessionId = resolveLocalSessionId(appContext)
        val batchDir = SessionStore.dirFor(appContext, localSessionId)
        batchDir.listFiles { f -> f.extension == "dat" }?.forEach { it.delete() }

        lastBatchDirPath = batchDir.absolutePath
        lastStep = params.step

        if (!params.debugDir.exists()) params.debugDir.mkdirs()
        IndicVisionNativeLib.setDebugOutputDir(params.debugDir.absolutePath)

        val totalFrames = defFilePaths.size
        val refBytes = refBytes ?: throw IllegalStateException("Reference missing")

        var firstFrameValidPoints = 0
        var firstFrameAvgIters = 0f
        var engineErrorCode = 0

        onProgress(BatchProgressUpdate(0, "Caching reference in engine…", "Caching Reference in Native Engine..."))
        IndicVisionNativeLib.initializeReference(
            refBytes,
            params.maskData,
            realRefWidth,
            realRefHeight,
            params.applyBlur,
        )

        val gridW = params.finalRectW / params.step
        val gridH = params.finalRectH / params.step
        val maxPoints = gridW * gridH
        val outputBuffer = java.nio.ByteBuffer.allocateDirect(maxPoints * DicResult.BYTES_PER_POINT)
        outputBuffer.order(java.nio.ByteOrder.nativeOrder())

        cancelRequested = false
        var totalPointsSolved = 0
        var lastConvergence = -1f

        // Persist the raw deformed originals alongside the reference so exports
        // (and reopened sessions) can bundle them. Cleared per re-run.
        val rawDeformedDir = File(batchDir, RAW_DEFORMED_SUBDIR).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        // The filenames actually written into raw_deformed/, index-aligned with
        // the frames. These (not the cache-copy paths) are what the session index
        // and the cloud upload look the images up by. Blank = persist failed.
        val persistedRawNames = MutableList(totalFrames) { "" }

        for ((frameIndex, defPath) in defFilePaths.withIndex()) {
            if (cancelRequested) {
                engineErrorCode = ERROR_CANCELLED
                break
            }
            val frameLabel = "Processing Frame ${frameIndex + 1}/$totalFrames..."
            onProgress(
                BatchProgressUpdate(
                    percent = ((frameIndex.toFloat() / totalFrames) * 100).toInt(),
                    status = if (totalFrames > 1) {
                        "Processing frame ${frameIndex + 1} of $totalFrames"
                    } else {
                        "Correlating & solving…"
                    },
                    timerText = frameLabel,
                ),
            )

            val defBytes = File(defPath).readBytes()
            // Store the untouched original bytes (no re-encode) under the user's own
            // filename so the export's raw photos keep their default names.
            try {
                val rawName = (defOriginalNames.getOrNull(frameIndex) ?: File(defPath).name)
                    .substringAfterLast('/').substringAfterLast('\\')
                // Keep the default name; only index-prefix if it would collide.
                val target = File(rawDeformedDir, rawName).let {
                    if (it.exists()) File(rawDeformedDir, String.format("%04d_%s", frameIndex, rawName)) else it
                }
                target.writeBytes(defBytes)
                // Record the name we ACTUALLY wrote: the session index (and the
                // cloud upload) must be able to find these files again.
                persistedRawNames[frameIndex] = target.name
            } catch (e: Exception) {
                Timber.w(e, "Could not persist raw deformed frame %d", frameIndex)
            }
            val callback = object : ProgressCallback {
                override fun onProgressUpdate(percentage: Int) {
                    val frameProgress = (frameIndex.toFloat() / totalFrames) * 100
                    val overallProgress = frameProgress + (percentage.toFloat() / totalFrames)
                    onProgress(
                        BatchProgressUpdate(
                            percent = overallProgress.toInt(),
                            status = if (totalFrames > 1) {
                                "Processing frame ${frameIndex + 1} of $totalFrames"
                            } else {
                                "Correlating & solving…"
                            },
                            timerText = frameLabel,
                        ),
                    )
                }
            }

            outputBuffer.clear()
            // 17 slots: 16 core metrics + mesh-seeding status. Slot 16 pre-set
            // to "unknown" so an engine that only writes 16 leaves it valid.
            val metricsCatcher = FloatArray(17).also { it[16] = EngineStats.MESH_SEEDING_UNKNOWN.toFloat() }

            val validPointsCount = IndicVisionNativeLib.computeFullFieldDirect(
                refBytes, defBytes, params.maskData,
                params.finalRectX, params.finalRectY, params.finalRectW, params.finalRectH,
                params.step, params.subset, params.strainWin, true, true, false, params.applyBlur,
                params.useNlvc, params.use6x6,
                outputBuffer, callback, metricsCatcher,
            )

            if (validPointsCount < 0) {
                engineErrorCode = validPointsCount
                break
            }

            if (frameIndex == 0) {
                firstFrameValidPoints = validPointsCount
                engineStatsArray = metricsCatcher.clone()
                firstFrameAvgIters = metricsCatcher[8]
            }

            if (validPointsCount == 0) continue

            val outputFile = File(batchDir, String.format("frame_%04d.dat", frameIndex))
            outputFile.outputStream().use { fos ->
                val bytes = ByteArray(validPointsCount * DicResult.BYTES_PER_POINT)
                outputBuffer.position(0)
                outputBuffer.get(bytes, 0, bytes.size)
                fos.write(bytes)
            }

            totalPointsSolved += validPointsCount
            lastConvergence = metricsCatcher[15]
            onProgress(
                BatchProgressUpdate(
                    percent = (((frameIndex + 1).toFloat() / totalFrames) * 100).toInt(),
                    status = "Processing frame ${frameIndex + 1} of $totalFrames",
                    timerText = frameLabel,
                    pointsSolved = totalPointsSolved,
                    convergencePercent = lastConvergence,
                ),
            )

            @Suppress("ExplicitGarbageCollectionCall")
            System.gc()
        }

        val executionTimeMs = (System.currentTimeMillis() - params.processingStartTime).toInt()

        if (firstFrameValidPoints > 0 && engineErrorCode != ERROR_CANCELLED) {
            currentSessionId = "Pending_Cloud_Sync_" + UUID.randomUUID().toString().take(8)

            // Persist a viewable copy of the reference next to the frames —
            // the Home list and reopened sessions depend on it surviving.
            val refPngPath = writeReferenceCopy(batchDir, refBytes)
            lastRefPath = refPngPath

            val cloudEnabled = DicSettings.saveToCloud(appContext)
            val saved = SessionStore.upsert(
                appContext,
                buildSessionRecord(
                    appContext, localSessionId, batchDir, refPngPath, params, cloudEnabled,
                    firstFrameValidPoints, firstFrameAvgIters, executionTimeMs,
                    persistedRawNames,
                ),
            )
            if (!saved) {
                // Race: limit filled between the pre-check and persist.
                engineErrorCode = ERROR_SESSION_LIMIT
            } else if (cloudEnabled) {
                // Everything the worker needs now lives in the SessionRecord.
                CloudSync.enqueueUpload(appContext, localSessionId)
            } else {
                Timber.d("Save to cloud is off — session %s stays local only", localSessionId)
            }
        }

        BatchAnalysisOutcome(
            engineErrorCode = engineErrorCode,
            firstFrameValidPoints = firstFrameValidPoints,
            totalFrames = totalFrames,
            executionTimeMs = executionTimeMs,
            batchDirPath = batchDir.absolutePath,
        )
    }

    /** Writes a PNG copy of the reference into the session dir; returns its path. */
    private fun writeReferenceCopy(sessionDir: File, refBytes: ByteArray): String {
        val refPngFile = File(sessionDir, "reference.png")
        var refBmp: Bitmap? = null
        try {
            refBmp = IndicVisionNativeLib.getPreviewFromBytes(refBytes, realRefWidth)
            refPngFile.outputStream().use { out ->
                refBmp?.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } finally {
            refBmp?.recycle()
        }
        return refPngFile.absolutePath
    }

    @Suppress("LongParameterList") // one-shot assembly of the index row
    /**
     * Default name for a new analysis: the reference file's base name plus the
     * run's timestamp. The name alone used to be the reference file name, so
     * every run off the same reference produced an identical, indistinguishable
     * row in the Home list. A user-chosen name always wins over this.
     */
    private fun defaultSessionName(refFileName: String, now: Long): String {
        val base = refFileName.substringBeforeLast('.').ifBlank { "Analysis" }
        val stamp = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(now))
        return "$base · $stamp"
    }

    private fun buildSessionRecord(
        appContext: Context,
        localSessionId: String,
        batchDir: File,
        refPngPath: String,
        params: BatchAnalysisParams,
        cloudEnabled: Boolean,
        pointsConverged: Int,
        avgIterations: Float,
        executionTimeMs: Int,
        persistedRawNames: List<String>,
    ): SessionRecord {
        val now = System.currentTimeMillis()
        // Re-runs upsert over the same id: keep the original creation time
        // and any user-chosen name.
        val existing = SessionStore.get(appContext, localSessionId)
        val cleanRefName = refName.removePrefix("Ref: ")
        val convergence = engineStatsArray?.getOrNull(15) ?: 0f
        return SessionRecord(
            id = localSessionId,
            name = existing?.name ?: defaultSessionName(cleanRefName, now),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            frameCount = defFilePaths.size,
            subset = params.subset,
            step = params.step,
            strainWindow = params.strainWin,
            use6x6 = params.use6x6,
            imgW = realRefWidth,
            imgH = realRefHeight,
            roiX = params.finalRectX,
            roiY = params.finalRectY,
            roiW = params.finalRectW,
            roiH = params.finalRectH,
            refPath = refPngPath,
            refName = cleanRefName,
            sessionDir = batchDir.absolutePath,
            // The names actually on disk in raw_deformed/ — reopening a session,
            // exporting and cloud upload all resolve the images by these.
            defNames = persistedRawNames.mapIndexed { i, persisted ->
                persisted.ifBlank {
                    (defOriginalNames.getOrNull(i) ?: defFilePaths[i].substringAfterLast('/'))
                        .substringAfterLast('/').substringAfterLast('\\')
                }
            },
            headline = String.format(java.util.Locale.US, "%.1f%% converged", convergence),
            engineStats = engineStatsArray?.toList() ?: emptyList(),
            strainMethod = if (params.useNlvc) "NLVC" else "VSG",
            pointsConverged = pointsConverged,
            avgIterations = avgIterations,
            executionTimeMs = executionTimeMs,
            syncState = if (cloudEnabled) SessionRecord.SyncState.PENDING else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }

}
