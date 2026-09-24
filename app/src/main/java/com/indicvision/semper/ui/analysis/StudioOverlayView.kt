// Custom ROI overlay view: coordinate mapping, gesture/hit-testing and mask
// serialization. Complexity is inherent; suppress rather than baseline so new
// findings elsewhere still fail CI.

@file:Suppress(
    "TooManyFunctions",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
)
@file:SuppressLint("ClickableViewAccessibility")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.toColorInt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class StudioOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // Store the actual image dimensions for perfect coordinate scaling
    var realImageWidth: Int = 0
    var realImageHeight: Int = 0

    // --- 1. IMAGE BOUNDARY TRACKING ---
    private var imageBounds = RectF()

    /** Reused every draw: the drag rect is rebuilt on each touch move. */
    private val activeHoleScratch = RectF()

    /** Reused every draw for the main ROI while it is dragged or implied. */
    private val mainRectScratch = RectF()
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

        // Remap live geometry when letterboxing changes (toolbar/IME resize).
        val liveRoi = if (!imageBounds.isEmpty && hasValidRoi && pendingRestoreRoi == null) {
            getRelativeRoi()
        } else {
            null
        }
        val liveHoles = if (!imageBounds.isEmpty && holes.isNotEmpty()) {
            holes.map { it.mode to viewRectToImage(it.rect) }
        } else {
            emptyList()
        }

        imageBounds.set(left, top, left + scaledWidth, top + scaledHeight)

        if (liveRoi != null && liveRoi.width() > 0f && liveRoi.height() > 0f) {
            pendingRestoreRoi = liveRoi
        }
        applyPendingRestore()

        if (liveHoles.isNotEmpty()) {
            holes.clear()
            for ((mode, img) in liveHoles) {
                val mapped = mapImageRectToView(
                    img.left.toInt(),
                    img.top.toInt(),
                    img.width().toInt().coerceAtLeast(1),
                    img.height().toInt().coerceAtLeast(1),
                )
                if (mapped != null) {
                    holes.add(Hole(mode, Path(), mapped))
                }
            }
            invalidate()
        }
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

    /**
     * Places the main crop from image-pixel [x], [y], [width], [height].
     * Clamps to the image; returns false if the size is non-positive or bounds
     * are not ready yet.
     */
    fun applyImageRoi(x: Int, y: Int, width: Int, height: Int): Boolean {
        val mapped = mapImageRectToView(x, y, width, height) ?: return false
        roiRect.set(mapped)
        hasValidRoi = true
        mainRoiMode = when (currentMode) {
            RoiMode.SQUARE -> RoiMode.SQUARE
            else -> RoiMode.RECTANGLE
        }
        invalidate()
        onRoiChangedListener?.invoke(getRelativeRoi())
        return true
    }

    /**
     * Adds an erase (hole) rect from image-pixel [x], [y], [width], [height].
     * Same clamp rules as [applyImageRoi].
     */
    fun applyImageHole(x: Int, y: Int, width: Int, height: Int): Boolean {
        val mapped = mapImageRectToView(x, y, width, height) ?: return false
        val mode = when (currentMode) {
            RoiMode.SQUARE -> RoiMode.SQUARE
            else -> RoiMode.RECTANGLE
        }
        holes.add(Hole(mode, Path(), RectF(mapped)))
        invalidate()
        onRoiChangedListener?.invoke(getRelativeRoi())
        return true
    }

    /** Image-pixel bounds of the last erase rect, or empty if there are none. */
    fun lastHoleRelative(): RectF {
        val hole = holes.lastOrNull() ?: return RectF()
        return viewRectToImage(hole.rect)
    }

    private fun mapImageRectToView(x: Int, y: Int, width: Int, height: Int): RectF? {
        if (realImageWidth <= 0 || realImageHeight <= 0 || imageBounds.isEmpty) return null
        if (width <= 0 || height <= 0) return null

        val leftPx = x.coerceIn(0, realImageWidth - 1)
        val topPx = y.coerceIn(0, realImageHeight - 1)
        val rightPx = (leftPx + width).coerceAtMost(realImageWidth)
        val bottomPx = (topPx + height).coerceAtMost(realImageHeight)
        if (rightPx <= leftPx || bottomPx <= topPx) return null

        val scaleX = imageBounds.width() / realImageWidth.toFloat()
        val scaleY = imageBounds.height() / realImageHeight.toFloat()
        return RectF(
            imageBounds.left + leftPx * scaleX,
            imageBounds.top + topPx * scaleY,
            imageBounds.left + rightPx * scaleX,
            imageBounds.top + bottomPx * scaleY,
        )
    }

    private fun viewRectToImage(viewRect: RectF): RectF {
        if (imageBounds.isEmpty || imageBounds.width() == 0f) return RectF()
        val scale = if (realImageWidth > 0) {
            realImageWidth.toFloat() / imageBounds.width()
        } else {
            (imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f) / imageBounds.width()
        }
        return RectF(
            (viewRect.left - imageBounds.left) * scale,
            (viewRect.top - imageBounds.top) * scale,
            (viewRect.right - imageBounds.left) * scale,
            (viewRect.bottom - imageBounds.top) * scale,
        )
    }

    private fun applyPendingRestore() {
        pendingRestoreRoi?.let { saved ->
            // Map the physical image coordinates back to the scaled screen view
            val scale = if (realImageWidth > 0) {
                realImageWidth.toFloat() / imageBounds.width()
            } else {
                (imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f) / imageBounds.width()
            }

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
        holes.clear()
        touchState = TouchState.NONE
        activeHoleIndex = -1 // Prevent stale index crash!
        invalidate()
        onRoiChangedListener?.invoke(RectF())
        mainFreeformPath.reset()
    }

    enum class RoiMode { RECTANGLE, SQUARE, CIRCLE, ELLIPSE, FREEFORM }
    var currentMode = RoiMode.RECTANGLE

    // Hole Tracking Variables
    var isSubtractMode = false
    data class Hole(val mode: RoiMode, val path: Path, val rect: RectF)
    val holes = mutableListOf<Hole>()

    private val holeFillPaint = Paint().apply {
        color = "#88FF0000".toColorInt()
        style = Paint.Style.FILL
    }
    private val holeBorderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    var hasValidRoi = false
        private set

    private var roiRect = RectF()
    private val minSize = 50f

    // Permanently remember the Main ROI's shape and path!
    private var mainRoiMode = RoiMode.RECTANGLE
    private val mainFreeformPath = Path()

    // Drawing variables

    // Drawing variables
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val freeformPath = Path()
    private var isDrawing = false

    private enum class TouchState { NONE, CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var touchState = TouchState.NONE

    private val borderPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val handlePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val dimPaint = Paint().apply {
        color = "#99000000".toColorInt()
        style = Paint.Style.FILL
    }
    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
    }
    private fun safeCoerce(value: Float, min: Float, max: Float): Float {
        val actualMax = if (max < min) min else max
        return value.coerceIn(min, actualMax)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bounds = if (imageBounds.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else imageBounds
        val x = event.x.coerceIn(bounds.left, bounds.right)
        val y = event.y.coerceIn(bounds.top, bounds.bottom)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchState = getTouchState(x, y)
                if (touchState != TouchState.NONE) {
                    lastX = x
                    lastY = y
                    return true
                }

                if (!isSubtractMode) {
                    holes.clear()
                    roiRect.setEmpty()
                    hasValidRoi = false
                }

                isDrawing = true
                startX = x
                startY = y
                endX = x
                endY = y
                freeformPath.reset()
                if (currentMode == RoiMode.FREEFORM) freeformPath.moveTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchState != TouchState.NONE) {
                    val dx = x - lastX
                    val dy = y - lastY
                    // DYNAMIC TARGET: Modifies either the grabbed hole OR the main ROI
                    val target = if (activeHoleIndex >= 0) holes[activeHoleIndex].rect else roiRect

                    when (touchState) {
                        TouchState.CENTER -> {
                            val newLeft = safeCoerce(target.left + dx, bounds.left, bounds.right - target.width())
                            val newTop = safeCoerce(target.top + dy, bounds.top, bounds.bottom - target.height())
                            target.offsetTo(newLeft, newTop)
                        }
                        TouchState.TOP_LEFT -> {
                            target.left = safeCoerce(target.left + dx, bounds.left, target.right - minSize)
                            target.top = safeCoerce(target.top + dy, bounds.top, target.bottom - minSize)
                            if (isSquareMode()) makeSquare(target.right, target.bottom, bounds, target)
                        }
                        TouchState.TOP_RIGHT -> {
                            target.right = safeCoerce(target.right + dx, target.left + minSize, bounds.right)
                            target.top = safeCoerce(target.top + dy, bounds.top, target.bottom - minSize)
                            if (isSquareMode()) makeSquare(target.left, target.bottom, bounds, target)
                        }
                        TouchState.BOTTOM_LEFT -> {
                            target.left = safeCoerce(target.left + dx, bounds.left, target.right - minSize)
                            target.bottom = safeCoerce(target.bottom + dy, target.top + minSize, bounds.bottom)
                            if (isSquareMode()) makeSquare(target.right, target.top, bounds, target)
                        }
                        TouchState.BOTTOM_RIGHT -> {
                            target.right = safeCoerce(target.right + dx, target.left + minSize, bounds.right)
                            target.bottom = safeCoerce(target.bottom + dy, target.top + minSize, bounds.bottom)
                            if (isSquareMode()) makeSquare(target.left, target.top, bounds, target)
                        }
                        else -> {}
                    }
                    lastX = x
                    lastY = y
                } else if (isDrawing) {
                    endX = x
                    endY = y
                    if (currentMode == RoiMode.FREEFORM) freeformPath.lineTo(x, y)
                }
                invalidate()
                onRoiChangedListener?.invoke(getRelativeRoi())
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    if (currentMode == RoiMode.FREEFORM) freeformPath.close()
                    val rect = RectF(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))

                    if (isSubtractMode) {
                        // ARCHITECTURE FIX: Removed 'hasValidRoi' so you can punch holes in the Full Image
                        if (rect.width() > 50f || rect.height() > 50f || currentMode == RoiMode.FREEFORM) {
                            holes.add(Hole(currentMode, Path(freeformPath), rect))
                        }
                    } else {
                        if (rect.width() > 50f || rect.height() > 50f) {
                            roiRect.set(rect)
                            hasValidRoi = true
                            mainRoiMode = currentMode
                            if (currentMode == RoiMode.FREEFORM) mainFreeformPath.set(freeformPath)
                        }
                    }
                    isDrawing = false
                }
                touchState = TouchState.NONE
                activeHoleIndex = -1
                invalidate()
                onRoiChangedListener?.invoke(getRelativeRoi())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isSquareMode() = (currentMode == RoiMode.SQUARE || currentMode == RoiMode.CIRCLE)

    private var activeHoleIndex = -1 // Tracks which hole you grabbed

    private fun makeSquare(pivotX: Float, pivotY: Float, bounds: RectF, targetRect: RectF) {
        val currentW = abs(targetRect.right - targetRect.left)
        val currentH = abs(targetRect.bottom - targetRect.top)
        val desiredSide = max(currentW, currentH)

        val growLeft = targetRect.left != pivotX && targetRect.left < pivotX
        val growRight = targetRect.right != pivotX && targetRect.right > pivotX
        val growTop = targetRect.top != pivotY && targetRect.top < pivotY
        val growBottom = targetRect.bottom != pivotY && targetRect.bottom > pivotY

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

        targetRect.set(
            newLeft.coerceIn(bounds.left, bounds.right),
            newTop.coerceIn(bounds.top, bounds.bottom),
            newRight.coerceIn(bounds.left, bounds.right),
            newBottom.coerceIn(bounds.top, bounds.bottom),
        )
    }

    private fun getTouchState(x: Float, y: Float): TouchState {
        val slop = 45f // Massive hitboxes for precision resizing
        activeHoleIndex = -1

        // 1. Check Holes First (allows resizing holes drawn over the main ROI)
        if (isSubtractMode) {
            for (i in holes.indices.reversed()) {
                val hr = holes[i].rect
                if (abs(x - hr.left) < slop && abs(y - hr.top) < slop) {
                    activeHoleIndex = i
                    return TouchState.TOP_LEFT
                }
                if (abs(x - hr.right) < slop && abs(y - hr.top) < slop) {
                    activeHoleIndex = i
                    return TouchState.TOP_RIGHT
                }
                if (abs(x - hr.left) < slop && abs(y - hr.bottom) < slop) {
                    activeHoleIndex = i
                    return TouchState.BOTTOM_LEFT
                }
                if (abs(x - hr.right) < slop && abs(y - hr.bottom) < slop) {
                    activeHoleIndex = i
                    return TouchState.BOTTOM_RIGHT
                }
                if (hr.contains(x, y)) {
                    activeHoleIndex = i
                    return TouchState.CENTER
                }
            }
        }

        // 2. Check Main ROI Second
        if (hasValidRoi && !isSubtractMode) {
            if (abs(x - roiRect.left) < slop && abs(y - roiRect.top) < slop) return TouchState.TOP_LEFT
            if (abs(x - roiRect.right) < slop && abs(y - roiRect.top) < slop) return TouchState.TOP_RIGHT
            if (abs(x - roiRect.left) < slop && abs(y - roiRect.bottom) < slop) return TouchState.BOTTOM_LEFT
            if (abs(x - roiRect.right) < slop && abs(y - roiRect.bottom) < slop) return TouchState.BOTTOM_RIGHT
            if (roiRect.contains(x, y)) return TouchState.CENTER
        }

        return TouchState.NONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // BUG FIX: Don't return early if we have holes but no main ROI!
        if (!isDrawing && !hasValidRoi && holes.isEmpty()) return

        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // --- 1. DRAW THE MAIN ROI ---
        // Holes with no explicit crop still mean "full image minus holes". Clear
        // imageBounds so the specimen stays visible instead of staying fully dimmed.
        val implicitFullImage = !hasValidRoi && (holes.isNotEmpty() || (isDrawing && isSubtractMode))
        if (hasValidRoi || (!isSubtractMode && isDrawing) || implicitFullImage) {
            val drawMainRect = when {
                !isSubtractMode && isDrawing -> mainRectScratch.apply {
                    set(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
                }
                hasValidRoi -> roiRect
                else -> mainRectScratch.apply { set(imageBounds) }
            }

            // Use current mode if actively drawing, otherwise use the saved mode
            val modeToUse = if (!isSubtractMode && isDrawing) currentMode else mainRoiMode
            val pathToUse = if (!isSubtractMode && isDrawing) freeformPath else mainFreeformPath

            when (modeToUse) {
                RoiMode.RECTANGLE, RoiMode.SQUARE -> {
                    canvas.drawRect(drawMainRect, clearPaint)
                    if (hasValidRoi || (!isSubtractMode && isDrawing)) {
                        canvas.drawRect(drawMainRect, borderPaint)
                    }
                }
                RoiMode.CIRCLE, RoiMode.ELLIPSE -> {
                    canvas.drawOval(drawMainRect, clearPaint)
                    if (hasValidRoi || (!isSubtractMode && isDrawing)) {
                        canvas.drawOval(drawMainRect, borderPaint)
                    }
                }
                RoiMode.FREEFORM -> {
                    canvas.drawPath(pathToUse, clearPaint)
                    if (hasValidRoi || (!isSubtractMode && isDrawing)) {
                        canvas.drawPath(pathToUse, borderPaint)
                    }
                }
            }

            // Draw Main ROI Handles (Only in Add Mode)
            if (!isSubtractMode && modeToUse != RoiMode.FREEFORM && (hasValidRoi || isDrawing)) {
                val r = 20f
                canvas.drawCircle(drawMainRect.left, drawMainRect.top, r, handlePaint)
                canvas.drawCircle(drawMainRect.right, drawMainRect.top, r, handlePaint)
                canvas.drawCircle(drawMainRect.left, drawMainRect.bottom, r, handlePaint)
                canvas.drawCircle(drawMainRect.right, drawMainRect.bottom, r, handlePaint)
            }
        }

        // --- 2. DRAW SAVED HOLES ---
        for (hole in holes) {
            when (hole.mode) {
                RoiMode.RECTANGLE, RoiMode.SQUARE -> {
                    canvas.drawRect(hole.rect, holeFillPaint)
                    canvas.drawRect(hole.rect, holeBorderPaint)
                }
                RoiMode.CIRCLE, RoiMode.ELLIPSE -> {
                    canvas.drawOval(hole.rect, holeFillPaint)
                    canvas.drawOval(hole.rect, holeBorderPaint)
                }
                RoiMode.FREEFORM -> {
                    canvas.drawPath(hole.path, holeFillPaint)
                    canvas.drawPath(hole.path, holeBorderPaint)
                }
            }
            // Draw handles for holes if we are in Erase mode to show they are editable
            if (isSubtractMode && !isDrawing && hole.mode != RoiMode.FREEFORM) {
                val r = 15f
                canvas.drawCircle(hole.rect.left, hole.rect.top, r, handlePaint)
                canvas.drawCircle(hole.rect.right, hole.rect.top, r, handlePaint)
                canvas.drawCircle(hole.rect.left, hole.rect.bottom, r, handlePaint)
                canvas.drawCircle(hole.rect.right, hole.rect.bottom, r, handlePaint)
            }
        }

        // --- 3. DRAW ACTIVE HOLE BEING DRAGGED ---
        if (isDrawing && isSubtractMode) {
            val activeHoleRect = activeHoleScratch.apply {
                set(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY))
            }
            when (currentMode) {
                RoiMode.RECTANGLE, RoiMode.SQUARE -> {
                    canvas.drawRect(activeHoleRect, holeFillPaint)
                    canvas.drawRect(activeHoleRect, holeBorderPaint)
                }
                RoiMode.CIRCLE, RoiMode.ELLIPSE -> {
                    canvas.drawOval(activeHoleRect, holeFillPaint)
                    canvas.drawOval(activeHoleRect, holeBorderPaint)
                }
                RoiMode.FREEFORM -> {
                    canvas.drawPath(freeformPath, holeFillPaint)
                    canvas.drawPath(freeformPath, holeBorderPaint)
                }
            }
        }

        canvas.restoreToCount(layerId)
    }

    fun getRelativeRoi(): RectF {
        if (imageBounds.isEmpty || imageBounds.width() == 0f) return RectF()

        // Use the physical image scale if available, otherwise use preview scale
        val scale = if (realImageWidth > 0) {
            realImageWidth.toFloat() / imageBounds.width()
        } else {
            (imageView?.drawable?.intrinsicWidth?.toFloat() ?: 1f) / imageBounds.width()
        }

        return RectF(
            (roiRect.left - imageBounds.left) * scale,
            (roiRect.top - imageBounds.top) * scale,
            (roiRect.right - imageBounds.left) * scale,
            (roiRect.bottom - imageBounds.top) * scale,
        )
    }

    // OOM FIX: Generate Raw ALPHA_8 bytes
    fun generateMaskBytes(): ByteArray = StudioOverlayMaskEncoder.encode(
        StudioOverlayMaskEncoder.Input(
            realImageWidth = realImageWidth,
            realImageHeight = realImageHeight,
            imageBounds = imageBounds,
            hasValidRoi = hasValidRoi,
            roiRect = roiRect,
            mainRoiMode = mainRoiMode,
            mainFreeformPath = mainFreeformPath,
            holes = holes.toList(),
        ),
    )
}
