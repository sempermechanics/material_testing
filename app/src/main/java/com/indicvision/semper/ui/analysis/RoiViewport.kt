package com.indicvision.semper.ui.analysis

import android.graphics.RectF
import kotlin.math.min

/**
 * Zoom and pan for the ROI editor's canvas. Zoom 1 is the fit-center rest
 * view; the state is the zoom and the image point (0..1 of each side) shown
 * at the view centre, so a canvas resize (keyboard, toolbar) keeps both.
 *
 * A zoomed side always covers the view and a side smaller than the view stays
 * centred, so the photo can never be panned off screen.
 */
internal class RoiViewport {

    var zoom = 1f
        private set

    val isZoomed: Boolean get() = zoom > 1f + ZOOM_EPS

    private var centerX = HALF
    private var centerY = HALF
    private var viewWidth = 0f
    private var viewHeight = 0f

    /** Image size in view px at zoom 1. */
    private var fitWidth = 0f
    private var fitHeight = 0f

    /** Sets the drawable's size and the view's; keeps zoom and centre. */
    fun layout(imageWidth: Float, imageHeight: Float, viewWidth: Float, viewHeight: Float) {
        val fit = min(viewWidth / imageWidth, viewHeight / imageHeight)
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        fitWidth = imageWidth * fit
        fitHeight = imageHeight * fit
        clampCenter()
    }

    /** Where the image sits, in view px. */
    fun bounds(out: RectF): RectF {
        val w = fitWidth * zoom
        val h = fitHeight * zoom
        val left = viewWidth / 2f - centerX * w
        val top = viewHeight / 2f - centerY * h
        out.set(left, top, left + w, top + h)
        return out
    }

    /** Scales by [factor] about view point ([focusX], [focusY]), within 1..[MAX_ZOOM]. */
    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (fitWidth <= 0f || fitHeight <= 0f) return
        val w = fitWidth * zoom
        val h = fitHeight * zoom
        // The image point under the focus stays under it.
        val u = centerX + (focusX - viewWidth / 2f) / w
        val v = centerY + (focusY - viewHeight / 2f) / h
        zoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        centerX = u - (focusX - viewWidth / 2f) / (fitWidth * zoom)
        centerY = v - (focusY - viewHeight / 2f) / (fitHeight * zoom)
        clampCenter()
    }

    /** Moves the image by ([dx], [dy]) view px. */
    fun panBy(dx: Float, dy: Float) {
        if (fitWidth <= 0f || fitHeight <= 0f) return
        centerX -= dx / (fitWidth * zoom)
        centerY -= dy / (fitHeight * zoom)
        clampCenter()
    }

    /** Double-tap: back to fit when zoomed, else [DOUBLE_TAP_ZOOM] about the tap. */
    fun toggle(focusX: Float, focusY: Float) {
        if (isZoomed) reset() else zoomBy(DOUBLE_TAP_ZOOM, focusX, focusY)
    }

    fun reset() {
        zoom = 1f
        centerX = HALF
        centerY = HALF
    }

    private fun clampCenter() {
        centerX = clampAxis(centerX, viewWidth, fitWidth * zoom)
        centerY = clampAxis(centerY, viewHeight, fitHeight * zoom)
    }

    private fun clampAxis(center: Float, view: Float, size: Float): Float {
        if (size <= view || size <= 0f) return HALF
        val half = view / 2f / size
        return center.coerceIn(half, 1f - half)
    }

    companion object {
        /** Matches the viewer's pinch ceiling (`TouchImageView`). */
        const val MAX_ZOOM = 10f
        const val DOUBLE_TAP_ZOOM = 2f
        private const val HALF = 0.5f
        private const val ZOOM_EPS = 1e-3f
    }
}
