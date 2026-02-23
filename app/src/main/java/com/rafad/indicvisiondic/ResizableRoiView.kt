package com.rafad.indicvisiondic

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class ResizableRoiView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onRoiChangedListener: ((RectF) -> Unit)? = null

    // The actual ROI rectangle
    var roiRect = RectF(100f, 100f, 500f, 500f) // Default initial box

    private enum class TouchState { NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER }
    private var touchState = TouchState.NONE

    private var lastX = 0f
    private var lastY = 0f
    private val touchRadius = 80f // How forgiving the corner touch targets are
    private val minSize = 100f // Minimum ROI size in pixels

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000") // 60% Black spotlight
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#00FF00")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Center the default box when the view loads
        if (w > 0 && h > 0 && oldw == 0) {
            val cx = w / 2f
            val cy = h / 2f
            roiRect.set(cx - 200f, cy - 200f, cx + 200f, cy + 200f)
            onRoiChangedListener?.invoke(roiRect)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchState = getTouchState(x, y)
                lastX = x
                lastY = y
                return touchState != TouchState.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY

                when (touchState) {
                    TouchState.CENTER -> {
                        roiRect.offset(dx, dy)
                    }
                    TouchState.TOP_LEFT -> {
                        roiRect.left = (roiRect.left + dx).coerceAtMost(roiRect.right - minSize)
                        roiRect.top = (roiRect.top + dy).coerceAtMost(roiRect.bottom - minSize)
                    }
                    TouchState.TOP_RIGHT -> {
                        roiRect.right = (roiRect.right + dx).coerceAtLeast(roiRect.left + minSize)
                        roiRect.top = (roiRect.top + dy).coerceAtMost(roiRect.bottom - minSize)
                    }
                    TouchState.BOTTOM_LEFT -> {
                        roiRect.left = (roiRect.left + dx).coerceAtMost(roiRect.right - minSize)
                        roiRect.bottom = (roiRect.bottom + dy).coerceAtLeast(roiRect.top + minSize)
                    }
                    TouchState.BOTTOM_RIGHT -> {
                        roiRect.right = (roiRect.right + dx).coerceAtLeast(roiRect.left + minSize)
                        roiRect.bottom = (roiRect.bottom + dy).coerceAtLeast(roiRect.top + minSize)
                    }
                    TouchState.NONE -> {}
                }

                lastX = x
                lastY = y
                invalidate()
                onRoiChangedListener?.invoke(roiRect)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchState = TouchState.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun getTouchState(x: Float, y: Float): TouchState {
        if (hypot(x - roiRect.left, y - roiRect.top) < touchRadius) return TouchState.TOP_LEFT
        if (hypot(x - roiRect.right, y - roiRect.top) < touchRadius) return TouchState.TOP_RIGHT
        if (hypot(x - roiRect.left, y - roiRect.bottom) < touchRadius) return TouchState.BOTTOM_LEFT
        if (hypot(x - roiRect.right, y - roiRect.bottom) < touchRadius) return TouchState.BOTTOM_RIGHT
        if (roiRect.contains(x, y)) return TouchState.CENTER
        return TouchState.NONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw Spotlight Mask
        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // 2. Punch hole for ROI
        canvas.drawRect(roiRect, clearPaint)
        canvas.restoreToCount(saveCount)

        // 3. Draw border
        canvas.drawRect(roiRect, borderPaint)

        // 4. Draw corner handles
        val handleRad = 15f
        canvas.drawCircle(roiRect.left, roiRect.top, handleRad, handlePaint)
        canvas.drawCircle(roiRect.right, roiRect.top, handleRad, handlePaint)
        canvas.drawCircle(roiRect.left, roiRect.bottom, handleRad, handlePaint)
        canvas.drawCircle(roiRect.right, roiRect.bottom, handleRad, handlePaint)
    }
}