package com.indicvision.semper.ui.analysis

import kotlin.math.roundToLong

/**
 * Which instants of a video get sampled. Pure, so the sampling sheet's
 * estimate and both extraction paths (retriever and AVI) count the same
 * frames.
 *
 * A clip of duration D holds frames that *start* at 0 … D − 1/fps; nothing
 * starts at D itself. A segment dragged to the end of the clip therefore ends,
 * for sampling, at the last frame's start. Sampling at D instead lands past
 * every frame: the retriever hands back the last frame a second time and the
 * AVI path drops it as a repeat, so the sheet promised one frame more than
 * either delivered.
 */
object VideoSampling {

    private const val MS_PER_S = 1000.0

    /**
     * Start of the clip's last frame in ms — the latest instant a sample can
     * land on a frame of its own. Without a known rate, one millisecond
     * before the end is the most that can be said.
     */
    fun lastFrameStartMs(durationMs: Long, fps: Double, fpsKnown: Boolean): Long {
        val frameMs = if (fpsKnown && fps > 0.0) (MS_PER_S / fps).roundToLong() else 1L
        return (durationMs - frameMs).coerceAtLeast(0L)
    }

    /** Sample instants in ms from [startMs] to [endMs] at [fpsExtract], at most [maxFrames]. */
    fun sampleTimesMs(startMs: Long, endMs: Long, fpsExtract: Double, maxFrames: Int): List<Double> {
        val stepMs = MS_PER_S / fpsExtract
        val span = (endMs - startMs).coerceAtLeast(0L)
        val count = ((span / stepMs).toInt() + 1).coerceIn(1, maxFrames)
        return List(count) { startMs + it * stepMs }
    }
}
