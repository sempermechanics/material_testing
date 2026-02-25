package com.rafad.indicvisiondic

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class StudioOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 🚀 NEW: Store the actual image dimensions for perfect coordinate scaling
    var realImageWidth: Int = 0
    var realImageHeight: Int = 0

    // --- 1. IMAGE BOUNDARY TRACKING ---
    private var imageBounds = RectF()
    var onRoiChangedListener: ((RectF) -> Unit)? = null
    var imageView: ImageView? = null
        set(value) {
            field = value
            if (value?.width ?: 0 > 0) updateImageBounds()
        }

    private var pendingRestoreRoi: RectF? = null

    fun updateImageBounds() {
        val iv = imageView ?: return
        val drawable = iv.drawable ?: return
        val imageWidth = drawable.intrinsicWidth.toFloat()
        val imageHeight = drawable.intrinsicHeight.toFloat()
        val viewWidth = iv.width.toFloat()
        val viewHeight = iv.height.toFloat()
        if (imageWidth == 0f || imageHeight == 0f) return

        val scale = min(viewWidth / imageWidth, viewHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale
        val left = (viewWidth - scaledWidth) / 2f
        val top = (viewHeight - scaledHeight) / 2f

        imageBounds.set(left, top, left + scaledWidth, top + scaledHeight)

        // If we survived a screen rotation, restore the box now that bounds are ready
        applyPendingRestore()

        if (roiRect.isEmpty && !hasValidRoi) roiRect.set(imageBounds)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateImageBounds()
    }

    // --- 2. LIFECYCLE RESTORE ---
    fun restoreRelativeRoi(savedRoi: RectF) {
        pendingRestoreRoi = savedRoi
        if (!imageBounds.isEmpty) {
            applyPendingRestore()
        }
    }

    private fun applyPendingRestore() {
        pendingRestoreRoi?.let { saved ->
            // Map the physical image coordinates back to the scaled screen view
            val scale = if (realImageWidth > 0) realImageWidth.toFloat() / imageBounds.width()
            else (imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f) / imageBounds.width()

            val left = imageBounds.left + (saved.left / scale)
            val top = imageBounds.top + (saved.top / scale)
            val right = imageBounds.left + (saved.right / scale)
            val bottom = imageBounds.top + (saved.bottom / scale)

            roiRect.set(left, top, right, bottom)
            hasValidRoi = true
            invalidate()
        }
        pendingRestoreRoi = null
    }

    // --- 3. RESET ---
    fun reset() {
        hasValidRoi = false
        isDrawing = false
        roiRect.setEmpty()
        freeformPath.reset()
        touchState = TouchState.NONE
        invalidate()
        onRoiChangedListener?.invoke(RectF())
    }

    enum class RoiMode { RECTANGLE, SQUARE, CIRCLE, ELLIPSE, FREEFORM }
    var currentMode = RoiMode.RECTANGLE
    var hasValidRoi = false
        private set

    private var roiRect = RectF()
    private val minSize = 50f

    // Drawing variables
    private var startX = 0f; private var startY = 0f
    private var endX = 0f; private var endY = 0f
    private var lastX = 0f; private var lastY = 0f
    private val freeformPath = Path()
    private var isDrawing = false

    private enum class TouchState { NONE, CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var touchState = TouchState.NONE

    private val borderPaint = Paint().apply { color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 5f }
    private val handlePaint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val dimPaint = Paint().apply { color = Color.parseColor("#99000000"); style = Paint.Style.FILL }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); style = Paint.Style.FILL }
    private fun safeCoerce(value: Float, min: Float, max: Float): Float {
        val actualMax = if (max < min) min else max
        return value.coerceIn(min, actualMax)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bounds = if (imageBounds.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else imageBounds

        // 🚀 THE SAFETY RAIL: Clamp touch exactly to the image boundaries
        val x = event.x.coerceIn(bounds.left, bounds.right)
        val y = event.y.coerceIn(bounds.top, bounds.bottom)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (hasValidRoi && currentMode != RoiMode.FREEFORM) {
                    touchState = getTouchState(x, y)
                    if (touchState != TouchState.NONE) {
                        lastX = x; lastY = y
                        return true
                    }
                }

                reset()
                isDrawing = true
                startX = x; startY = y
                endX = x; endY = y
                if (currentMode == RoiMode.FREEFORM) freeformPath.moveTo(startX, startY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchState != TouchState.NONE) {
                    val dx = x - lastX
                    val dy = y - lastY

                    when (touchState) {
                        TouchState.CENTER -> {
                            // 🚀 Safe clamping inside the image
                            val maxLeft = bounds.right - roiRect.width()
                            val maxTop = bounds.bottom - roiRect.height()
                            // 🚀 Clamp dragging inside the image
                            val newLeft = (roiRect.left + dx).coerceIn(bounds.left, bounds.right - roiRect.width())
                            val newTop = (roiRect.top + dy).coerceIn(bounds.top, bounds.bottom - roiRect.height())
                            roiRect.offsetTo(newLeft, newTop)
                        }
                        TouchState.TOP_LEFT -> {
                            roiRect.left = (roiRect.left + dx).coerceIn(bounds.left, roiRect.right - minSize)
                            roiRect.top = (roiRect.top + dy).coerceIn(bounds.top, roiRect.bottom - minSize)
                            if (isSquareMode()) makeSquare(pivotX = roiRect.right, pivotY = roiRect.bottom, bounds)
                        }
                        TouchState.TOP_RIGHT -> {
                            roiRect.right = (roiRect.right + dx).coerceIn(roiRect.left + minSize, bounds.right)
                            roiRect.top = (roiRect.top + dy).coerceIn(bounds.top, roiRect.bottom - minSize)
                            if (isSquareMode()) makeSquare(pivotX = roiRect.left, pivotY = roiRect.bottom, bounds)
                        }
                        TouchState.BOTTOM_LEFT -> {
                            roiRect.left = (roiRect.left + dx).coerceIn(bounds.left, roiRect.right - minSize)
                            roiRect.bottom = (roiRect.bottom + dy).coerceIn(roiRect.top + minSize, bounds.bottom)
                            if (isSquareMode()) makeSquare(pivotX = roiRect.right, pivotY = roiRect.top, bounds)
                        }
                        TouchState.BOTTOM_RIGHT -> {
                            roiRect.right = (roiRect.right + dx).coerceIn(roiRect.left + minSize, bounds.right)
                            roiRect.bottom = (roiRect.bottom + dy).coerceIn(roiRect.top + minSize, bounds.bottom)
                            if (isSquareMode()) makeSquare(pivotX = roiRect.left, pivotY = roiRect.top, bounds)
                        }
                        else -> {}
                    }
                    lastX = x; lastY = y
                } else {
                    if (currentMode == RoiMode.FREEFORM) {
                        endX = x; endY = y
                        freeformPath.lineTo(endX, endY)
                    } else {
                        endX = x; endY = y
                        if (isSquareMode()) {
                            val w = endX - startX
                            val h = endY - startY
                            val side = max(abs(w), abs(h))
                            val maxSideX = if (w > 0) bounds.right - startX else startX - bounds.left
                            val maxSideY = if (h > 0) bounds.bottom - startY else startY - bounds.top
                            val constrainedSide = min(side, min(abs(maxSideX), abs(maxSideY)))
                            endX = startX + sign(w) * constrainedSide
                            endY = startY + sign(h) * constrainedSide
                        }
                    }
                }
                invalidate()
                onRoiChangedListener?.invoke(getRelativeRoi())
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    if (currentMode == RoiMode.FREEFORM) {
                        freeformPath.close()
                    } else {
                        roiRect.set(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
                    }
                    isDrawing = false
                }
                touchState = TouchState.NONE
                hasValidRoi = true
                invalidate()
                onRoiChangedListener?.invoke(getRelativeRoi())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isSquareMode() = (currentMode == RoiMode.SQUARE || currentMode == RoiMode.CIRCLE)

    private fun makeSquare(pivotX: Float, pivotY: Float, bounds: RectF) {
        val currentW = abs(roiRect.right - roiRect.left)
        val currentH = abs(roiRect.bottom - roiRect.top)
        val desiredSide = max(currentW, currentH)

        val growLeft = roiRect.left != pivotX && roiRect.left < pivotX
        val growRight = roiRect.right != pivotX && roiRect.right > pivotX
        val growTop = roiRect.top != pivotY && roiRect.top < pivotY
        val growBottom = roiRect.bottom != pivotY && roiRect.bottom > pivotY

        var maxSide = desiredSide
        if (growLeft) maxSide = min(maxSide, pivotX - bounds.left)
        if (growRight) maxSide = min(maxSide, bounds.right - pivotX)
        if (growTop) maxSide = min(maxSide, pivotY - bounds.top)
        if (growBottom) maxSide = min(maxSide, bounds.bottom - pivotY)

        val finalSide = max(maxSide, minSize)
        val newLeft = if (growLeft) pivotX - finalSide else pivotX
        val newRight = if (growRight) pivotX + finalSide else pivotX
        val newTop = if (growTop) pivotY - finalSide else pivotY
        val newBottom = if (growBottom) pivotY + finalSide else pivotY

        roiRect.set(
            newLeft.coerceIn(bounds.left, bounds.right),
            newTop.coerceIn(bounds.top, bounds.bottom),
            newRight.coerceIn(bounds.left, bounds.right),
            newBottom.coerceIn(bounds.top, bounds.bottom)
        )
    }

    private fun getTouchState(x: Float, y: Float): TouchState {
        val slop = 60f
        if (abs(x - roiRect.left) < slop && abs(y - roiRect.top) < slop) return TouchState.TOP_LEFT
        if (abs(x - roiRect.right) < slop && abs(y - roiRect.top) < slop) return TouchState.TOP_RIGHT
        if (abs(x - roiRect.left) < slop && abs(y - roiRect.bottom) < slop) return TouchState.BOTTOM_LEFT
        if (abs(x - roiRect.right) < slop && abs(y - roiRect.bottom) < slop) return TouchState.BOTTOM_RIGHT
        if (roiRect.contains(x, y)) return TouchState.CENTER
        return TouchState.NONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDrawing && !hasValidRoi) return

        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        val useRoiRect = (hasValidRoi && !isDrawing)
        val drawRect = if (useRoiRect) roiRect else RectF(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))

        when (currentMode) {
            RoiMode.RECTANGLE, RoiMode.SQUARE -> {
                canvas.drawRect(drawRect, clearPaint)
                canvas.drawRect(drawRect, borderPaint)
            }
            RoiMode.CIRCLE, RoiMode.ELLIPSE -> {
                canvas.drawOval(drawRect, clearPaint)
                canvas.drawOval(drawRect, borderPaint)
            }
            RoiMode.FREEFORM -> {
                canvas.drawPath(freeformPath, clearPaint)
                canvas.drawPath(freeformPath, borderPaint)
            }
        }

        if ((hasValidRoi || isDrawing) && currentMode != RoiMode.FREEFORM) {
            val r = 20f
            canvas.drawCircle(drawRect.left, drawRect.top, r, handlePaint)
            canvas.drawCircle(drawRect.right, drawRect.top, r, handlePaint)
            canvas.drawCircle(drawRect.left, drawRect.bottom, r, handlePaint)
            canvas.drawCircle(drawRect.right, drawRect.bottom, r, handlePaint)
        }
        canvas.restoreToCount(layerId)
    }

    fun getRelativeRoi(): RectF {
        if (imageBounds.isEmpty || imageBounds.width() == 0f) return RectF()

        // 🚀 Use the physical image scale if available, otherwise use preview scale
        val scale = if (realImageWidth > 0) realImageWidth.toFloat() / imageBounds.width()
        else (imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f) / imageBounds.width()

        return RectF(
            (roiRect.left - imageBounds.left) * scale,
            (roiRect.top - imageBounds.top) * scale,
            (roiRect.right - imageBounds.left) * scale,
            (roiRect.bottom - imageBounds.top) * scale
        )
    }
}