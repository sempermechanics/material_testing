package com.indicvision.semper.ui.analysis

import kotlin.math.roundToInt
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

    /**
     * The key frames (sync samples, start times in µs) inside [startMs, endMs],
     * as ms. A key frame is stored whole rather than predicted from its
     * neighbours, so it carries the least compression error of any frame —
     * the cleanest input for correlation. Their spacing is the encoder's, about
     * one a second on a phone; loads are matched to each frame's own time, so
     * uneven spacing costs nothing. More than [maxFrames] are thinned evenly,
     * keeping the first (the reference) and the last.
     */
    fun keyframeTimesMs(syncTimesUs: List<Long>, startMs: Long, endMs: Long, maxFrames: Int): List<Double> {
        val inSegment = syncTimesUs
            .filter { it >= startMs * US_PER_MS && it <= endMs * US_PER_MS }
            .distinct()
            .sorted()
            .map { it / US_PER_MS.toDouble() }
        return selectEvenly(inSegment, maxFrames)
    }

    /** [times] thinned to at most [count], evenly by index, first and last kept. */
    fun selectEvenly(times: List<Double>, count: Int): List<Double> = when {
        times.size <= count -> times
        count <= 1 -> times.take(1)
        else -> {
            val step = (times.size - 1).toDouble() / (count - 1)
            List(count) { times[(it * step).roundToInt()] }.distinct()
        }
    }

    private const val US_PER_MS = 1000L
}
