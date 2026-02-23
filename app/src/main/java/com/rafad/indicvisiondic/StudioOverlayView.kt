package com.rafad.indicvisiondic

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class StudioOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class RoiMode { RECTANGLE, SQUARE, CIRCLE, ELLIPSE, FREEFORM }

    var currentMode = RoiMode.RECTANGLE
    var hasValidRoi = false
        private set

    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f

    private val freeformPath = Path()
    private var isDrawing = false

    var onDrawListener: ((RectF) -> Unit)? = null

    // --- NEW PROPERTIES: ROI BOUNDARY JAIL ---
    var onBoundsUpdated: ((RectF) -> Unit)? = null
    private var imageBounds = RectF()
    var imageView: ImageView? = null
        set(value) {
            field = value
            updateImageBounds()
        }

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99000000") // Spotlight effect
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        color = Color.TRANSPARENT
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val borderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    // Calculates the exact physical pixels of the scaled image
    private fun updateImageBounds() {
        val iv = imageView ?: return
        val drawable = iv.drawable ?: return

        val imgWidth = drawable.intrinsicWidth.toFloat()
        val imgHeight = drawable.intrinsicHeight.toFloat()
        val viewWidth = iv.width.toFloat()
        val viewHeight = iv.height.toFloat()

        if (imgWidth <= 0 || imgHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return

        // Mimic the math of scaleType="fitCenter"
        val scale = minOf(viewWidth / imgWidth, viewHeight / imgHeight)
        val scaledW = imgWidth * scale
        val scaledH = imgHeight * scale

        val left = (viewWidth - scaledW) / 2f
        val top = (viewHeight - scaledH) / 2f

        imageBounds = RectF(left, top, left + scaledW, top + scaledH)
        onBoundsUpdated?.invoke(imageBounds) // Sends bounds back to Activity
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageBounds()
    }

    // Mathematical Jail: Stops touch coordinates from leaving the image
    private fun clampToImage(x: Float, y: Float): PointF {
        if (imageBounds.isEmpty) return PointF(x, y)
        return PointF(
            x.coerceIn(imageBounds.left, imageBounds.right),
            y.coerceIn(imageBounds.top, imageBounds.bottom)
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Intercept and clamp every touch event before drawing!
        val clamped = clampToImage(event.x, event.y)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                hasValidRoi = false
                startX = clamped.x
                startY = clamped.y
                endX = clamped.x
                endY = clamped.y
                if (currentMode == RoiMode.FREEFORM) {
                    freeformPath.reset()
                    freeformPath.moveTo(startX, startY)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = clamped.x
                endY = clamped.y
                if (currentMode == RoiMode.FREEFORM) {
                    freeformPath.lineTo(endX, endY)
                }
                invalidate()
                onDrawListener?.invoke(getBoundingBox())
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDrawing = false
                if (currentMode == RoiMode.FREEFORM) {
                    freeformPath.close()
                }
                hasValidRoi = true
                invalidate()
                onDrawListener?.invoke(getBoundingBox())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDrawing && !hasValidRoi) return

        val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val rect = RectF(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
        val radius = Math.hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat()
        val side = max(abs(endX - startX), abs(endY - startY))
        val squareRect = RectF(
            if (endX > startX) startX else startX - side,
            if (endY > startY) startY else startY - side,
            if (endX > startX) startX + side else startX,
            if (endY > startY) startY + side else startY
        )

        when (currentMode) {
            RoiMode.RECTANGLE -> {
                canvas.drawRect(rect, clearPaint)
                canvas.drawRect(rect, borderPaint)
            }
            RoiMode.SQUARE -> {
                canvas.drawRect(squareRect, clearPaint)
                canvas.drawRect(squareRect, borderPaint)
            }
            RoiMode.CIRCLE -> {
                canvas.drawCircle(startX, startY, radius, clearPaint)
                canvas.drawCircle(startX, startY, radius, borderPaint)
            }
            RoiMode.ELLIPSE -> {
                canvas.drawOval(rect, clearPaint)
                canvas.drawOval(rect, borderPaint)
            }
            RoiMode.FREEFORM -> {
                canvas.drawPath(freeformPath, clearPaint)
                canvas.drawPath(freeformPath, borderPaint)
            }
        }
        canvas.restoreToCount(saveCount)
    }

    fun getBoundingBox(): RectF {
        if (currentMode == RoiMode.FREEFORM) {
            val bounds = RectF()
            freeformPath.computeBounds(bounds, true)
            return bounds
        }
        if (currentMode == RoiMode.SQUARE) {
            val side = max(abs(endX - startX), abs(endY - startY))
            return RectF(
                if (endX > startX) startX else startX - side,
                if (endY > startY) startY else startY - side,
                if (endX > startX) startX + side else startX,
                if (endY > startY) startY + side else startY
            )
        }
        if (currentMode == RoiMode.CIRCLE) {
            val radius = Math.hypot((endX - startX).toDouble(), (endY - startY).toDouble()).toFloat()
            return RectF(startX - radius, startY - radius, startX + radius, startY + radius)
        }
        return RectF(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
    }
}