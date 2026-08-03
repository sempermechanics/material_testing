@file:Suppress("LongMethod")

package com.indicvision.semper.ui.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.core.graphics.createBitmap

/**
 * ALPHA_8 ROI mask encoding extracted from [StudioOverlayView.generateMaskBytes].
 * Behavior identical: white = correlate, clear = void.
 */
object StudioOverlayMaskEncoder {

    data class Input(
        val realImageWidth: Int,
        val realImageHeight: Int,
        val imageBounds: RectF,
        val hasValidRoi: Boolean,
        val roiRect: RectF,
        val mainRoiMode: StudioOverlayView.RoiMode,
        val mainFreeformPath: Path,
        val holes: List<StudioOverlayView.Hole>,
    )

    fun encode(input: Input): ByteArray {
        if (input.realImageWidth <= 0 || input.realImageHeight <= 0) return ByteArray(0)

        val maskBitmap = createBitmap(input.realImageWidth, input.realImageHeight, Bitmap.Config.ALPHA_8)
        val maskCanvas = Canvas(maskBitmap)

        maskCanvas.drawColor(Color.BLACK)

        val scaleX = input.realImageWidth.toFloat() / input.imageBounds.width()
        val scaleY = input.realImageHeight.toFloat() / input.imageBounds.height()

        val paintAdd = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        val paintSub = Paint().apply {
            color = Color.TRANSPARENT
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        if (input.hasValidRoi) {
            val mappedMainRect = RectF(
                (input.roiRect.left - input.imageBounds.left) * scaleX,
                (input.roiRect.top - input.imageBounds.top) * scaleY,
                (input.roiRect.right - input.imageBounds.left) * scaleX,
                (input.roiRect.bottom - input.imageBounds.top) * scaleY,
            )
            when (input.mainRoiMode) {
                StudioOverlayView.RoiMode.RECTANGLE, StudioOverlayView.RoiMode.SQUARE ->
                    maskCanvas.drawRect(mappedMainRect, paintAdd)
                StudioOverlayView.RoiMode.CIRCLE, StudioOverlayView.RoiMode.ELLIPSE ->
                    maskCanvas.drawOval(mappedMainRect, paintAdd)
                StudioOverlayView.RoiMode.FREEFORM -> {
                    val scaledPath = Path(input.mainFreeformPath)
                    val matrix = Matrix()
                    matrix.postTranslate(-input.imageBounds.left, -input.imageBounds.top)
                    matrix.postScale(scaleX, scaleY)
                    scaledPath.transform(matrix)
                    maskCanvas.drawPath(scaledPath, paintAdd)
                }
            }
        } else {
            maskCanvas.drawRect(0f, 0f, input.realImageWidth.toFloat(), input.realImageHeight.toFloat(), paintAdd)
        }
        for (hole in input.holes) {
            val mappedHole = RectF(
                (hole.rect.left - input.imageBounds.left) * scaleX,
                (hole.rect.top - input.imageBounds.top) * scaleY,
                (hole.rect.right - input.imageBounds.left) * scaleX,
                (hole.rect.bottom - input.imageBounds.top) * scaleY,
            )
            when (hole.mode) {
                StudioOverlayView.RoiMode.RECTANGLE, StudioOverlayView.RoiMode.SQUARE ->
                    maskCanvas.drawRect(mappedHole, paintSub)
                StudioOverlayView.RoiMode.CIRCLE, StudioOverlayView.RoiMode.ELLIPSE ->
                    maskCanvas.drawOval(mappedHole, paintSub)
                StudioOverlayView.RoiMode.FREEFORM -> {
                    val scaledPath = Path(hole.path)
                    val matrix = Matrix()
                    matrix.postTranslate(-input.imageBounds.left, -input.imageBounds.top)
                    matrix.postScale(scaleX, scaleY)
                    scaledPath.transform(matrix)
                    maskCanvas.drawPath(scaledPath, paintSub)
                }
            }
        }

        val size = maskBitmap.rowBytes * maskBitmap.height
        val byteBuffer = java.nio.ByteBuffer.allocate(size)
        maskBitmap.copyPixelsToBuffer(byteBuffer)
        maskBitmap.recycle()

        return byteBuffer.array()
    }
}
