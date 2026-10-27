package com.sempermechanics.semper.imaging.video

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * The clip times of the frames the retriever rung gets back (TD-194).
 * `getFrameAtTime` returns a frame near the time asked for and cannot say
 * which, so this reads the video track's sample times with a [MediaExtractor]
 * walk, as the key-frame lookup does, and answers with the one the retriever
 * lands on: the sample nearest the time asked for, or the sync sample nearest
 * it when the frame came from `OPTION_CLOSEST_SYNC`. A tie goes to the later
 * sample, as the platform's sample table breaks it.
 *
 * With no samples (the clip will not open in a [MediaExtractor], or has no
 * video track) every frame keeps the time asked for, as before.
 */
internal class RetrieverFrameClock(sampleTimesUs: List<Long>, syncTimesUs: List<Long>) {

    private val samplesUs = sampleTimesUs.distinct().sorted()
    private val syncUs = syncTimesUs.distinct().sorted()

    /** The time of the frame the retriever returned for [askedUs]; [askedUs] itself when the walk found none. */
    fun frameTimeUs(askedUs: Long, syncFrame: Boolean): Long =
        nearest(if (syncFrame) syncUs else samplesUs, askedUs) ?: askedUs

    /** One sample of the walk: its presentation time and whether it is a sync frame. */
    class Sample(val timeUs: Long, val isSync: Boolean)

    companion object {

        private const val US_PER_MS = 1000L

        /** No samples: every frame keeps the time asked for. */
        val NONE = RetrieverFrameClock(emptyList(), emptyList())

        /** [read] over [request]'s clip and segment. */
        fun read(context: Context, request: ExtractionRequest): RetrieverFrameClock = read(
            context,
            request.uri,
            startUs = request.startMs * US_PER_MS,
            endUs = request.endMs * US_PER_MS,
        )

        /**
         * The sample times of [uri]'s video track around [[startUs], [endUs]],
         * read by a [MediaExtractor] of its own; [NONE] when the clip will not
         * open or has no video track.
         */
        @Suppress("TooGenericExceptionCaught") // a clip the extractor refuses times nothing, as before
        fun read(context: Context, uri: Uri, startUs: Long, endUs: Long): RetrieverFrameClock {
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(context, uri, null)
                val track = HardwareVideoDecoder.findVideoTrack(extractor) ?: return NONE
                extractor.selectTrack(track)
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                walk(endUs) {
                    val timeUs = extractor.sampleTime
                    if (timeUs < 0L) {
                        null
                    } else {
                        val sync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        extractor.advance()
                        Sample(timeUs, sync)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Retriever frame clock: sample walk failed")
                NONE
            } finally {
                runCatching { extractor.release() }
                    .onFailure { Timber.w(it, "MediaExtractor release failed") }
            }
        }

        /**
         * The samples [next] yields in decode order, from the sync frame at or
         * before the segment's start through the first sync frame after [endUs]
         * and the frames that decode after it but show before it (a B-frame's),
         * so the nearest frame and the nearest sync frame on either side of any
         * time in the segment are both read. Stops at the end of the track.
         */
        fun walk(endUs: Long, next: () -> Sample?): RetrieverFrameClock {
            val samples = mutableListOf<Long>()
            val syncs = mutableListOf<Long>()
            var syncAfterEndUs: Long? = null
            var sample = next()
            while (sample != null && (syncAfterEndUs == null || sample.timeUs <= syncAfterEndUs)) {
                samples.add(sample.timeUs)
                if (sample.isSync) {
                    syncs.add(sample.timeUs)
                    if (syncAfterEndUs == null && sample.timeUs > endUs) syncAfterEndUs = sample.timeUs
                }
                sample = next()
            }
            return RetrieverFrameClock(samples, syncs)
        }

        /** The value of [sortedUs] nearest [timeUs], the later on a tie; null when it is empty. */
        fun nearest(sortedUs: List<Long>, timeUs: Long): Long? {
            if (sortedUs.isEmpty()) return null
            // The first value at or after timeUs, or the insertion point past the end.
            val found = sortedUs.binarySearch(timeUs)
            val after = if (found >= 0) found else -(found + 1)
            val before = after - 1
            return when {
                after >= sortedUs.size -> sortedUs[before]
                before < 0 -> sortedUs[after]
                timeUs - sortedUs[before] < sortedUs[after] - timeUs -> sortedUs[before]
                else -> sortedUs[after]
            }
        }
    }
}
