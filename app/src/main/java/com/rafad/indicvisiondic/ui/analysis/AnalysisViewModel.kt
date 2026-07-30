// runBatchAnalysis / runVsgSweep stream frames through the native engine in one
// cohesive loop (per-frame persist, solve, progress, cancel); the method size,
// branching, break/continue and broad per-frame catch are inherent to that
// pipeline and its literal step constants, so those rules are suppressed here.
@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "CyclomaticComplexMethod",
    "LoopWithTooManyJumpStatements",
    "TooGenericExceptionCaught",
)

package com.rafad.indicvisiondic.ui.analysis
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.IndicVisionNativeLib
import com.rafad.indicvisiondic.ProgressCallback
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.CloudSync
import com.rafad.indicvisiondic.data.DicSettings
import com.rafad.indicvisiondic.data.SessionPaths
import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.data.SessionStore
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.report.EngineStats
import com.rafad.indicvisiondic.report.VisualizationEngine
import com.rafad.indicvisiondic.ui.common.BitmapDecode
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Holds analysis inputs/state across configuration changes and runs the
 * batch solve: streams each deformed frame through the native engine
 * ([IndicVisionNativeLib]) on a dedicated thread, writes per-frame `.dat`
 * results, and enqueues cloud sync via DicUploadWorker.
 */
@Suppress("TooManyFunctions") // both run modes plus their session bookkeeping
class AnalysisViewModel : ViewModel() {

    companion object {
        /** @see AnalysisRunCodes.ERROR_CANCELLED */
        const val ERROR_CANCELLED = AnalysisRunCodes.ERROR_CANCELLED

        /** @see AnalysisRunCodes.ERROR_SESSION_LIMIT */
        const val ERROR_SESSION_LIMIT = AnalysisRunCodes.ERROR_SESSION_LIMIT

        /** Index of the height in an `[x, y, w, h]` ROI array. */
        private const val ROI_H = 3

        /** Below this, the correlation has effectively lost the speckle. */
        const val MIN_CONVERGENCE_PERCENT = 50f

        /**
         * How many consecutive low-convergence frames end the run. One bad frame
         * can be a transient — a flash, a knock — so a single strike would abort
         * runs that would have recovered.
         */
        const val LOW_CONVERGENCE_STRIKES = 2
    }

    // NATIVE THREAD PINNING: All JNI/OpenMP calls are routed through the global
    // IndicVisionNativeLib.nativeDispatcher to ensure thread affinity.

    var refBytes: ByteArray? = null
    var roiMaskBytes: ByteArray? = null
    var defFilePaths: List<String> = emptyList()

    /** Original picked filenames, index-aligned with [defFilePaths]. */
    var defOriginalNames: List<String> = emptyList()

    /**
     * Pixel size of each deformed frame, keyed by its path in [defFilePaths].
     * Measured once at import (the bytes are already in hand there) so the
     * match against the reference costs nothing to re-check later.
     *
     * The engine clamps its AKAZE search window to the *reference* size and
     * then applies that same window to the deformed image, so a frame of a
     * different size makes OpenCV throw — swallowed by a `catch (...)` in the
     * JNI layer, which silently degrades seeding. [frameSizeError] is what
     * stops such a batch from ever reaching the engine.
     */
    var defFrameSizes: Map<String, Pair<Int, Int>> = emptyMap()

    /**
     * Best-effort capture/creation time per deformed frame, index-aligned with
     * [defFilePaths]. [Long.MAX_VALUE] means unknown (sorts last by date).
     */
    var defFrameDates: List<Long> = emptyList()

    /** How the user wants deformed frames ordered (image batches only). */
    var defOrderMode: FrameOrderMode = FrameOrderMode.PICKER

    /** Ascending/descending for Name and Date modes. */
    var defOrderDirection: FrameOrderDirection = FrameOrderDirection.ASCENDING

    /** Set when loaded frames do not all match the reference; blocks Compute. */
    var frameSizeError: String? = null

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

