// Frame extraction: each rung walks the video in one cohesive decode loop (seek,
// decode, write, progress) with literal timing/quality constants and a broad
// per-frame guard, so those rules are suppressed for this whole file. A rung
// that cannot read the file returns where it finds that out rather than
// carrying a null result down the rest of the loop.
@file:Suppress(
    "MagicNumber",
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
    "ReturnCount",
)

@file:SuppressLint("InlinedApi")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale
import com.indicvision.semper.R
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.ImageEncode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

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
 * Frames are extracted as lossless 8-bit grayscale PNGs straight from the
 * decoded Y (luma) plane ([AviVideoDecoder], [HardwareVideoDecoder]), with a
 * [MediaMetadataRetriever] fallback that writes lossless RGB PNGs.
 */
object VideoFrameExtractor {

    private val PREVIEW_MAX_EDGE = BitmapDecode.PREVIEW_MAX_EDGE

    fun formatClock(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    fun readMeta(context: Context, uri: Uri): VideoMeta {
        // An AVI, which no platform API can open, answers for itself; anything
        // else falls through to the retriever below.
        AviVideoDecoder.create(context, uri)?.use { avi ->
            return if (avi.canDecode) avi.meta else avi.meta.copy(unsupportedCodec = avi.fourcc)
        }
        var meta = VideoMeta(0L, 30.0, false, 0, 0, 0)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun m(key: Int) = retriever.extractMetadata(key)
            val durationMs = m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w
                w = h
                h = t
            }
            val frameCountMeta = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            var fps = 30.0
            var fpsKnown = false
            if (frameCountMeta != null && frameCountMeta > 0 && durationMs > 0) {
                fps = frameCountMeta / (durationMs / 1000.0)
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

    data class ExtractionResult(
        val refPng: ByteArray,
        val refWidth: Int,
        val refHeight: Int,
        val refName: String,
        val refPreview: Bitmap?,
        val batch: ImportedBatch,
        /** Time of each deformed frame after the reference, aligned with [batch]. */
        val defTimesMs: List<Long> = emptyList(),
    )

    /**
     * Extract frames at [fpsExtract] over [startMs, endMs]. Call from IO.
     * Invokes [onProgress] from the background thread. Returns
     * [ExtractionResult] or null if insufficient frames.
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        fpsExtract: Double,
        startMs: Long,
        endMs: Long,
        maxFrames: Int,
        cacheDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? = extract(
        context = context,
        uri = uri,
        times = VideoSampling.sampleTimesMs(startMs, endMs, fpsExtract, maxFrames),
        cacheDir = cacheDir,
        onProgress = onProgress,
    )

    /**
     * Extract the frames at [times] (ms, ascending): the first becomes the
     * reference and every deformed frame is timed from it. A frame-rate plan
     * or the key frames ([VideoSampling]). Call from IO.
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        times: List<Double>,
        cacheDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        if (times.isEmpty()) return null
        val startMs = times.first().toLong()
        val stagingDir = FrameImportHelper.createStagingDir(cacheDir)
        var completed = false
        var refPreview: Bitmap? = null
        try {
            // Three rungs, tried in order: the AVI demuxer, which is the only
            // thing that can open that container; the hardware decoder; and the
            // retriever, which crashes on nothing.
            val result = extractWithAvi(
                context = context,
                uri = uri,
                times = times,
                startMs = startMs,
                cacheDir = cacheDir,
                stagingDir = stagingDir,
                onProgress = onProgress,
            ) ?: extractWithHardwareDecoder(
                context = context,
                uri = uri,
                rotationDegrees = readMeta(context, uri).rotationDegrees,
                times = times,
                startMs = startMs,
                cacheDir = cacheDir,
                stagingDir = stagingDir,
                onProgress = onProgress,
            ) ?: extractWithRetriever(
                context = context,
                uri = uri,
                times = times,
                startMs = startMs,
                cacheDir = cacheDir,
                stagingDir = stagingDir,
                onProgress = onProgress,
            ) ?: return null

            refPreview = result.refPreview
            completed = true
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
        uri: Uri,
        rotationDegrees: Int,
        times: List<Double>,
        startMs: Long,
        cacheDir: File,
        stagingDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        if (times.size < 2) return null
        val decoder = HardwareVideoDecoder.create(context, uri, rotationDegrees) ?: return null
        return decoder.use { dec ->
            try {
                VideoFrameBatchWriter.write(
                    context = context,
                    count = times.size,
                    // Rounded: a key frame's time in ms, truncated, lands a hair before
                    // it, and the decoder would then decode the GOP before it first.
                    lumaAt = { i -> dec.decodeFrameAt((times[i] * 1000).roundToLong()) },
                    offsetMsAt = { i -> (times[i] - times[0]).roundToLong() },
                    startMs = startMs,
                    cacheDir = cacheDir,
                    stagingDir = stagingDir,
                    onProgress = onProgress,
                )
            } catch (e: Exception) {
                Timber.w(e, "Hardware decoding loop failed")
                null
            }
        }
    }

    /**
     * Frames of an AVI. `MediaExtractor` cannot open the container at all, so
     * [AviVideoDecoder] demuxes it and decodes each frame by its FourCC.
     * Null when [uri] is not an AVI, or holds a codec this device cannot decode.
     */
    private suspend fun extractWithAvi(
        context: Context,
        uri: Uri,
        times: List<Double>,
        startMs: Long,
        cacheDir: File,
        stagingDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        val decoder = AviVideoDecoder.create(context, uri) ?: return null
        return decoder.use { avi ->
            // An AVI has no seekable timeline of its own: sampling times map
            // onto frame indices, and repeats collapse so a rate above the
            // stream's own cannot ask for the same frame twice.
            val picked = LinkedHashMap<Int, Long>()
            for (timeMs in times) {
                val index = avi.video.frameIndexAt((timeMs * 1000).roundToLong())
                if (!picked.containsKey(index)) picked[index] = (timeMs - times[0]).roundToLong()
            }
            val indices = picked.keys.toList()
            val offsetsMs = picked.values.toList()
            if (!avi.canDecode || indices.size < 2) {
                null
            } else {
                VideoFrameBatchWriter.write(
                    context = context,
                    count = indices.size,
                    lumaAt = { i -> avi.decodeFrame(indices[i]) },
                    offsetMsAt = { i -> offsetsMs[i] },
                    startMs = startMs,
                    cacheDir = cacheDir,
                    stagingDir = stagingDir,
                    onProgress = onProgress,
                )
            }
        }
    }

    private suspend fun extractWithRetriever(
        context: Context,
        uri: Uri,
        times: List<Double>,
        startMs: Long,
        cacheDir: File,
        stagingDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        Timber.i("Falling back to MediaMetadataRetriever for video frame extraction")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val count = times.size

            val defPaths = mutableListOf<String>()
            val defTimesMs = mutableMapOf<String, Long>()
            var refPng: ByteArray? = null
            var refWidth = 0
            var refHeight = 0
            var refPreview: Bitmap? = null

            for ((i, timeMs) in times.withIndex()) {
                currentCoroutineContext().ensureActive()
                val frame = getFrameHybrid(retriever, (timeMs * 1000).roundToLong()) ?: continue
                try {
                    currentCoroutineContext().ensureActive()
                    if (i == 0) {
                        refWidth = frame.width
                        refHeight = frame.height
                        refPng = compressPngToBytes(frame)
                        refPreview = scaledPreview(frame)
                    } else {
                        val f = File(stagingDir, String.format(Locale.US, "%04d_frame.png", i))
                        FileOutputStream(f).use { out ->
                            frame.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                        }
                        defPaths.add(f.absolutePath)
                        defTimesMs[f.absolutePath] = (timeMs - times[0]).roundToLong()
                    }
                } finally {
                    frame.recycle()
                }

                val status = context.getString(R.string.video_extracting_progress_fmt, i + 1, count)
                onProgress((i + 1) * 100 / count, status)
            }

            val pngBytes = refPng ?: return null
            if (defPaths.isEmpty()) return null

            return VideoFrameBatchWriter.assemble(
                cacheDir = cacheDir,
                stagingDir = stagingDir,
                refPng = pngBytes,
                refWidth = refWidth,
                refHeight = refHeight,
                refPreview = refPreview,
                startMs = startMs,
                defPaths = defPaths,
                defTimesMs = defTimesMs,
            )
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
            frame.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
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
