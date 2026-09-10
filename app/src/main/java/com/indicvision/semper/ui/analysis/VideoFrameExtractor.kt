@file:Suppress(
    "MagicNumber",
    "LongParameterList",
    "NestedBlockDepth",
    "TooGenericExceptionCaught",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
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
    val rotationDegrees: Int = 0,
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

    private val PREVIEW_MAX_EDGE = BitmapDecode.PREVIEW_MAX_EDGE

    fun formatClock(ms: Long): String = VideoKeyframeHelper.formatClock(ms)

    fun readMeta(context: Context, uri: Uri): VideoMeta {
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
    )

    /**
     * Extract frames. Call from IO. Invokes [onProgress] from the background thread.
     * Returns [ExtractionResult] or null if insufficient frames.
     */
    suspend fun extract(
        context: Context,
        uri: Uri,
        fpsExtract: Double,
        startMs: Long,
        endMs: Long,
        maxFrames: Int,
        cacheDir: File,
        preferKeyframes: Boolean = true,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        val stagingDir = FrameImportHelper.createStagingDir(cacheDir)
        var completed = false
        var refPreview: Bitmap? = null
        try {
            val meta = readMeta(context, uri)
            val hwResult = extractWithHardwareDecoder(
                context = context,
                uri = uri,
                rotationDegrees = meta.rotationDegrees,
                fpsExtract = fpsExtract,
                startMs = startMs,
                endMs = endMs,
                maxFrames = maxFrames,
                cacheDir = cacheDir,
                stagingDir = stagingDir,
                preferKeyframes = preferKeyframes,
                onProgress = onProgress,
            )
            if (hwResult != null) {
                refPreview = hwResult.refPreview
                completed = true
                return hwResult
            }

            Timber.i("Falling back to MediaMetadataRetriever for video frame extraction")
            val fallbackResult = extractWithRetriever(
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
            if (fallbackResult != null) {
                refPreview = fallbackResult.refPreview
                completed = true
                return fallbackResult
            }
            return null
        } finally {
            stagingDir.deleteRecursively()
            if (!completed) refPreview?.recycle()
        }
    }

    private suspend fun extractWithHardwareDecoder(
        context: Context,
        uri: Uri,
        rotationDegrees: Int,
        fpsExtract: Double,
        startMs: Long,
        endMs: Long,
        maxFrames: Int,
        cacheDir: File,
        stagingDir: File,
        preferKeyframes: Boolean,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        val decoder = HardwareVideoDecoder.create(context, uri, rotationDegrees) ?: return null
        return decoder.use { dec ->
            try {
                val startUs = startMs * 1000L
                val endUs = endMs * 1000L
                val keyframesUs = VideoKeyframeHelper.findKeyframeTimestampsUs(
                    extractor = dec.extractor,
                    trackIndex = dec.trackIndex,
                    startUs = startUs,
                    endUs = endUs,
                )
                val plan = VideoKeyframeHelper.resolveExtractionPlan(
                    keyframeTimestampsUs = keyframesUs,
                    startMs = startMs,
                    endMs = endMs,
                    fpsExtract = fpsExtract,
                    maxFrames = maxFrames,
                    forceUniform = !preferKeyframes,
                )
                if (plan.timestampsUs.size < 2) return null

                decodePlanToBatch(
                    context = context,
                    decoder = dec,
                    plan = plan,
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

    private suspend fun decodePlanToBatch(
        context: Context,
        decoder: HardwareVideoDecoder,
        plan: VideoKeyframeHelper.ExtractionPlan,
        startMs: Long,
        cacheDir: File,
        stagingDir: File,
        onProgress: (percent: Int, status: String) -> Unit,
    ): ExtractionResult? {
        val defPaths = mutableListOf<String>()
        var refPng: ByteArray? = null
        var refWidth = 0
        var refHeight = 0
        var refPreview: Bitmap? = null
        val count = plan.timestampsUs.size

        for ((i, timeUs) in plan.timestampsUs.withIndex()) {
            currentCoroutineContext().ensureActive()
            val luma = decoder.decodeFrameAt(timeUs) ?: return null
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
            }

            val status = context.getString(R.string.video_extracting_progress_fmt, i + 1, count)
            onProgress((i + 1) * 100 / count, status)
        }

        val pngBytes = refPng ?: return null
        if (defPaths.isEmpty()) return null

        return assembleExtractionResult(
            cacheDir = cacheDir,
            stagingDir = stagingDir,
            refPng = pngBytes,
            refWidth = refWidth,
            refHeight = refHeight,
            refPreview = refPreview,
            startMs = startMs,
            defPaths = defPaths,
        )
    }

    private suspend fun extractWithRetriever(
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
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val stepMs = 1000.0 / fpsExtract
            val span = (endMs - startMs).coerceAtLeast(0L)
            val count = ((span / stepMs).toInt() + 1).coerceIn(1, maxFrames)

            val defPaths = mutableListOf<String>()
            var refPng: ByteArray? = null
            var refWidth = 0
            var refHeight = 0
            var refPreview: Bitmap? = null

            for (i in 0 until count) {
                currentCoroutineContext().ensureActive()
                val timeMs = startMs + i * stepMs
                if (timeMs > endMs + stepMs / 2) break
                val frame = getFrameHybrid(retriever, (timeMs * 1000).toLong()) ?: continue
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
                    }
                } finally {
                    frame.recycle()
                }

                val status = context.getString(R.string.video_extracting_progress_fmt, i + 1, count)
                onProgress((i + 1) * 100 / count, status)
            }

            val pngBytes = refPng ?: return null
            if (defPaths.isEmpty()) return null

            return assembleExtractionResult(
                cacheDir = cacheDir,
                stagingDir = stagingDir,
                refPng = pngBytes,
                refWidth = refWidth,
                refHeight = refHeight,
                refPreview = refPreview,
                startMs = startMs,
                defPaths = defPaths,
            )
        } catch (e: Exception) {
            Timber.w(e, "Retriever extraction fallback failed")
            return null
        } finally {
            runCatching { retriever.release() }
                .onFailure { Timber.w(it, "MediaMetadataRetriever.release failed") }
        }
    }

    private suspend fun assembleExtractionResult(
        cacheDir: File,
        stagingDir: File,
        refPng: ByteArray,
        refWidth: Int,
        refHeight: Int,
        refPreview: Bitmap?,
        startMs: Long,
        defPaths: List<String>,
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

        return ExtractionResult(
            refPng = refPng,
            refWidth = refWidth,
            refHeight = refHeight,
            refName = "video @ ${formatClock(startMs)}",
            refPreview = refPreview,
            batch = batch,
        )
    }

    private fun getFrameHybrid(retriever: MediaMetadataRetriever, timeUs: Long): Bitmap? =
        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)

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
