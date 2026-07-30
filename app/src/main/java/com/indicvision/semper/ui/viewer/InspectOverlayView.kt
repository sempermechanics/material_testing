// Custom overlay view: literal marker sizes, stroke widths and colours are
// clearest inline, so MagicNumber is suppressed for this whole file.
@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class InspectOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Probe State
    private var drawX = -1f
    private var drawY = -1f
    private var showCrosshair = false

    // Max/Min State
    private var maxDX = -1f
    private var maxDY = -1f
    private var minDX = -1f
    private var minDY = -1f
    private var showMaxMin = false

    // Paints
    private val paintProbe = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val paintMax = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val paintMin = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }
    private val paintShadow = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    fun updatePosition(x: Float, y: Float) {
        drawX = x
        drawY = y
        showCrosshair = true
        invalidate()
    }

    fun hide() {
        showCrosshair = false
        invalidate()
    }

    fun updateMaxMinPositions(maxX: Float, maxY: Float, minX: Float, minY: Float) {
        maxDX = maxX
        maxDY = maxY
        minDX = minX
        minDY = minY
        showMaxMin = true
        invalidate()
    }

    fun hideMaxMin() {
        showMaxMin = false
        invalidate()
    }

    private fun drawReticle(canvas: Canvas, x: Float, y: Float, paint: Paint) {
        val radius = 15f
        val lineLen = 35f
        val gap = 5f

        // Shadow
        canvas.drawCircle(x, y, radius, paintShadow)
        canvas.drawLine(x - lineLen, y, x - gap, y, paintShadow)
        canvas.drawLine(x + gap, y, x + lineLen, y, paintShadow)
        canvas.drawLine(x, y - lineLen, x, y - gap, paintShadow)
        canvas.drawLine(x, y + gap, x, y + lineLen, paintShadow)

        // Color Reticle
        canvas.drawCircle(x, y, radius, paint)
        canvas.drawLine(x - lineLen, y, x - gap, y, paint)
        canvas.drawLine(x + gap, y, x + lineLen, y, paint)
        canvas.drawLine(x, y - lineLen, x, y - gap, paint)
        canvas.drawLine(x, y + gap, x, y + lineLen, paint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showCrosshair) drawReticle(canvas, drawX, drawY, paintProbe)
        if (showMaxMin) {
            drawReticle(canvas, maxDX, maxDY, paintMax)
            drawReticle(canvas, minDX, minDY, paintMin)
        }
    }
}
