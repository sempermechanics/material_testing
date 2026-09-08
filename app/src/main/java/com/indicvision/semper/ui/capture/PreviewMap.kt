package com.indicvision.semper.ui.capture

import android.graphics.Matrix
import android.graphics.PointF

/**
 * The geometry between the preview buffer, the view showing it, and the
 * fractions the rest of the capture path speaks in.
 *
 * Three frames of reference meet on the capture screen and none of them agree:
 *
 * - the **buffer** Camera2 fills, laid out the way the sensor is mounted;
 * - the **view** the user is looking at, where the buffer has been turned
 *   upright, scaled to fit and centred, with letterbox bars around it;
 * - the **upright fraction** everything downstream carries — the focus point,
 *   the contrast ROI — normalised against the picture as the user sees it.
 *
 * Held as one object because the two directions have to be exact inverses. A
 * focus tap goes view → upright and the ring that marks it goes upright → view,
 * and if those were derived separately the ring could sit a quarter turn or a
 * letterbox bar away from the pixel the camera actually focused on — with
 * nothing failing, because every coordinate involved is still valid.
 *
 * [of] returns null rather than a degenerate map when there is no size to work
 * with yet, or when the fit turned out not to be invertible, so callers get an
 * absent map instead of one that silently answers wrongly.
 */
internal class PreviewMap private constructor(
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    private val rotationDegrees: Int,
    private val bufferToView: Matrix,
    private val viewToBuffer: Matrix,
    /**
     * The transform a [android.view.TextureView] needs to show this fit.
     *
     * A TextureView stretches its buffer to its own bounds before any transform
     * is applied, so the transform is composed in *view* space and has to start
     * by undoing that stretch. Everything after the undo is [bufferToView]
     * itself — which is why the tap mapping shares it rather than re-deriving
     * the same fit a second time.
     */
    val textureTransform: Matrix,
) {

    /**
     * Where a tap at [viewX], [viewY] lands in the upright picture, or null
     * when it fell on a letterbox bar rather than on the frame.
     *
     * Null rather than a clamp: a tap beside the frame is not a request to
     * focus on the frame's edge, and quietly moving it there would put the lock
     * somewhere the user did not point at.
     */
    fun uprightPointAt(viewX: Float, viewY: Float): PointF? {
        val point = floatArrayOf(viewX, viewY)
        viewToBuffer.mapPoints(point)
        if (!insideBuffer(point[0], point[1])) return null
        val (normX, normY) = CaptureOrientation.rotatePoint(
            point[0] / bufferWidth,
            point[1] / bufferHeight,
            rotationDegrees,
        )
        return PointF(normX, normY)
    }

    /** Where the upright fraction [normX], [normY] shows up on screen. */
    fun viewPointOf(normX: Float, normY: Float): PointF {
        val (bufNormX, bufNormY) = CaptureOrientation.rotatePoint(normX, normY, -rotationDegrees)
        val point = floatArrayOf(bufNormX * bufferWidth, bufNormY * bufferHeight)
        bufferToView.mapPoints(point)
        return PointF(point[0], point[1])
    }

    private fun insideBuffer(x: Float, y: Float): Boolean =
        x in 0f..bufferWidth.toFloat() && y in 0f..bufferHeight.toFloat()

    companion object {
        fun of(viewW: Int, viewH: Int, bufW: Int, bufH: Int, rotationDegrees: Int): PreviewMap? {
            if (minOf(viewW, viewH, bufW, bufH) <= 0) return null
            val scale = CaptureOrientation.previewFitScale(viewW, viewH, bufW, bufH, rotationDegrees)
            val bufferToView = Matrix().apply {
                postTranslate(-bufW / 2f, -bufH / 2f)
                postRotate(rotationDegrees.toFloat())
                postScale(scale, scale)
                postTranslate(viewW / 2f, viewH / 2f)
            }
            val viewToBuffer = Matrix()
            return PreviewMap(
                bufferWidth = bufW,
                bufferHeight = bufH,
                rotationDegrees = rotationDegrees,
                bufferToView = bufferToView,
                viewToBuffer = viewToBuffer,
                textureTransform = Matrix().apply {
                    postScale(bufW.toFloat() / viewW, bufH.toFloat() / viewH)
                    postConcat(bufferToView)
                },
            ).takeIf { bufferToView.invert(viewToBuffer) }
        }
    }
}
