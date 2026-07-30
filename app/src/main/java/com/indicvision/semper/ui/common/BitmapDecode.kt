// Bitmap decode/sample math: literal max-edge and sample-step constants read
// clearest inline, so MagicNumber is suppressed for this whole file.
@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.indicvision.semper.report.VisualizationEngine
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Shared BitmapFactory helpers for display-sized decodes and RAW→RGBA import.
 */
object BitmapDecode {

    /** Preview / wizard long-edge budget (matches historical wizard previews). */
    const val PREVIEW_MAX_EDGE = 1000

    /**
     * Power-of-two [BitmapFactory.Options.inSampleSize] that fits [width]×[height]
     * into [reqWidth]×[reqHeight], also capped by [maxLongEdge].
     */
    fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int,
        maxLongEdge: Int = VisualizationEngine.DISPLAY_MAX_EDGE,
    ): Int {
        var inSampleSize = 1
        if (width <= 0 || height <= 0) return 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
            val longest = max(width, height)
            while (longest / inSampleSize > maxLongEdge * 2 && inSampleSize < 64) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /** Decode [bytes] with an inSampleSize that keeps the long edge ≤ [maxLongEdge]. */
    fun decodeByteArrayCapped(
        bytes: ByteArray,
        maxLongEdge: Int = VisualizationEngine.DISPLAY_MAX_EDGE,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longEdge / sample > maxLongEdge) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** Decode a file path with inSampleSize for [reqWidth]×[reqHeight]. */
    fun decodeFileForView(
        path: String,
        reqWidth: Int,
        reqHeight: Int,
        maxLongEdge: Int = VisualizationEngine.DISPLAY_MAX_EDGE,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val sample = calculateInSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            reqWidth,
            reqHeight,
            maxLongEdge,
        )
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, opts)
    }

    /** Write RGBA bytes from a RAW/DNG stream into [dest]; return width×height. */
    fun writeRgbaFromStream(stream: InputStream, dest: File): Pair<Int, Int>? {
        val bitmap = BitmapFactory.decodeStream(stream) ?: return null
        val buffer = ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
        bitmap.copyPixelsToBuffer(buffer)
        dest.writeBytes(buffer.array())
        val size = bitmap.width to bitmap.height
        bitmap.recycle()
        return size
    }

    /** Full-size RGBA plus a preview scaled to [previewMaxEdge]. */
    fun rgbaAndPreviewFromStream(
        stream: InputStream,
        previewMaxEdge: Int = PREVIEW_MAX_EDGE,
    ): RgbaWithPreview? {
        val bitmap = BitmapFactory.decodeStream(stream) ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val buffer = ByteBuffer.allocate(width * height * 4)
        bitmap.copyPixelsToBuffer(buffer)
        val longEdge = max(width, height)
        val preview = if (longEdge <= previewMaxEdge) {
            bitmap
        } else {
            val scale = previewMaxEdge.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                bitmap,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { scaled ->
                if (scaled !== bitmap) bitmap.recycle()
            }
        }
        return RgbaWithPreview(buffer.array(), width, height, preview)
    }

    data class RgbaWithPreview(
        val rgba: ByteArray,
        val width: Int,
        val height: Int,
        val preview: Bitmap,
    )
}
