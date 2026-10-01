package com.indicvision.semper.ui.analysis.run

import androidx.annotation.VisibleForTesting
import com.indicvision.semper.ProgressCallback
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.newMetrics
import java.nio.ByteBuffer

/**
 * One full-field solve for the single-shot callers: the VSG sweep's per-node
 * solve and the noise-floor probe. Both cleared the output buffer, passed a
 * silent progress callback and handed back the engine's point count, around
 * the same JNI call; this is that wrapper.
 *
 * Not for the batch run: `DicBatchRunner` keeps its
 * [SemperNativeLib.computeFullFieldDirect] call inline in its one loop (it
 * reports progress, and its buffer handling is pinned there).
 *
 * The reference must already be initialised ([SemperNativeLib.initializeReference]).
 * Runs on the caller's thread; the callers already run on the native one.
 */
internal object SemperEngine {

    /** The JNI signature, so a JVM test can stand in for the native library. */
    @Suppress("LongParameterList") // mirrors the JNI entry point
    fun interface FullFieldSolver {
        fun solve(
            refBytes: ByteArray,
            defBytes: ByteArray,
            maskData: ByteArray,
            roiX: Int,
            roiY: Int,
            roiW: Int,
            roiH: Int,
            step: Int,
            subset: Int,
            strainWindow: Int,
            use6x6Interpolator: Boolean,
            outputBuffer: ByteBuffer,
            callback: ProgressCallback,
            outMetrics: FloatArray,
        ): Int
    }

    /** A progress callback that ignores progress. */
    val SILENT: ProgressCallback = object : ProgressCallback {
        override fun onProgressUpdate(percentage: Int) = Unit
    }

    /** No mask: every pixel of the region is solved. */
    val NO_MASK = ByteArray(0)

    /**
     * The engine. A lambda, not a reference to [SemperNativeLib], so loading
     * this object does not load the native library.
     */
    @VisibleForTesting
    @Volatile
    var solver = FullFieldSolver { ref, def, mask, x, y, w, h, step, subset, window, use6x6, out, callback, metrics ->
        SemperNativeLib.computeFullFieldDirect(
            ref, def, mask,
            x, y, w, h,
            step, subset, window,
            use6x6,
            out, callback, metrics,
        )
    }

    /**
     * Solves region ([roiX], [roiY], [roiW], [roiH]) of [defBytes] against
     * [refBytes] into [buffer], which is cleared first. [metrics] receives the
     * telemetry slots ([EngineStats.fromArray]); the sweep passes
     * [EngineStats.newMetrics], the probe a plain zeroed array.
     *
     * @return the engine's result: points written, or a negative engine code.
     */
    @Suppress("LongParameterList") // the solve's own inputs, as the JNI takes them
    fun solve(
        refBytes: ByteArray,
        defBytes: ByteArray,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        step: Int,
        subset: Int,
        strainWindow: Int,
        buffer: ByteBuffer,
        metrics: FloatArray = EngineStats.newMetrics(),
        maskData: ByteArray = NO_MASK,
        use6x6: Boolean = false,
        progress: ProgressCallback = SILENT,
    ): Int {
        buffer.clear()
        return solver.solve(
            refBytes, defBytes, maskData,
            roiX, roiY, roiW, roiH,
            step, subset, strainWindow,
            use6x6,
            buffer, progress, metrics,
        )
    }
}
