@file:Suppress("NestedBlockDepth", "TooGenericExceptionCaught")

@file:SuppressLint("InlinedApi")

package com.sempermechanics.semper.imaging.video

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.imaging.ImageEncoder
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.frames.ImportedBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * What to extract from [uri]: the frames at [timesMs] (ms, ascending). The
 * first becomes the reference and every deformed frame is timed from it. A
 * frame-rate plan or the clip's key frames ([VideoSampling]).
 *
 * @param rotationDegrees the clip's rotation from [VideoFrameExtractor.readMeta],
 *   when the caller already has it; null reads the metadata again.
 */
data class ExtractionRequest(
    val uri: Uri,
    val timesMs: List<Double>,
    val rotationDegrees: Int? = null,
) {
    /** Where the sampled segment starts: the reference's time. It names the reference. */
    val startMs: Long get() = timesMs.firstOrNull()?.toLong() ?: 0L

    /** Sample [index]'s time after the reference, ms. */
    fun offsetMsAt(index: Int): Long = (timesMs[index] - timesMs[0]).roundToLong()

    companion object {
        /** Evenly at [fpsExtract] over [startMs] to [endMs], at most [maxFrames] ([VideoSampling.sampleTimesMs]). */
        @Suppress("LongParameterList") // a fixed-rate sampling's whole description, all named at the call
        fun uniform(
            uri: Uri,
            fpsExtract: Double,
            startMs: Long,
            endMs: Long,
            maxFrames: Int,
            rotationDegrees: Int? = null,
        ) = ExtractionRequest(uri, VideoSampling.sampleTimesMs(startMs, endMs, fpsExtract, maxFrames), rotationDegrees)
    }
}

/** A video's first sampled frame as the reference: lossless [png] bytes, its pixel [size] and a [preview]. */
class ReferenceFrame(
    val png: ByteArray,
    val size: ImageSize,
    val preview: Bitmap?,
)

data class VideoMeta(
    val durationMs: Long,
    val fps: Double,
    val fpsKnown: Boolean,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    /** FourCC of a stream that opened but has no decoder here, for the error. */
    val unsupportedCodec: String? = null,
)

/**
 * Reads video metadata and extracts a reference + deformed-frame batch into
 * cacheDir/temp_deformed. Call [extract] from an IO dispatcher.
 *
 * Frames are extracted as lossless 8-bit grayscale PNGs directly from the raw
 * physical Y luma plane via native hardware decoding ([HardwareVideoDecoder]),
 * with a zero-crash fallback to [MediaMetadataRetriever].
 */
object VideoFrameExtractor {

    private val PREVIEW_MAX_EDGE = BitmapDecoder.PREVIEW_MAX_EDGE

    /** The frame rate assumed when the clip does not report its frame count. */
    private const val ASSUMED_FPS = 30.0
    private const val MS_PER_SECOND = 1000.0
    private const val US_PER_MS = 1000.0
    private const val SECONDS_PER_MINUTE = 60

    /** Rotations that swap the clip's width and height. */
    private const val QUARTER_TURN = 90
    private const val THREE_QUARTER_TURN = 270

