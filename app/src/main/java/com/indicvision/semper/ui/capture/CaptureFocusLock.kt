package com.indicvision.semper.ui.capture

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.Serializable

/**
 * Focus / exposure remembered from the test shot and re-applied on the
 * hardware lock pass. The normalized point comes from the strongest SSSIG
 * sample; EXIF tags are best-effort (phones often omit subject distance).
 */
data class CaptureFocusLock(
    /** Focus point in the image, 0..1 from left. */
    val normX: Float,
    /** Focus point in the image, 0..1 from top. */
    val normY: Float,
    val focalLengthMm: Float? = null,
    val subjectDistanceM: Float? = null,
    val fNumber: Float? = null,
    val iso: Int? = null,
    val exposureTimeSec: Double? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val testShotPath: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L

        fun fromExif(
            file: File,
            normX: Float,
            normY: Float,
            imageWidth: Int,
            imageHeight: Int,
        ): CaptureFocusLock {
            val exif = runCatching { ExifInterface(file) }.getOrNull()
            return CaptureFocusLock(
                normX = normX.coerceIn(0f, 1f),
                normY = normY.coerceIn(0f, 1f),
                focalLengthMm = exif?.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN)
                    ?.takeIf { it.isFinite() && it > 0 }?.toFloat(),
                subjectDistanceM = exif?.getAttributeDouble(
                    ExifInterface.TAG_SUBJECT_DISTANCE,
                    Double.NaN,
                )?.takeIf { it.isFinite() && it > 0 }?.toFloat(),
                fNumber = exif?.getAttributeDouble(ExifInterface.TAG_F_NUMBER, Double.NaN)
                    ?.takeIf { it.isFinite() && it > 0 }?.toFloat(),
                iso = exif?.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                    ?.takeIf { it > 0 },
                exposureTimeSec = exif?.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, Double.NaN)
                    ?.takeIf { it.isFinite() && it > 0 },
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                testShotPath = file.absolutePath,
            )
        }
    }
}
