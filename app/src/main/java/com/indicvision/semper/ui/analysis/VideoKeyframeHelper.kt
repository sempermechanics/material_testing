@file:Suppress(
    "MagicNumber",
    "LongParameterList",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package com.indicvision.semper.ui.analysis

import android.media.MediaExtractor
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure helper functions for video timestamp calculation, keyframe extraction planning,
 * and time clock formatting.
 */
internal object VideoKeyframeHelper {

    private const val MICROS_PER_MILLI = 1000L
    private const val MILLIS_PER_SECOND = 1000.0
    private const val SECONDS_PER_MINUTE = 60

    data class ExtractionPlan(
        val timestampsUs: List<Long>,
        val isKeyframePlan: Boolean,
    )

    /**
     * Formats milliseconds into a clock display string (M:SS).
     */
    fun formatClock(ms: Long): String {
        val totalSec = (ms / MICROS_PER_MILLI).toInt()
        val minutes = totalSec / SECONDS_PER_MINUTE
        val seconds = totalSec % SECONDS_PER_MINUTE
        return String.format(Locale.US, "%d:%02d", minutes, seconds)
    }

    /**
     * Discovers all sync keyframes (I-frames) within [[startUs], [endUs]].
     */
    fun findKeyframeTimestampsUs(
        extractor: MediaExtractor,
        trackIndex: Int,
        startUs: Long,
        endUs: Long,
    ): List<Long> {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val keyframes = mutableListOf<Long>()
        while (true) {
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0L || sampleTime > endUs) break
            val isSync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            if (isSync && sampleTime >= startUs) {
                keyframes.add(sampleTime)
            }
            if (!extractor.advance()) break
        }
        return keyframes.distinct()
    }

    /**
     * Builds an extraction plan. Prefers true keyframes when >= 2 are present,
     * otherwise falls back to uniform sampling at [fpsExtract].
     */
    fun resolveExtractionPlan(
        keyframeTimestampsUs: List<Long>,
        startMs: Long,
        endMs: Long,
        fpsExtract: Double,
        maxFrames: Int,
        forceUniform: Boolean = false,
    ): ExtractionPlan {
        if (!forceUniform && keyframeTimestampsUs.size >= 2) {
            val selected = if (keyframeTimestampsUs.size > maxFrames) {
                selectEvenly(keyframeTimestampsUs, maxFrames)
            } else {
                keyframeTimestampsUs
            }
            return ExtractionPlan(selected, isKeyframePlan = true)
        }
        val uniform = uniformTimestampsUs(startMs, endMs, fpsExtract, maxFrames)
        return ExtractionPlan(uniform, isKeyframePlan = false)
    }

    /**
     * Subsamples [timestamps] evenly down to [targetCount], preserving the first and last elements.
     */
    fun selectEvenly(timestamps: List<Long>, targetCount: Int): List<Long> {
        if (timestamps.size <= targetCount) return timestamps
        if (targetCount <= 1) return listOf(timestamps.first())
        val step = (timestamps.size - 1).toDouble() / (targetCount - 1).toDouble()
        val result = mutableListOf<Long>()
        for (i in 0 until targetCount) {
            val idx = (i * step).roundToInt().coerceIn(0, timestamps.size - 1)
            val item = timestamps[idx]
            if (result.isEmpty() || result.last() != item) {
                result.add(item)
            }
        }
        return result
    }

    /**
     * Generates uniform sampling timestamps between [startMs] and [endMs].
     */
    fun uniformTimestampsUs(
        startMs: Long,
        endMs: Long,
        fpsExtract: Double,
        maxFrames: Int,
    ): List<Long> {
        val safeFps = fpsExtract.coerceAtLeast(0.1)
        val stepMs = MILLIS_PER_SECOND / safeFps
        val spanMs = (endMs - startMs).coerceAtLeast(0L)
        val count = ((spanMs / stepMs).toInt() + 1).coerceIn(2, maxFrames)
        val timesUs = mutableListOf<Long>()
        for (i in 0 until count) {
            val timeMs = startMs + (i * stepMs).toLong()
            if (timeMs > endMs && i >= 2) break
            timesUs.add(timeMs * MICROS_PER_MILLI)
        }
        return timesUs
    }
}
