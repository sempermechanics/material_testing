package com.indicvision.semper.ui.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Rewrites a Camera-app JPEG so pixel rows match upright display and the
 * orientation tag is NORMAL. BitmapFactory bounds, OpenCV decode, and
 * BitmapRegionDecoder then agree — required for ROI + SSSIG on the same file.
 */
internal object CaptureJpegOrient {

    /** Re-encode quality when rewriting pixels to match the EXIF orientation. */
    private const val REENCODE_QUALITY = 95

    @Suppress("ReturnCount")
    fun uprightInPlace(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val exif = runCatching { ExifInterface(file) }.getOrNull() ?: return true
        val orient = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED,
        )
        if (orient == ExifInterface.ORIENTATION_UNDEFINED ||
            orient == ExifInterface.ORIENTATION_NORMAL
        ) {
            return true
        }
        // BitmapFactory does NOT apply EXIF orientation to pixels — the matrix
        // transform below is what actually rotates/flips them to match.
        val original = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        val upright = rotateForExif(original, orient)
        return try {
            FileOutputStream(file).use { out ->
                upright.compress(Bitmap.CompressFormat.JPEG, REENCODE_QUALITY, out)
            }
            ExifInterface(file).apply {
                setAttribute(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL.toString(),
                )
                saveAttributes()
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            if (upright !== original) original.recycle()
            upright.recycle()
        }
    }

    /** Applies the pixel transform [orientation] declares; returns [bmp] unchanged if none applies. */
    private fun rotateForExif(bmp: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(ROTATE_90)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(ROTATE_180)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(ROTATE_270)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(ROTATE_90)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(ROTATE_270)
                matrix.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private const val ROTATE_90 = 90f
    private const val ROTATE_180 = 180f
    private const val ROTATE_270 = 270f
}
