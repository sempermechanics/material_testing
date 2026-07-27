package com.rafad.indicvisiondic.ui.analysis

import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.IndicVisionNativeLib
import com.rafad.indicvisiondic.ProgressCallback
import com.rafad.indicvisiondic.report.EngineStats
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Executes the sweep a [VsgStudy] plans: solves one deformed frame once per
 * parameter combination and writes each result as its own `.dat` file.
 *
 * The output is deliberately shaped like an ordinary batch of frames — one
 * `.dat` per combination, named in plan order — so the sweep lands in the
 * normal result viewer and the normal report path, with each combination
 * browsable as its own specimen.
 *
 * All calls here are JNI: the caller must already be on
 * [IndicVisionNativeLib.nativeDispatcher].
 */
object VsgStudyRunner {

    /** Outcome code for a user-cancelled sweep. */
    const val ERROR_CANCELLED = AnalysisRunCodes.ERROR_CANCELLED

    /** Engine returned no usable field for one of the sweep's combinations. */
    const val ERROR_ENGINE_FAILED = -97

    private const val PERCENT = 100

    @Suppress("LongParameterList") // one-shot bundle of engine inputs
    data class Params(
        val plan: List<VsgStudy.Point>,
        /** The single deformed frame every combination is solved against. */
        val defFramePath: String,
        val roiX: Int,
        val roiY: Int,
        val roiW: Int,
        val roiH: Int,
        val maskData: ByteArray,
        val use6x6: Boolean,
        val debugDir: File,
        /** Where the per-combination `.dat` files are written. */
        val outputDir: File,
    )

    data class Progress(
        val runIndex: Int,
        val totalRuns: Int,
        val percent: Int,
        val point: VsgStudy.Point,
        val pointsSolved: Int,
        val convergencePercent: Float,
    )

    /** One completed combination and the file holding its field. */
    data class RunOutcome(
        val point: VsgStudy.Point,
        val datFile: File,
        val pointsSolved: Int,
    )

    /**
     * @param runs one entry per combination that produced a field, in plan order
     * @param firstMetrics engine telemetry of the first combination, for the
     *   session record; null when nothing completed
     * @param skipped combinations the engine could not solve; the sweep carries
     *   on past them, so this is empty on a clean run and non-empty on a
     *   partial one
     * @param engineErrorCode 0 when at least one combination solved,
     *   [ERROR_CANCELLED] when the user stopped it, otherwise the engine's own
     *   negative code from the last attempt
     */
    data class Result(
        val runs: List<RunOutcome>,
        val firstMetrics: FloatArray?,
        val engineErrorCode: Int,
        val skipped: List<VsgStudy.Point> = emptyList(),
    )

    /** Cooperative cancel, polled between solves (a solve is not interruptible). */
    @Volatile
    var cancelRequested = false