    /** Why the last run stopped early (0 = ran to completion), and its planned size. */
    var lastStopCode: Int = 0
    var lastPlannedFrames: Int = 0

    var currentSessionId: String? = null
    var engineStatsArray: FloatArray? = null

    var wizardStep: Int = 1
    var settingsReviewed: Boolean = false

    /**
     * Subset size suggested by [SubsetRecommender] for the current reference
     * image + ROI, or null while it has not been computed. It seeds the subset
     * slider until [subsetUserModified] says the user has taken it over.
     */
    var subsetRecommendation: SubsetRecommender.Result? = null

    /** Identifies the inputs [subsetRecommendation] was computed for. */
    var subsetRecommendationKey: String? = null

    /** Set once the user drags or types a subset size; suppresses re-seeding. */
    var subsetUserModified: Boolean = false

    // ------------------------------------------------------------------
    // Virtual strain gauge study (see [VsgStudy])
    // ------------------------------------------------------------------

    /** True when Run should sweep the parameter space instead of solving once. */
    var sweepMode: Boolean = false

    /** Smallest subset size of the sweep; 0 until a recommendation seeds it. */
    var subsetMin: Int = 0

    /** Largest subset size of the sweep; 0 until a recommendation seeds it. */
    var subsetMax: Int = 0

    /** Smallest strain window of the sweep; 0 until a default seeds it. */
    var strainWinMin: Int = 0

    /** Largest strain window of the sweep; 0 until a default seeds it. */
    var strainWinMax: Int = 0

    /** How many subset sizes the sweep samples across the subset range (x axis). */
    var subsetSamples: Int = VsgStudy.DEFAULT_SUBSET_SAMPLES

    /** How many strain window sizes the sweep samples up to [strainWinMax] (y axis). */
    var strainWinSamples: Int = VsgStudy.DEFAULT_VSG_SAMPLES

    /**
     * Step-depth denominator (z axis): step sizes are subset/2 down to
     * subset/[stepDenominator]. 2 = one step (subset/2), 6 = five steps.
     */
    var stepDenominator: Int = VsgStudy.DEFAULT_STEP_DENOM

    /** True when the line cut runs along x; false for a cut along y. */
    var lineCutHorizontal: Boolean = true

    /**
     * Frame index the sweep is solved against; -1 means the middle of the
     * sequence (1-based frame n/2+1, i.e. 0-based index n/2).
     */
    var vsgFrameIndex: Int = -1

    /** Parameter combination behind each frame of the last sweep, in order. */
    var sweepPlan: List<VsgStudy.Point> = emptyList()

    /** Combinations the engine could not solve in the last sweep. */
    var sweepSkipped: List<VsgStudy.Point> = emptyList()

    /** Engine code per skipped combination, index-aligned with [sweepSkipped]. */
    var sweepSkippedCodes: List<Int> = emptyList()

    /**
     * @param labels one human-readable name per combination, index-aligned with
     *   [plan]; they become the frame names in the viewer and the report
     */
    data class SweepRequest(
        val plan: List<VsgStudy.Point>,
        val labels: List<String>,
        val roi: IntArray,
        val use6x6: Boolean,
        val debugDir: File,
    )

