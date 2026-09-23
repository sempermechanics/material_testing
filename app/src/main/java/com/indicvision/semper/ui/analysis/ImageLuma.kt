package com.indicvision.semper.ui.analysis

import android.media.Image
import com.indicvision.semper.imaging.GrayPngEncoder

/**
 * The Y plane of a decoded [Image], copied out as a [GrayPngEncoder.Luma].
 *
 * Plane 0 of a `COLOR_FormatYUV420Flexible` output already is the luminance the
 * DIC engine correlates on, so taking it directly skips every YUV→RGB→gray
 * conversion and the blur that comes with them.
 */
internal object ImageLuma {

    /** Copies the cropped Y plane of [image]; the caller still owns the image. */
    fun of(image: Image, rotationDegrees: Int): GrayPngEncoder.Luma {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val crop = image.cropRect
        val width = if (crop.width() > 0) crop.width() else image.width
        val height = if (crop.height() > 0) crop.height() else image.height

        val buffer = plane.buffer.duplicate()
        val start = (crop.top * rowStride + crop.left * pixelStride).coerceIn(0, buffer.capacity())
        buffer.position(start)
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        return GrayPngEncoder.Luma(
            bytes = bytes,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            rotationDegrees = rotationDegrees,
        )
    }
}
