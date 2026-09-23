// Frame extraction: extract() walks the video in one cohesive decode loop (seek,
// decode, write, progress) with literal timing/quality constants and a broad
// per-frame guard, so those rules are suppressed for this whole file. A rung
// that cannot read the file returns where it finds that out rather than
// carrying a null result down the rest of the loop.
@file:Suppress(
    "MagicNumber",
    "LongMethod",
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
import com.indicvision.semper.imaging.AviReader
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.GrayPngEncoder
import com.indicvision.semper.imaging.ImageEncode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max

data class VideoMeta(
    val durationMs: Long,
    val fps: Double,
    val fpsKnown: Boolean,
    val width: Int,
    val height: Int,
    /** FourCC of a stream that opened but has no decoder here, for the error. */
    val unsupportedCodec: String? = null,
)

/**
 * Reads video metadata and extracts a reference + deformed-frame batch into
 * cacheDir/temp_deformed. Call [extract] from an IO dispatcher.
 *
 * Frames are always written as lossless PNG — never JPEG/WebP.
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
        var meta = VideoMeta(0L, 30.0, false, 0, 0)
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
            meta = VideoMeta(durationMs, fps, fpsKnown, w, h)
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
     * Extract frames. Call from IO. Invoke [onProgress] which may be called from
     * a background thread. Returns [ExtractionResult] or null if insufficient frames.
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
    ): ExtractionResult? {
        val retriever = MediaMetadataRetriever()
        val stagingDir = FrameImportHelper.createStagingDir(cacheDir)
        var completed = false
        var refPreview: Bitmap? = null
        try {
            // AVI first: it is the one container MediaMetadataRetriever cannot
            // open at all, so nothing below would get a frame out of it.
            val aviResult = extractWithAvi(
                context = context,
                uri = uri,
                fpsExtract = fpsExtract,
                startMs = startMs,
                endMs = endMs,
                maxFrames = maxFrames,
                cacheDir = cacheDir,
                stagingDir = stagingDir,
                onProgress = onProgress,
            )
            if (aviResult != null) {
                refPreview = aviResult.refPreview
                completed = true
                return aviResult
            }

            retriever.setDataSource(context, uri)

            val times = VideoSampling.sampleTimesMs(startMs, endMs, fpsExtract, maxFrames)
            val count = times.size

            val defPaths = mutableListOf<String>()
            val defTimesMs = mutableMapOf<String, Long>()
            var refPng: ByteArray? = null
            var refWidth = 0
            var refHeight = 0

            for ((i, timeMs) in times.withIndex()) {
                currentCoroutineContext().ensureActive()
                val frame = getFrameHybrid(retriever, (timeMs * 1000).toLong()) ?: continue
                try {
                    currentCoroutineContext().ensureActive()
                    if (i == 0) {
                        refWidth = frame.width
                        refHeight = frame.height
                        refPng = compressPngToBytes(frame)
                        currentCoroutineContext().ensureActive()
                        refPreview = scaledPreview(frame)
                    } else {
                        val f = File(stagingDir, String.format(Locale.US, "%04d_frame.png", i))
                        FileOutputStream(f).use { out ->
                            frame.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                        }
                        currentCoroutineContext().ensureActive()
                        defPaths.add(f.absolutePath)
                        defTimesMs[f.absolutePath] = (timeMs - startMs).toLong()
                    }
                } finally {
                    frame.recycle()
                }

                onProgress((i + 1) * 100 / count, "Extracting frame ${i + 1} of $count")
            }

            val pngBytes = refPng
            if (pngBytes == null || defPaths.isEmpty()) return null

            val result = assemble(
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
            completed = true
            return result
        } finally {
            runCatching { retriever.release() }
                .onFailure { Timber.w(it, "MediaMetadataRetriever.release failed") }
            stagingDir.deleteRecursively()
            if (!completed) refPreview?.recycle()
        }
    }

    /**
     * Frames of an AVI. `MediaMetadataRetriever` cannot open the container at
     * all, so [AviVideoDecoder] demuxes it and decodes each frame by its FourCC.
     * Null when [uri] is not an AVI, or holds a codec this device cannot decode.
     */
    private suspend fun extractWithAvi(
        context: Context,
        uri: Uri,
        fpsExtract: Double,
        startMs: Long,
        endMs: Long,
        maxFrames: Int,
        cacheDir: File,
        stagingDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        val decoder = AviVideoDecoder.create(context, uri) ?: return null
        return decoder.use { avi ->
            if (!avi.canDecode) return@use null
            val picked = pickAviFrames(avi.video, fpsExtract, startMs, endMs, maxFrames)
            if (picked.size < 2) return@use null

            val defPaths = mutableListOf<String>()
            val defTimesMs = mutableMapOf<String, Long>()
            var refPng: ByteArray? = null
            var refWidth = 0
            var refHeight = 0
            var refPreview: Bitmap? = null
            val count = picked.size

            for ((i, entry) in picked.entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                val luma = avi.decodeFrame(entry.key) ?: return@use null
                currentCoroutineContext().ensureActive()

                if (i == 0) {
                    refWidth = luma.outWidth
                    refHeight = luma.outHeight
                    val out = ByteArrayOutputStream()
                    GrayPngEncoder.encode(out, luma)
                    refPng = out.toByteArray()
                    refPreview = BitmapDecode.decodeByteArrayCapped(refPng, PREVIEW_MAX_EDGE)
                } else {
                    val f = File(stagingDir, String.format(Locale.US, "%04d_frame.png", i))
                    FileOutputStream(f).use { out -> GrayPngEncoder.encode(out, luma) }
                    defPaths.add(f.absolutePath)
                    defTimesMs[f.absolutePath] = entry.value
                }

                onProgress((i + 1) * 100 / count, "Extracting frame ${i + 1} of $count")
            }

            val pngBytes = refPng
            if (pngBytes == null || defPaths.isEmpty()) {
                return@use null
            }
            assemble(
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
        }
    }

    /**
     * Frame indices to pull out of [video], mapped to their offset from
     * [startMs] in milliseconds. An AVI has no seekable timeline of its own, so
     * a sampling time maps onto an index; repeats collapse, which is what keeps
     * a rate above the stream's own from asking for the same frame twice.
     */
    private fun pickAviFrames(
        video: AviReader.Video,
        fpsExtract: Double,
        startMs: Long,
        endMs: Long,
        maxFrames: Int,
    ): Map<Int, Long> {
        val picked = LinkedHashMap<Int, Long>()
        for (timeMs in VideoSampling.sampleTimesMs(startMs, endMs, fpsExtract, maxFrames)) {
            val index = video.frameIndexAt((timeMs * 1000).toLong())
            if (!picked.containsKey(index)) picked[index] = (timeMs - startMs).toLong()
        }
        return picked
    }

    /**
     * Commits the staged PNGs as an [ImportedBatch]. Both extraction paths end
     * here, so they produce identical batches.
     */
    private suspend fun assemble(
        cacheDir: File,
        stagingDir: File,
        refPng: ByteArray,
        refWidth: Int,
        refHeight: Int,
        refPreview: Bitmap?,
        startMs: Long,
        defPaths: List<String>,
        defTimesMs: Map<String, Long>,
    ): ExtractionResult {
        val sortedDefPaths = defPaths.sorted()
        val videoFrameSize = refWidth to refHeight
        val stagedBatch = ImportedBatch(
            filePaths = sortedDefPaths,
            originalNames = sortedDefPaths.mapIndexed { idx, _ ->
                String.format(Locale.US, "frame_%04d.png", idx + 1)
            },
            frameSizes = sortedDefPaths.associateWith { videoFrameSize },
            fromVideo = true,
        )
        currentCoroutineContext().ensureActive()
        val batch = requireNotNull(
            FrameImportHelper.commitStagedBatch(cacheDir, stagingDir, stagedBatch),
        )
        // The commit moves the files but keeps their order, so the staged paths
        // line up with the committed batch index for index.
        return ExtractionResult(
            refPng = refPng,
            refWidth = refWidth,
            refHeight = refHeight,
            refName = "video @ ${formatClock(startMs)}",
            refPreview = refPreview,
            batch = batch,
            defTimesMs = sortedDefPaths.map { defTimesMs.getValue(it) },
        )
    }

    /** Prefer keyframe seek; fall back to closest frame if sync seek misses. */
    private fun getFrameHybrid(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? {
        return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }

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