    /**
     * Runs the sweep and persists it as an ordinary session: one `.dat` per
     * parameter combination, so the result viewer and the report treat the
     * combinations exactly as they treat frames.
     */
    suspend fun runVsgSweep(
        appContext: Context,
        request: SweepRequest,
        onProgress: (VsgStudyRunner.Progress) -> Unit,
    ): BatchAnalysisOutcome = withContext(IndicVisionNativeLib.nativeDispatcher) {
        val plan = request.plan
        val roi = request.roi
        val use6x6 = request.use6x6
        val debugDir = request.debugDir
        val bytes = refBytes ?: error("Reference missing")
        val startedAt = System.currentTimeMillis()

        val limited = sessionLimitOutcome(appContext, plan.size)
        if (limited != null) return@withContext limited

        val localSessionId = resolveLocalSessionId()
        val batchDir = SessionStore.dirFor(appContext, localSessionId)
        batchDir.listFiles { f -> f.extension == "dat" }?.forEach { it.delete() }
        lastBatchDirPath = batchDir.absolutePath

        val frameIndex = resolvedVsgFrameIndex()
        val result = VsgStudyRunner.run(
            bytes,
            realRefWidth,
            realRefHeight,
            VsgStudyRunner.Params(
                plan = plan,
                defFramePath = defFilePaths[frameIndex],
                roiX = roi[0],
                roiY = roi[1],
                roiW = roi[2],
                roiH = roi[ROI_H],
                maskData = roiMaskBytes ?: ByteArray(0),
                use6x6 = use6x6,
                debugDir = debugDir,
                outputDir = batchDir,
            ),
            onProgress,
        )

        sweepPlan = result.runs.map { it.point }
        sweepSkipped = result.skipped
        sweepSkippedCodes = result.skippedCodes
        engineStatsArray = result.firstMetrics
        val executionTimeMs = (System.currentTimeMillis() - startedAt).toInt()

        if (result.runs.isEmpty()) {
            return@withContext BatchAnalysisOutcome(
                engineErrorCode = result.engineErrorCode,
                firstFrameValidPoints = 0,
                totalFrames = 0,
                executionTimeMs = executionTimeMs,
                batchDirPath = batchDir.absolutePath,
            )
        }

        persistSweepSession(appContext, localSessionId, batchDir, bytes, result, request, executionTimeMs)

        BatchAnalysisOutcome(
            engineErrorCode = result.engineErrorCode,
            firstFrameValidPoints = result.runs.first().pointsSolved,
            totalFrames = result.runs.size,
            executionTimeMs = executionTimeMs,
            batchDirPath = batchDir.absolutePath,
        )
    }

    /**
     * The deformed frame the sweep was solved against is persisted once, under
     * the name every combination shares — a sweep varies settings, not images.
     */
    @Suppress("LongParameterList") // the run's outputs a sweep session is assembled from
    private fun persistSweepSession(
        appContext: Context,
        localSessionId: String,
        batchDir: File,
        refBytes: ByteArray,
        result: VsgStudyRunner.Result,
        request: SweepRequest,
        executionTimeMs: Int,
    ) {
        val roi = request.roi
        currentSessionId = newPendingSessionId()
        val refPngPath = writeReferenceCopy(batchDir, refBytes)
        lastRefPath = refPngPath
        lastStep = result.runs.first().point.step

        val frameIndex = resolvedVsgFrameIndex()
        val rawName = copyRawDeformed(batchDir, frameIndex)

        val cloudEnabled = DicSettings.saveToCloud(appContext)
        val first = result.runs.first().point
        val defDisplay = rawName.ifBlank {
            defOriginalNames.getOrNull(frameIndex) ?: File(defFilePaths[frameIndex]).name
        }.baseName()
        val skipped = result.skipped
        val summary = sweepSummary(appContext, localSessionId, result, request, defDisplay)
        val record = buildSessionRecord(
            appContext = appContext,
            localSessionId = localSessionId,
            batchDir = batchDir,
            refPngPath = refPngPath,
            settings = RecordSettings(
                subset = first.subset,
                step = first.step,
                strainWin = first.strainWindow,
                roiX = roi[0],
                roiY = roi[1],
                roiW = roi[2],
                roiH = roi[ROI_H],
                useNlvc = false,
                use6x6 = request.use6x6,
            ),
            cloudEnabled = cloudEnabled,
            pointsConverged = result.runs.first().pointsSolved,
            avgIterations = result.firstMetrics?.getOrNull(EngineStats.SLOT_AVG_ITERS) ?: 0f,
            executionTimeMs = executionTimeMs,
            frameCount = result.runs.size,
            // Every combination was solved against the same image, so they all
            // point at the one raw file persisted above.
            defNames = result.runs.map { rawName },
        ).copy(
            name = summary.name,
            // What makes a reopened session a sweep again: without these the
            // viewer would render every frame at the first frame's step size.
            sweepSubsets = result.runs.map { it.point.subset },
            sweepSteps = result.runs.map { it.point.step },
            sweepStrainWindows = result.runs.map { it.point.strainWindow },
            sweepLabels = summary.solvedLabels,
            lineCutHorizontal = lineCutHorizontal,
            sweepSkipSubsets = skipped.map { it.subset },
            sweepSkipSteps = skipped.map { it.step },
            sweepSkipStrainWindows = skipped.map { it.strainWindow },
            sweepSkipCodes = result.skippedCodes,
            stopCode = result.engineErrorCode.also { lastStopCode = it },
            plannedFrameCount = result.runs.size + skipped.size,
            headline = summary.headline,
        )
        if (SessionStore.upsert(appContext, record) && cloudEnabled) {
            CloudSync.enqueueUpload(appContext, localSessionId, allowMetered = true)
        }
    }