    fun formatClock(ms: Long): String {
        val totalSeconds = (ms / MS_PER_SECOND).toInt()
        return String.format(Locale.US, "%d:%02d", totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
    }

    fun readMeta(context: Context, uri: Uri): VideoMeta {
        // An AVI, which no platform API can open, answers for itself; anything
        // else falls through to the retriever below.
        AviVideoDecoder.create(context, uri)?.use { avi ->
            return if (avi.canDecode) avi.meta else avi.meta.copy(unsupportedCodec = avi.fourcc)
        }
        var meta = VideoMeta(0L, ASSUMED_FPS, false, 0, 0, 0)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun m(key: Int) = retriever.extractMetadata(key)
            val durationMs = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == QUARTER_TURN || rot == THREE_QUARTER_TURN) {
                val t = w
                w = h
                h = t
            }
            val frameCountMeta = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            var fps = ASSUMED_FPS
            var fpsKnown = false
            if (frameCountMeta != null && frameCountMeta > 0 && durationMs > 0) {
                fps = frameCountMeta / (durationMs / MS_PER_SECOND)
                fpsKnown = true
            }
            meta = VideoMeta(durationMs, fps, fpsKnown, w, h, rot)
        } catch (e: Exception) {
            Timber.e(e, "Video metadata read failed")
        } finally {
            runCatching { retriever.release() }
                .onFailure { Timber.w(it, "MediaMetadataRetriever.release failed") }
        }
        return meta
    }

    /** Frame 0 of the segment as the reference, and the rest as the committed deformed [batch]. */
    data class ExtractionResult(
        val reference: ReferenceFrame,
        val refName: String,
        val batch: ImportedBatch,
    )

    /**
     * Extract frames. Call from IO. Invokes [onProgress] from the background thread.
     * Returns [ExtractionResult] or null if insufficient frames.
     *
     * Cancelling the caller's job cancels the extraction: it throws
     * [CancellationException] rather than falling through to the next rung,
     * whose null would read as "not enough frames".
     */
    suspend fun extract(
        context: Context,
        request: ExtractionRequest,
        cacheDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        if (request.timesMs.size < 2) return null
        val stagingDir = FrameImportHelper.createStagingDir(cacheDir)
        val sink = FrameSink(context, cacheDir, stagingDir, request.startMs, onProgress)
        var completed = false
        var refPreview: Bitmap? = null
        try {
            // Three rungs, tried in order: the AVI demuxer, which is the only
            // thing that can open that container; the hardware decoder; and the
            // retriever, which crashes on nothing.
            val result = extractWithAvi(context, request, sink)
                ?: extractWithHardwareDecoder(
                    context,
                    request,
                    rotationDegrees = request.rotationDegrees ?: readMeta(context, request.uri).rotationDegrees,
                    sink,
                )
                ?: extractWithRetriever(context, request, sink)

            refPreview = result?.reference?.preview
            completed = result != null
            return result
        } finally {
            stagingDir.deleteRecursively()
            if (!completed) refPreview?.recycle()
        }
    }

    /**
     * Frames of an MP4/3GP/WebM through [HardwareVideoDecoder]: each sample
     * decodes forward from the sync frame before it, so it is the frame asked
     * for. A sync-frame seek would hand back the same I-frame for every sample
     * in a GOP — identical frames, and zero displacement from DIC.
     */
    private suspend fun extractWithHardwareDecoder(
        context: Context,
        request: ExtractionRequest,
        rotationDegrees: Int,
        sink: FrameSink,
    ): ExtractionResult? {
        val decoder = HardwareVideoDecoder.create(context, request.uri, rotationDegrees) ?: return null
        return decoder.use { dec ->
            try {
                val times = request.timesMs
                // Rounded: a key frame's time in ms, truncated, lands a hair before
                // it, and the decoder would then decode the GOP before it first.
                sink.write(count = times.size, offsetMsAt = request::offsetMsAt) { i ->
                    dec.decodeFrameAt((times[i] * US_PER_MS).roundToLong())
                }
            } catch (e: CancellationException) {
                // The user's Cancel: not a decoder failure for the next rung to retry.
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Hardware decoding loop failed")
                null
            }
        }
    }

    /**
     * Frames of an AVI. `MediaExtractor` cannot open the container at all, so
     * [AviVideoDecoder] demuxes it and decodes each frame by its FourCC.
     * Null when the request's clip is not an AVI, or holds a codec this device cannot decode.
     */
    private suspend fun extractWithAvi(
        context: Context,
        request: ExtractionRequest,
        sink: FrameSink,
    ): ExtractionResult? {
        val decoder = AviVideoDecoder.create(context, request.uri) ?: return null
        return decoder.use { avi ->
            // An AVI has no seekable timeline of its own: sampling times map
            // onto frame indices, and repeats collapse so a rate above the
            // stream's own cannot ask for the same frame twice. Each kept
            // frame keeps the time of the first sample that asked for it.
            val picked = LinkedHashMap<Int, Long>()
            request.timesMs.forEachIndexed { i, timeMs ->
                val index = avi.video.frameIndexAt((timeMs * US_PER_MS).roundToLong())
                if (!picked.containsKey(index)) picked[index] = request.offsetMsAt(i)
            }
            val indices = picked.keys.toList()
            val offsetsMs = picked.values.toList()
            if (!avi.canDecode || indices.size < 2) {
                null
            } else {
                sink.write(count = indices.size, offsetMsAt = offsetsMs::get) { i -> avi.decodeFrame(indices[i]) }
            }
        }
    }

    private suspend fun extractWithRetriever(
        context: Context,
        request: ExtractionRequest,
        sink: FrameSink,
    ): ExtractionResult? {
        Timber.i("Falling back to MediaMetadataRetriever for video frame extraction")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, request.uri)
            val times = request.timesMs
            val count = times.size

            val defPaths = mutableListOf<String>()
            val defTimesMs = mutableMapOf<String, Long>()
            var reference: ReferenceFrame? = null

            for ((i, timeMs) in times.withIndex()) {
                currentCoroutineContext().ensureActive()
                val frame = getFrameHybrid(retriever, (timeMs * US_PER_MS).roundToLong()) ?: continue
                try {
                    currentCoroutineContext().ensureActive()
                    if (i == 0) {
                        reference = ReferenceFrame(
                            png = compressPngToBytes(frame),
                            size = ImageSize(frame.width, frame.height),
                            preview = scaledPreview(frame),
                        )
                    } else {
                        val f = sink.deformedFile(i)
                        FileOutputStream(f).use { out ->
                            frame.compress(Bitmap.CompressFormat.PNG, ImageEncoder.PNG_QUALITY_MAX, out)
                        }
                        defPaths.add(f.absolutePath)
                        defTimesMs[f.absolutePath] = request.offsetMsAt(i)
                    }
                } finally {
                    frame.recycle()
                }
                sink.progress(i, count)
            }
            return sink.finish(reference, defPaths, defTimesMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Retriever extraction fallback failed")
            return null
        } finally {
            runCatching { retriever.release() }
                .onFailure { Timber.w(it, "MediaMetadataRetriever.release failed") }
        }
    }

    /**
     * The frame at [timeUs], decoding forward from the sync frame before it;
     * the sync frame itself only when that fails. Sync-first would return one
     * I-frame for every sample in a GOP.
     */
    private fun getFrameHybrid(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    private fun compressPngToBytes(frame: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            frame.compress(Bitmap.CompressFormat.PNG, ImageEncoder.PNG_QUALITY_MAX, out)
            out.toByteArray()
        }

    private fun scaledPreview(frame: Bitmap): Bitmap {
        val longEdge = max(frame.width, frame.height)
        if (longEdge <= PREVIEW_MAX_EDGE) {
            return frame.copy(frame.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val scale = PREVIEW_MAX_EDGE.toFloat() / longEdge
        return frame.scale(
            (frame.width * scale).toInt().coerceAtLeast(1),
            (frame.height * scale).toInt().coerceAtLeast(1),
        )
    }
}
