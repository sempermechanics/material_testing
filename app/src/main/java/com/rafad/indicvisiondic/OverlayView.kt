package com.rafad.indicvisiondic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // Red paint for the ROI Rectangle
    private val rectPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    // Blue paint for the Validation Scan Line
    private val scanPaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.STROKE
        strokeWidth = 8f // Slightly thicker for visibility
    }

    private var screenRect: RectF? = null
    private var scanLineY: Float? = null

    fun drawRect(rect: RectF) {
        screenRect = rect
        invalidate()
    }

    /**
     * Draws a horizontal line across the view at the specified Y coordinate.
     */
    fun drawScanLine(y: Float) {
        scanLineY = y
        invalidate()
    }

    fun clear() {
        screenRect = null
        scanLineY = null
        invalidate()
    }

    private var pointX: Float? = null
    private var pointY: Float? = null

    fun drawPoint(x: Float, y: Float) {
        this.pointX = x
        this.pointY = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw ROI Box
        screenRect?.let { canvas.drawRect(it, rectPaint) }

        // 2. Draw Scan Line (Blue)
        scanLineY?.let { y -> canvas.drawLine(0f, y, width.toFloat(), y, scanPaint) }

        // 3. Draw Single Point (Yellow Crosshair)
        if (pointX != null && pointY != null) {
            val p = Paint().apply {
                color = Color.YELLOW
                strokeWidth = 8f
                style = Paint.Style.STROKE
            }
            canvas.drawCircle(pointX!!, pointY!!, 15f, p)
            canvas.drawLine(pointX!! - 30, pointY!!, pointX!! + 30, pointY!!, p)
            canvas.drawLine(pointX!!, pointY!! - 30, pointX!!, pointY!! + 30, p)
        }
    }
}