    /** The Home-list name, headline and per-frame labels of a finished sweep. */
    private class SweepSummary(
        val solvedLabels: List<String>,
        val name: String,
        val headline: String,
    )

    private fun sweepSummary(
        appContext: Context,
        localSessionId: String,
        result: VsgStudyRunner.Result,
        request: SweepRequest,
        defDisplay: String,
    ): SweepSummary {
        // Labels are plan-aligned; map each solved run back to its plan slot so
        // a skip mid-sweep does not shift later names onto the wrong frame.
        val labelByPoint = request.plan.zip(request.labels).toMap()
        val solvedLabels = result.runs.map { labelByPoint[it.point].orEmpty() }
        val totalPlanned = result.runs.size + result.skipped.size
        val existing = SessionStore.get(appContext, localSessionId)
        val stamp = timestamp(System.currentTimeMillis())
        val name = existing?.name ?: appContext.getString(
            R.string.session_sweep_name_fmt,
            defDisplay.substringBeforeLast('.').ifBlank { defDisplay },
            stamp,
        )
        val headline = appContext.getString(
            R.string.session_sweep_headline_fmt,
            defDisplay,
            result.runs.size,
            totalPlanned,
            result.runs.minOf { it.point.subset },
            result.runs.maxOf { it.point.subset },
        )
        return SweepSummary(solvedLabels, name, headline)
    }

    /** Copies one deformed original into the session dir; returns its name. */
    private fun copyRawDeformed(batchDir: File, frameIndex: Int): String {
        val rawDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val name = (defOriginalNames.getOrNull(frameIndex) ?: File(defFilePaths[frameIndex]).name)
            .baseName()
        return runCatching {
            val target = File(rawDir, name)
            if (target.exists()) target.delete()
            FileInputStream(File(defFilePaths[frameIndex])).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.name
        }.onFailure { Timber.w(it, "Could not persist the sweep's deformed frame") }.getOrDefault("")
    }

    fun isReadyToCompute(): Boolean = refBytes != null && defFilePaths.isNotEmpty()

    fun clearPreviousResults() {
        lastBatchDirPath = null
        lastRefPath = null
        lastDefPath = null
        hasCompletedAnalysis = false
        workingLocalId = null
    }

    /**
     * Identity of the working session on the Home list. Every re-run reuses it,
     * so the exploration loop keeps updating one row instead of leaving a trail
     * of near-identical ones. New inputs reset it via [clearPreviousResults].
     */
    var workingLocalId: String? = null

    /** True when the next completed run would create a new Home-list row. */
    fun wouldCreateNewSession(): Boolean = workingLocalId == null

