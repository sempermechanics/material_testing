@file:Suppress("TooGenericExceptionCaught")

package com.indicvision.semper.ui.analysis

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import timber.log.Timber
import kotlin.math.roundToLong

/**
 * Where a video's key frames (sync samples) start, in µs. The sampling sheet
 * offers them as the sample times ([VideoSampling.keyframeTimesMs]); the
 * extraction paths then decode each one like any other sample, and a decode
 * at a key frame's own time is that key frame.
 *
 * Empty when the file marks none, or cannot be read — the sheet then offers
 * only the frame rate. Call from an IO dispatcher.
 */
internal object VideoKeyframes {

    private const val US_PER_S = 1_000_000.0

    fun syncTimesUs(context: Context, uri: Uri): List<Long> =
        AviVideoDecoder.create(context, uri)?.use { avi -> aviSyncTimesUs(avi) }
            ?: extractorSyncTimesUs(context, uri)

    /**
     * An AVI's key frames come from its `idx1` flags. Without an index every
     * frame counts as one, which says nothing, and without a rate there are
     * no times to give.
     */
    private fun aviSyncTimesUs(avi: AviVideoDecoder): List<Long> {
        val video = avi.video
        if (!video.hasIndex || video.fps <= 0.0) return emptyList()
        return video.frames.indices
            .filter { video.frames[it].keyframe }
            .map { (it * US_PER_S / video.fps).roundToLong() }
    }

    /**
     * Hops from key frame to key frame with [MediaExtractor.SEEK_TO_NEXT_SYNC],
     * so a long clip costs one seek per key frame, not a walk over every sample.
     */
    private fun extractorSyncTimesUs(context: Context, uri: Uri): List<Long> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")
            } ?: return emptyList()
            extractor.selectTrack(track)
            val times = mutableListOf<Long>()
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_NEXT_SYNC)
            while (extractor.sampleTime >= 0L) {
                val t = extractor.sampleTime
                if (times.isNotEmpty() && t <= times.last()) break
                times.add(t)
                extractor.seekTo(t + 1L, MediaExtractor.SEEK_TO_NEXT_SYNC)
            }
            times
        } catch (e: Exception) {
            Timber.w(e, "Key frame scan failed")
            emptyList()
        } finally {
            runCatching { extractor.release() }
        }
    }
}
