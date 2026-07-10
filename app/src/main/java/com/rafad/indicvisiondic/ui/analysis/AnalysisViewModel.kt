package com.rafad.indicvisiondic.ui.analysis
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.IndicVisionNativeLib
import com.rafad.indicvisiondic.ProgressCallback
import com.rafad.indicvisiondic.data.DicSettings
import com.rafad.indicvisiondic.data.DicUploadWorker
import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.data.SessionStore
import com.rafad.indicvisiondic.data.SupabaseManager
import com.rafad.indicvisiondic.report.EngineStats
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
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
            SessionStore.upsert(appContext, buildSessionRecord(appContext, localSessionId, batchDir, refPngPath, params, cloudEnabled))

            if (cloudEnabled) {
                enqueueUploadWorkers(
                    appContext,
                    params,
                    batchDir,
                    localSessionId,
                    refPngPath,
                    firstFrameValidPoints,
                    firstFrameAvgIters,
                    executionTimeMs,
                )
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
    private fun buildSessionRecord(
        appContext: Context,
        localSessionId: String,
        batchDir: File,
        refPngPath: String,
        params: BatchAnalysisParams,
        cloudEnabled: Boolean,
    ): SessionRecord {
        val now = System.currentTimeMillis()
        // Re-runs upsert over the same id: keep the original creation time
        // and any user-chosen name.
        val existing = SessionStore.get(appContext, localSessionId)
        val cleanRefName = refName.removePrefix("Ref: ")
        val convergence = engineStatsArray?.getOrNull(15) ?: 0f
        return SessionRecord(
            id = localSessionId,
            name = existing?.name ?: cleanRefName.substringBeforeLast('.').ifBlank { "Analysis" },
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
            defNames = defFilePaths.map { it.substringAfterLast('/') },
            headline = String.format(java.util.Locale.US, "%.1f%% converged", convergence),
            engineStats = engineStatsArray?.toList() ?: emptyList(),
            syncState = if (cloudEnabled) SessionRecord.SyncState.PENDING else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }

    @Suppress("LongParameterList", "LongMethod") // per-frame worker Data assembly
    private suspend fun enqueueUploadWorkers(
        appContext: Context,
        params: BatchAnalysisParams,
        batchDir: File,
        localSessionId: String,
        refPngPath: String,
        firstFrameValidPoints: Int,
        firstFrameAvgIters: Float,
        executionTimeMs: Int,
    ) = withContext(Dispatchers.IO) {
        Timber.d("Engine finished — queueing offline upload workers")

        try {
            val generatedRefPath = refPngPath

            val currentUser = SupabaseManager.client.auth.currentUserOrNull()
            val userEmail = currentUser?.email ?: "Offline_User"
            val userId = currentUser?.id ?: "Offline_ID"

            for ((frameIndex, rawDefPath) in defFilePaths.withIndex()) {
                var defBmp: Bitmap? = null
                try {
                    var generatedDefPath = ""
                    val rawFile = File(rawDefPath)
                    if (rawFile.exists() && rawFile.length() < 50_000_000) {
                        val defBytes = rawFile.readBytes()
                        defBmp = IndicVisionNativeLib.getPreviewFromBytes(defBytes, realRefWidth)
                        val defPngFile = File(
                            params.cacheDir,
                            "temp_def_${System.currentTimeMillis()}_frame_$frameIndex.png",
                        )
                        defPngFile.outputStream().use { out ->
                            defBmp?.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        generatedDefPath = defPngFile.absolutePath
                    }

                    val datFile = File(batchDir, String.format("frame_%04d.dat", frameIndex))
                    val uploadData = Data.Builder()
                        .putString(DicKeys.USER_ID, userId)
                        .putString(DicKeys.USER_EMAIL, userEmail)
                        .putString(DicKeys.REF_PATH, generatedRefPath)
                        .putString(DicKeys.DEF_PATH, generatedDefPath)
                        .putString(DicKeys.DAT_PATH, datFile.absolutePath)
                        .putString(DicKeys.FRAME_NAME, "Frame_${frameIndex + 1}")
                        .putString(DicKeys.REF_NAME, refName.removePrefix("Ref: "))
                        .putInt(DicKeys.IMG_W, realRefWidth)
                        .putInt(DicKeys.IMG_H, realRefHeight)
                        .putInt(DicKeys.STEP, params.step)
                        .putInt(DicKeys.SUBSET, params.subset)
                        .putInt(DicKeys.STRAIN_WIN, params.strainWin)
                        .putString(DicKeys.STRAIN_METHOD, if (params.useNlvc) "NLVC" else "VSG")
                        .putInt(DicKeys.ROI_X, params.finalRectX)
                        .putInt(DicKeys.ROI_Y, params.finalRectY)
                        .putInt(DicKeys.ROI_W, params.finalRectW)
                        .putInt(DicKeys.ROI_H, params.finalRectH)
                        .putFloatArray(DicKeys.ENGINE_STATS, engineStatsArray ?: FloatArray(16))
                        .putInt(DicKeys.POINTS_CONVERGED, firstFrameValidPoints)
                        .putFloat(DicKeys.AVG_ITERS, firstFrameAvgIters)
                        .putInt(DicKeys.EXEC_TIME, executionTimeMs)
                        .putString(DicKeys.SESSION_LOCAL_ID, localSessionId)
                        .build()

                    val uploadWork = OneTimeWorkRequestBuilder<DicUploadWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        .setInputData(uploadData)
                        .build()

                    WorkManager.getInstance(appContext).enqueue(uploadWork)
                    Timber.d("-> SUCCESS! Worker queued for Frame ${frameIndex + 1}.")
                } finally {
                    defBmp?.recycle()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to enqueue batch workers")
        }
    }
}
