// Frame extraction: extract() walks the video in one cohesive decode loop (seek,
// decode, write, progress) with literal timing/quality constants and a broad
// per-frame guard, so those rules are suppressed for this whole file.
@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
)

@file:SuppressLint("InlinedApi")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.graphics.scale
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.ImageEncode
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
        try {
            retriever.setDataSource(context, uri)

            val stepMs = 1000.0 / fpsExtract
            val span = (endMs - startMs).coerceAtLeast(0L)
            val count = ((span / stepMs).toInt() + 1).coerceIn(1, maxFrames)

            val tempDir = File(cacheDir, "temp_deformed")
            if (!tempDir.exists()) tempDir.mkdirs()
            tempDir.listFiles()?.forEach { it.delete() }

            val defPaths = mutableListOf<String>()
            var refPng: ByteArray? = null
            var refWidth = 0
            var refHeight = 0
            var refPreview: Bitmap? = null

            for (i in 0 until count) {
                val timeMs = startMs + i * stepMs
                if (timeMs > endMs + stepMs / 2) break
                val frame = getFrameHybrid(retriever, (timeMs * 1000).toLong()) ?: continue

                if (i == 0) {
                    refWidth = frame.width
                    refHeight = frame.height
                    refPng = compressPngToBytes(frame)
                    refPreview = scaledPreview(frame)
                    frame.recycle()
                } else {
                    val f = File(tempDir, String.format(Locale.US, "%04d_frame.png", i))
                    FileOutputStream(f).use { out ->
                        frame.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                    }
                    frame.recycle()
                    defPaths.add(f.absolutePath)
                }

                onProgress((i + 1) * 100 / count, "Extracting frame ${i + 1} of $count")
            }

            val pngBytes = refPng
            if (pngBytes == null || defPaths.isEmpty()) return null

            val sortedDefPaths = defPaths.sorted()
            val videoFrameSize = refWidth to refHeight
            val batch = ImportedBatch(
                filePaths = sortedDefPaths,
                originalNames = sortedDefPaths.mapIndexed { idx, _ ->
                    String.format(Locale.US, "frame_%04d.png", idx + 1)
                },
                frameSizes = sortedDefPaths.associateWith { videoFrameSize },
                fromVideo = true,
            )

            return ExtractionResult(
                refPng = pngBytes,
                refWidth = refWidth,
                refHeight = refHeight,
                refName = "video @ ${formatClock(startMs)}",
                refPreview = refPreview,
                batch = batch,
            )
        } finally {
            runCatching { retriever.release() }
                .onFailure { Timber.w(it, "MediaMetadataRetriever.release failed") }
        }
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
