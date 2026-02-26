package com.rafad.indicvisiondic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class InspectOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var drawX = -1f
    private var drawY = -1f
    private var showCrosshair = false

    // 🚀 Thinner Neon Green Crosshair
    private val paintCrosshair = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 3f // Reduced from 4f
        isAntiAlias = true
    }

    // 🚀 Thinner subtle black shadow
    private val paintShadow = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.STROKE
        strokeWidth = 6f // Reduced from 8f
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showCrosshair) return

        // 🚀 THE SIZE CONTROLS
        val radius = 20f    // Size of the center circle (was 40)
        val lineLen = 45f   // Total length of the cross lines (was 80)
        val gap = 7.5f        // Gap between the circle and the line start (was 10)

        // 1. Draw the Shadows first
        canvas.drawCircle(drawX, drawY, radius, paintShadow)
        canvas.drawLine(drawX - lineLen, drawY, drawX - gap, drawY, paintShadow) // Left
        canvas.drawLine(drawX + gap, drawY, drawX + lineLen, drawY, paintShadow) // Right
        canvas.drawLine(drawX, drawY - lineLen, drawX, drawY - gap, paintShadow) // Top
        canvas.drawLine(drawX, drawY + gap, drawX, drawY + lineLen, paintShadow) // Bottom

        // 2. Draw the Neon Green Reticle on top
        canvas.drawCircle(drawX, drawY, radius, paintCrosshair)
        canvas.drawLine(drawX - lineLen, drawY, drawX - gap, drawY, paintCrosshair)
        canvas.drawLine(drawX + gap, drawY, drawX + lineLen, drawY, paintCrosshair)
        canvas.drawLine(drawX, drawY - lineLen, drawX, drawY - gap, paintCrosshair)
        canvas.drawLine(drawX, drawY + gap, drawX, drawY + lineLen, paintCrosshair)
    }
}