    /**
     * Hard stop before any native work: a new session cannot exceed the account
     * quota. Re-runs over an existing [workingLocalId] are still allowed.
     * Returns the outcome to abort with, or null when the run may proceed.
     */
    @Suppress("ReturnCount") // two independent all-clear checks, then the stop
    private fun sessionLimitOutcome(appContext: Context, plannedFrames: Int): BatchAnalysisOutcome? {
        if (!wouldCreateNewSession()) return null
        TokenStore.refreshSessionLimit(appContext, SessionStore.list(appContext).size)
        if (!TokenStore.isSessionLimitReached(appContext)) return null
        Timber.w("Hard stop: analysis blocked at session limit")
        return BatchAnalysisOutcome(
            engineErrorCode = ERROR_SESSION_LIMIT,
            firstFrameValidPoints = 0,
            totalFrames = plannedFrames,
            executionTimeMs = 0,
            batchDirPath = "",
        )
    }

    private fun resolveLocalSessionId(): String =
        workingLocalId ?: UUID.randomUUID().toString().take(12).also { workingLocalId = it }

    /**
     * The deformed frame a sweep is solved against: [vsgFrameIndex] when it
     * points at a real frame, otherwise the middle of the sequence
     * (0-based index n/2).
     */
    private fun resolvedVsgFrameIndex(): Int {
        val n = defFilePaths.size
        if (n <= 0) return -1
        val last = n - 1
        return if (vsgFrameIndex < 0 || vsgFrameIndex > last) {
            (n / 2).coerceIn(0, last)
        } else {
            vsgFrameIndex
        }
    }

    /** Placeholder cloud id a session carries until the upload worker assigns the real one. */
    private fun newPendingSessionId(): String =
        "Pending_Cloud_Sync_" + UUID.randomUUID().toString().take(8)

    /** The "MMM d, HH:mm:ss" stamp used in default session names. */
    private fun timestamp(millis: Long): String =
        SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(Date(millis))

    /** The engine settings a session row records, shared by both run modes. */
    @Suppress("LongParameterList") // one row of the session index
    data class RecordSettings(
        val subset: Int,
        val step: Int,
        val strainWin: Int,
        val roiX: Int,
        val roiY: Int,
        val roiW: Int,
        val roiH: Int,
        val useNlvc: Boolean,
        val use6x6: Boolean,
    )

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

