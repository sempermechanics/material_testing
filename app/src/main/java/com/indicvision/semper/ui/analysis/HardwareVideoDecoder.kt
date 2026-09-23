@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "ReturnCount", "NestedBlockDepth")

package com.indicvision.semper.ui.analysis

import android.content.Context
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.indicvision.semper.imaging.GrayPngEncoder
import timber.log.Timber

/**
 * Native hardware-accelerated video decoder using [MediaExtractor] and [MediaCodec].
 *
 * Configures [MediaCodec] with [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible]
 * and extracts the raw physical Y luminance plane directly from [Image.getPlanes] index 0,
 * avoiding any lossy RGB conversions, chroma interpolation, or demosaicing blur.
 */
internal class HardwareVideoDecoder private constructor(
    val extractor: MediaExtractor,
    private val codec: MediaCodec,
    val trackIndex: Int,
    val format: MediaFormat,
    val rotationDegrees: Int,
) : AutoCloseable {

    companion object {
        private const val TIMEOUT_US = 10_000L

        // Enough to decode forward across a long GOP (e.g. 2 s at 60 fps) after a sync seek.
        private const val MAX_DRAIN_ATTEMPTS = 600

        /**
         * Creates and starts a [HardwareVideoDecoder] for [uri], or returns null if hardware
         * decoding cannot be initialized.
         */
        fun create(context: Context, uri: Uri, rotationDegrees: Int): HardwareVideoDecoder? {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(context, uri, null)
                val trackIndex = findVideoTrack(extractor) ?: return null
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )

                val decoder = MediaCodec.createDecoderByType(mime)
                codec = decoder
                decoder.configure(format, null, null, 0)
                decoder.start()
                extractor.selectTrack(trackIndex)

                return HardwareVideoDecoder(
                    extractor = extractor,
                    codec = decoder,
                    trackIndex = trackIndex,
                    format = format,
                    rotationDegrees = rotationDegrees,
                )
            } catch (e: Exception) {
                Timber.w(e, "HardwareVideoDecoder initialization failed")
                runCatching { codec?.release() }
                runCatching { extractor.release() }
                return null
            }
        }

        private fun findVideoTrack(extractor: MediaExtractor): Int? {
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    return i
                }
            }
            return null
        }
    }

    /**
     * Decodes the first frame presented at or after [timeUs] (seeking to the previous sync
     * frame and decoding forward) and extracts the raw Y plane.
     * Returns null if decoding fails or output image cannot be acquired.
     */
    fun decodeFrameAt(timeUs: Long): GrayPngEncoder.Luma? {
        try {
            extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec.flush()

            val bufferInfo = MediaCodec.BufferInfo()
            var attempts = 0
            var sawEos = false

            while (attempts < MAX_DRAIN_ATTEMPTS) {
                attempts++
                if (!sawEos) {
                    sawEos = feedInput()
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    // The seek lands on the previous sync frame; decode forward past it so
                    // uniform-interval samples are the requested frame, not a repeated I-frame.
                    val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val reached = bufferInfo.presentationTimeUs >= timeUs || isEos
                    val luma = if (reached) extractLumaFromOutputBuffer(outIndex) else null
                    codec.releaseOutputBuffer(outIndex, false)
                    if (luma != null) return luma
                    if (isEos) break
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error during hardware frame decode at %d us", timeUs)
        }
        return null
    }

    private fun feedInput(): Boolean {
        val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
        if (inIndex < 0) return false

        val inBuf = codec.getInputBuffer(inIndex) ?: return false
        val sampleSize = extractor.readSampleData(inBuf, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        val sampleTime = extractor.sampleTime
        val flags = extractor.sampleFlags
        codec.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, flags)
        extractor.advance()
        return false
    }

    private fun extractLumaFromOutputBuffer(outIndex: Int): GrayPngEncoder.Luma? {
        val image = codec.getOutputImage(outIndex) ?: return null
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val crop = image.cropRect
            val width = if (crop.width() > 0) crop.width() else image.width
            val height = if (crop.height() > 0) crop.height() else image.height

            val startOffset = crop.top * rowStride + crop.left * pixelStride
            val dup = buffer.duplicate()
            val safeOffset = startOffset.coerceIn(0, dup.capacity())
            dup.position(safeOffset)

            val bytes = ByteArray(dup.remaining())
            dup.get(bytes)

            GrayPngEncoder.Luma(
                bytes = bytes,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                rotationDegrees = rotationDegrees,
            )
        } finally {
            image.close()
        }
    }

    override fun close() {
        runCatching {
            codec.stop()
            codec.release()
        }.onFailure { Timber.w(it, "MediaCodec release failed") }
        runCatching {
            extractor.release()
        }.onFailure { Timber.w(it, "MediaExtractor release failed") }
    }
}