    /** Runs every combination of [params].plan in order. */
    fun run(
        refBytes: ByteArray,
        refWidth: Int,
        refHeight: Int,
        params: Params,
        onProgress: (Progress) -> Unit,
    ): Result {
        cancelRequested = false
        if (!params.debugDir.exists()) params.debugDir.mkdirs()
        params.outputDir.mkdirs()
        IndicVisionNativeLib.setDebugOutputDir(params.debugDir.absolutePath)
        IndicVisionNativeLib.initializeReference(refBytes, params.maskData, refWidth, refHeight, false)

        val defBytes = File(params.defFramePath).readBytes()
        val buffer = allocateFor(params)
        val runs = ArrayList<RunOutcome>(params.plan.size)
        var firstMetrics: FloatArray? = null
        var errorCode = 0
        val total = params.plan.size

        val skipped = ArrayList<VsgStudy.Point>()
        var lastEngineError = 0

        // A cancel short-circuits the remaining solves rather than interrupting
        // one: the native call cannot be stopped once it has started.
        for ((index, point) in params.plan.withIndex().takeWhile { !cancelRequested }) {
            val metrics = newMetrics()
            onProgress(Progress(index, total, index * PERCENT / maxOf(1, total), point, 0, -1f))

            val solved = solve(refBytes, defBytes, params, point, buffer, metrics)
            if (solved <= 0) {
                // One combination failing says nothing about the rest — a small
                // subset can fail to correlate where a larger one succeeds, and
                // the plan starts at the smallest. Skip it and keep sweeping;
                // aborting here would throw away every setting that does work.
                Timber.w(
                    "VSG sweep skipped subset=%d step=%d window=%d (engine code %d)",
                    point.subset,
                    point.step,
                    point.strainWindow,
                    solved,
                )
                skipped.add(point)
                lastEngineError = solved
                continue
            }
            if (firstMetrics == null) firstMetrics = metrics

            // Named by solved index (not plan index) so .dat files stay dense
            // and line up with [runs] / upload's frame_0000..N-1 walk — skipped
            // combinations must not leave gaps the cloud packager cannot find.
            val datFile = File(
                params.outputDir,
                String.format(Locale.US, "frame_%04d.dat", runs.size),
            )
            writeField(buffer, solved, datFile)
            runs.add(RunOutcome(point, datFile, solved))

            onProgress(
                Progress(
                    runIndex = index,
                    totalRuns = total,
                    percent = (index + 1) * PERCENT / maxOf(1, total),
                    point = point,
                    pointsSolved = solved,
                    convergencePercent = metrics[EngineStats.SLOT_CONVERGENCE],
                ),
            )
        }

        // Some combinations failing is a partial success. Only a sweep that
        // produced nothing reports the engine's own code, which says why.
        if (cancelRequested) {
            errorCode = ERROR_CANCELLED
        } else if (runs.isEmpty() && skipped.isNotEmpty()) {
            errorCode = lastEngineError
        }
        return Result(runs, firstMetrics, errorCode, skipped)
    }

    private fun newMetrics() = FloatArray(EngineStats.SLOT_COUNT).also {
        it[EngineStats.SLOT_MESH_SEEDING] = EngineStats.MESH_SEEDING_UNKNOWN.toFloat()
    }

    /**
     * A buffer large enough for the densest combination in the plan — the
     * finest step fills the most grid points — so the sweep allocates direct
     * memory once instead of once per run.
     */
    private fun allocateFor(params: Params): ByteBuffer {
        val finestStep = params.plan.minOfOrNull { it.step } ?: 1
        val gridW = params.roiW / finestStep
        val gridH = params.roiH / finestStep
        return ByteBuffer
            .allocateDirect(maxOf(1, gridW * gridH) * DicResult.BYTES_PER_POINT)
            .order(ByteOrder.nativeOrder())
    }

    private fun writeField(buffer: ByteBuffer, validPoints: Int, target: File) {
        val bytes = ByteArray(validPoints * DicResult.BYTES_PER_POINT)
        buffer.position(0)
        buffer.get(bytes, 0, bytes.size)
        target.outputStream().use { it.write(bytes) }
    }

    /** One full-field solve. Returns the engine's point count, negative on failure. */
    @Suppress("LongParameterList") // mirrors the native signature
    private fun solve(
        refBytes: ByteArray,
        defBytes: ByteArray,
        params: Params,
        point: VsgStudy.Point,
        buffer: ByteBuffer,
        metrics: FloatArray,
    ): Int {
        buffer.clear()
        val silent = object : ProgressCallback {
            override fun onProgressUpdate(percentage: Int) = Unit
        }
        return IndicVisionNativeLib.computeFullFieldDirect(
            refBytes, defBytes, params.maskData,
            params.roiX, params.roiY, params.roiW, params.roiH,
            point.step, point.subset, point.strainWindow,
            true, true, false, false, false, params.use6x6,
            buffer, silent, metrics,
        )
    }
}