    /**
     * Cooperative cancel: checked between frames here, and forwarded to the
     * engine, which polls it inside its point loops. Setting it therefore stops
     * the solve already running rather than only the ones after it.
     */
    @Volatile
    var cancelRequested = false
        set(value) {
            field = value
            IndicVisionNativeLib.setCancelRequested(value)
        }

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
        /**
         * Frames that actually produced a field. Below the planned count when the
         * run stopped early — the frames before that point are kept, so this is
         * what the session holds and what the user should be told.
         */
        val totalFrames: Int,
        val executionTimeMs: Int,
        val batchDirPath: String,
        /**
         * Which frame the run stopped on, and its image name — the failure is
         * almost always a property of one image pair, so naming it is the
         * difference between an actionable message and a shrug. -1 / null when
         * the run finished.
         */
        val failedFrameIndex: Int = -1,
        val failedFrameName: String? = null,
    )

    /**
     * Full-field batch compute + offline upload queue. All JNI calls run on the native dispatcher.
     */
    suspend fun runBatchAnalysis(
        appContext: Context,
        params: BatchAnalysisParams,
        onProgress: (BatchProgressUpdate) -> Unit,
    ): BatchAnalysisOutcome = withContext(IndicVisionNativeLib.nativeDispatcher) {
        val limited = sessionLimitOutcome(appContext, defFilePaths.size)
        if (limited != null) return@withContext limited

        // Results live in app-private persistent storage (NOT cacheDir, which
        // the OS may evict): one directory per Home-list session.
        val localSessionId = resolveLocalSessionId()
        val batchDir = SessionStore.dirFor(appContext, localSessionId)
        batchDir.listFiles { f -> f.extension == "dat" }?.forEach { it.delete() }

        lastBatchDirPath = batchDir.absolutePath
        lastStep = params.step

        if (!params.debugDir.exists()) params.debugDir.mkdirs()
        IndicVisionNativeLib.setDebugOutputDir(params.debugDir.absolutePath)

        val plannedFrames = defFilePaths.size
        val refBytes = refBytes ?: error("Reference missing")

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
        val convergenceGate = ConvergenceGate()
        var failedFrameIndex = -1
        var solvedFrames = 0

        // Persist the raw deformed originals alongside the reference so exports
        // (and reopened sessions) can bundle them. Cleared per re-run.
        val rawDeformedDir = File(batchDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }

        // The filenames actually written into raw_deformed/, index-aligned with
        // the frames. These (not the cache-copy paths) are what the session index
        // and the cloud upload look the images up by. Blank = persist failed.
        val persistedRawNames = MutableList(plannedFrames) { "" }

        for ((frameIndex, defPath) in defFilePaths.withIndex()) {
            if (cancelRequested) {
                engineErrorCode = ERROR_CANCELLED
                break
            }
            val frameLabel = "Processing Frame ${frameIndex + 1}/$plannedFrames..."
            onProgress(
                BatchProgressUpdate(
                    percent = ((frameIndex.toFloat() / plannedFrames) * 100).toInt(),
                    status = if (plannedFrames > 1) {
                        "Processing frame ${frameIndex + 1} of $plannedFrames"
                    } else {
                        "Correlating & solving…"
                    },
                    timerText = frameLabel,
                ),
            )

            // Persist the untouched original under the user's own filename so
            // exports keep default names — one read feeds both the session copy
            // and the JNI buffer (avoids Files.copy + a second heap read).
            val defBytes = try {
                File(defPath).readBytes()
            } catch (e: Exception) {
                Timber.e(e, "Could not read deformed frame %d", frameIndex)
                continue
            }
            try {
                val rawName = (defOriginalNames.getOrNull(frameIndex) ?: File(defPath).name)
                    .baseName()
                // Keep the default name; only index-prefix if it would collide.
                val target = File(rawDeformedDir, rawName).let {
                    if (it.exists()) {
                        File(rawDeformedDir, String.format(Locale.US, "%04d_%s", frameIndex, rawName))
                    } else {
                        it
                    }
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
                    val frameProgress = (frameIndex.toFloat() / plannedFrames) * 100
                    val overallProgress = frameProgress + (percentage.toFloat() / plannedFrames)
                    onProgress(
                        BatchProgressUpdate(
                            percent = overallProgress.toInt(),
                            status = if (plannedFrames > 1) {
                                "Processing frame ${frameIndex + 1} of $plannedFrames"
                            } else {
                                "Correlating & solving…"
                            },
                            timerText = frameLabel,
                        ),
                    )
                }
            }

            outputBuffer.clear()
            // 16 core metrics + optional mesh-seeding slot, pre-set to "unknown"
            // so an engine that only writes the core slots leaves it valid.
            val metricsCatcher = FloatArray(EngineStats.SLOT_COUNT).also {
                it[EngineStats.SLOT_MESH_SEEDING] = EngineStats.MESH_SEEDING_UNKNOWN.toFloat()
            }

            val validPointsCount = IndicVisionNativeLib.computeFullFieldDirect(
                refBytes, defBytes, params.maskData,
                params.finalRectX, params.finalRectY, params.finalRectW, params.finalRectH,
                params.step, params.subset, params.strainWin, true, true, false, params.applyBlur,
                params.useNlvc, params.use6x6,
                outputBuffer, callback, metricsCatcher,
            )

            if (validPointsCount < 0) {
                engineErrorCode = validPointsCount
                failedFrameIndex = frameIndex
                break
            }

            if (frameIndex == 0) {
                firstFrameValidPoints = validPointsCount
                engineStatsArray = metricsCatcher.clone()
                firstFrameAvgIters = metricsCatcher[EngineStats.SLOT_AVG_ITERS]
            }

            if (validPointsCount == 0) continue

            val outputFile = File(batchDir, String.format(Locale.US, "frame_%04d.dat", frameIndex))
            outputFile.outputStream().use { fos ->
                val bytes = ByteArray(validPointsCount * DicResult.BYTES_PER_POINT)
                outputBuffer.position(0)
                outputBuffer.get(bytes, 0, bytes.size)
                fos.write(bytes)
            }

            solvedFrames++
            totalPointsSolved += validPointsCount
            lastConvergence = metricsCatcher[EngineStats.SLOT_CONVERGENCE]
            if (convergenceGate.record(lastConvergence)) {
                engineErrorCode = AnalysisRunCodes.ERROR_LOW_CONVERGENCE
                failedFrameIndex = frameIndex
                break
            }
            onProgress(
                BatchProgressUpdate(
                    percent = (((frameIndex + 1).toFloat() / plannedFrames) * 100).toInt(),
                    status = "Processing frame ${frameIndex + 1} of $plannedFrames",
                    timerText = frameLabel,
                    pointsSolved = totalPointsSolved,
                    convergencePercent = lastConvergence,
                ),
            )
        }

        val executionTimeMs = (System.currentTimeMillis() - params.processingStartTime).toInt()

        if (firstFrameValidPoints > 0 && engineErrorCode != ERROR_CANCELLED) {
            currentSessionId = newPendingSessionId()

            // Persist a viewable copy of the reference next to the frames —
            // the Home list and reopened sessions depend on it surviving.
            val refPngPath = writeReferenceCopy(batchDir, refBytes)
            lastRefPath = refPngPath

            val cloudEnabled = DicSettings.saveToCloud(appContext)
            val saved = SessionStore.upsert(
                appContext,
                buildSessionRecord(
                    appContext = appContext,
                    localSessionId = localSessionId,
                    batchDir = batchDir,
                    refPngPath = refPngPath,
                    settings = RecordSettings(
                        subset = params.subset,
                        step = params.step,
                        strainWin = params.strainWin,
                        roiX = params.finalRectX,
                        roiY = params.finalRectY,
                        roiW = params.finalRectW,
                        roiH = params.finalRectH,
                        useNlvc = params.useNlvc,
                        use6x6 = params.use6x6,
                    ),
                    cloudEnabled = cloudEnabled,
                    pointsConverged = firstFrameValidPoints,
                    avgIterations = firstFrameAvgIters,
                    executionTimeMs = executionTimeMs,
                    frameCount = solvedFrames,
                    stopCode = engineErrorCode.also { lastStopCode = it },
                    plannedFrameCount = plannedFrames.also { lastPlannedFrames = it },
                    // The names actually on disk in raw_deformed/ — reopening a
                    // session, exporting and cloud upload resolve images by these.
                    defNames = persistedRawNames.mapIndexed { i, persisted ->
                        persisted.ifBlank {
                            (defOriginalNames.getOrNull(i) ?: defFilePaths[i])
                                .baseName()
                        }
                    },
                ),
            )
            if (!saved) {
                // Race: limit filled between the pre-check and persist.
                engineErrorCode = ERROR_SESSION_LIMIT
            } else if (cloudEnabled) {
                // Everything the worker needs now lives in the SessionRecord.
                CloudSync.enqueueUpload(appContext, localSessionId, allowMetered = true)
            } else {
                Timber.d("Save to cloud is off — session %s stays local only", localSessionId)
            }
        }

        BatchAnalysisOutcome(
            engineErrorCode = engineErrorCode,
            firstFrameValidPoints = firstFrameValidPoints,
            totalFrames = solvedFrames,
            executionTimeMs = executionTimeMs,
            batchDirPath = batchDir.absolutePath,
            failedFrameIndex = failedFrameIndex,
            failedFrameName = failedFrameIndex
                .takeIf { it >= 0 }
                ?.let { defFilePaths.getOrNull(it)?.substringAfterLast('/') },
        )
    }

    /**
     * Writes a display-sized PNG of the reference into the session dir for Home
     * list + viewer UI. The engine still uses full [refBytes] in memory.
     */
    private fun writeReferenceCopy(sessionDir: File, refBytes: ByteArray): String {
        val refPngFile = File(sessionDir, "reference.png")
        var refBmp: Bitmap? = null
        try {
            // Cap longest edge for the on-disk display copy — full-width preview
            // was wasteful for Home thumbs / viewer chrome. Fallback chain keeps
            // a decodable file so cloud backup and report base images still work.
            refBmp = IndicVisionNativeLib.getPreviewFromBytes(
                refBytes,
                VisualizationEngine.DISPLAY_MAX_EDGE,
            ) ?: decodeDisplaySized(refBytes)
            val bmp = refBmp
            if (bmp != null) {
                refPngFile.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
            } else {
                refPngFile.writeBytes(refBytes)
            }
        } catch (e: Exception) {
            Timber.w(e, "Reference preview failed; storing the raw reference bytes")
            runCatching { refPngFile.writeBytes(refBytes) }
        } finally {
            refBmp?.recycle()
        }
        return refPngFile.absolutePath
    }

    /** Bounds-aware decode capped to [VisualizationEngine.DISPLAY_MAX_EDGE]. */
    private fun decodeDisplaySized(refBytes: ByteArray): Bitmap? =
        BitmapDecode.decodeByteArrayCapped(refBytes)

    @Suppress("LongParameterList") // one-shot assembly of the index row
    /**
     * Default name for a new analysis: the reference file's base name plus the
     * run's timestamp. The name alone used to be the reference file name, so
     * every run off the same reference produced an identical, indistinguishable
     * row in the Home list. A user-chosen name always wins over this.
     */
    private fun defaultSessionName(refFileName: String, now: Long): String {
        val base = refFileName.substringBeforeLast('.').ifBlank { "Analysis" }
        val stamp = timestamp(now)
        return "$base · $stamp"
    }

    @Suppress("LongParameterList") // one-shot assembly of the session index row
    private fun buildSessionRecord(
        appContext: Context,
        localSessionId: String,
        batchDir: File,
        refPngPath: String,
        settings: RecordSettings,
        cloudEnabled: Boolean,
        pointsConverged: Int,
        avgIterations: Float,
        executionTimeMs: Int,
        frameCount: Int,
        defNames: List<String>,
        stopCode: Int = 0,
        plannedFrameCount: Int = 0,
    ): SessionRecord {
        val now = System.currentTimeMillis()
        // Re-runs upsert over the same id: keep the original creation time
        // and any user-chosen name.
        val existing = SessionStore.get(appContext, localSessionId)
        val convergence = engineStatsArray?.getOrNull(EngineStats.SLOT_CONVERGENCE) ?: 0f
        return SessionRecord(
            id = localSessionId,
            name = existing?.name ?: defaultSessionName(refName, now),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            frameCount = frameCount,
            subset = settings.subset,
            step = settings.step,
            strainWindow = settings.strainWin,
            use6x6 = settings.use6x6,
            imgW = realRefWidth,
            imgH = realRefHeight,
            roiX = settings.roiX,
            roiY = settings.roiY,
            roiW = settings.roiW,
            roiH = settings.roiH,
            refPath = refPngPath,
            refName = refName,
            sessionDir = batchDir.absolutePath,
            defNames = defNames,
            headline = String.format(java.util.Locale.US, "%.1f%% converged", convergence),
            engineStats = engineStatsArray?.toList() ?: emptyList(),
            stopCode = stopCode,
            plannedFrameCount = plannedFrameCount,
            strainMethod = if (settings.useNlvc) "NLVC" else "VSG",
            pointsConverged = pointsConverged,
            avgIterations = avgIterations,
            executionTimeMs = executionTimeMs,
            syncState = if (cloudEnabled) SessionRecord.SyncState.PENDING else SessionRecord.SyncState.LOCAL_ONLY,
        )
    }
}

/** Filename without any directory prefix, handling both '/' and '\' separators. */
private fun String.baseName(): String = substringAfterLast('/').substringAfterLast('\\')
