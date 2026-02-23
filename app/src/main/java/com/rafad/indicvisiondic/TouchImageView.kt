package com.rafad.indicvisiondic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class TouchImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private var matrix = Matrix()
    private var mode = 0 // 0: None, 1: Drag, 2: Zoom
    private var last = PointF()
    private var start = PointF()
    private var minScale = 1f
    private var maxScale = 5f
    private var m: FloatArray = FloatArray(9)
    private var viewWidth = 0
    private var viewHeight = 0
    private var saveScale = 1f
    private var mScaleDetector: ScaleGestureDetector

    // Callback to tell the heatmap to update its position
    var onMatrixChangedListener: (() -> Unit)? = null

    init {
        super.setClickable(true)
        mScaleDetector = ScaleGestureDetector(context, ScaleListener())
        scaleType = ScaleType.MATRIX

        setOnTouchListener { _, event ->
            mScaleDetector.onTouchEvent(event)
            val curr = PointF(event.x, event.y)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    last.set(curr)
                    start.set(last)
                    mode = 1
                }
                MotionEvent.ACTION_MOVE -> if (mode == 1) {
                    val deltaX = curr.x - last.x
                    val deltaY = curr.y - last.y
                    matrix.postTranslate(deltaX, deltaY)
                    fixTrans()
                    last.set(curr.x, curr.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = 0
                }
            }
            imageMatrix = matrix
            invalidate()
            onMatrixChangedListener?.invoke() // Broadcast the movement!
            true
        }
    }

    // --- CRITICAL OVERRIDE ---
    // This tells the view to fit the image to the screen the moment it is loaded!
    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post {
            if (viewWidth > 0 && viewHeight > 0) {
                fitToScreen()
            }
        }
    }

    // Called when the layout is drawn on the screen
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewWidth = w
        viewHeight = h
        fitToScreen()
    }

    // Calculates the "fitCenter" scale automatically
    fun fitToScreen() {
        val d = drawable ?: return
        val drawableWidth = d.intrinsicWidth.toFloat()
        val drawableHeight = d.intrinsicHeight.toFloat()

        // Safety check to prevent math errors that make the image disappear
        if (drawableWidth <= 0f || drawableHeight <= 0f || viewWidth <= 0 || viewHeight <= 0) return

        val scaleX = viewWidth.toFloat() / drawableWidth
        val scaleY = viewHeight.toFloat() / drawableHeight
        val scale = min(scaleX, scaleY)

        matrix.setScale(scale, scale)

        // Center the image within the view
        val redundantYSpace = viewHeight.toFloat() - (scale * drawableHeight)
        val redundantXSpace = viewWidth.toFloat() - (scale * drawableWidth)
        matrix.postTranslate(redundantXSpace / 2f, redundantYSpace / 2f)

        minScale = scale
        maxScale = scale * 5f // Allow 5x zoom from the fit size
        saveScale = scale

        imageMatrix = matrix
        invalidate()
        onMatrixChangedListener?.invoke() // Force the heatmap to align on load
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            mode = 2
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            var mScaleFactor = detector.scaleFactor
            val origScale = saveScale
            saveScale *= mScaleFactor
            if (saveScale > maxScale) {
                saveScale = maxScale
                mScaleFactor = maxScale / origScale
            } else if (saveScale < minScale) {
                saveScale = minScale
                mScaleFactor = minScale / origScale
            }

            if (viewWidth == 0 || viewHeight == 0) return false

            matrix.postScale(mScaleFactor, mScaleFactor, detector.focusX, detector.focusY)
            fixTrans()
            return true
        }
    }

    private fun fixTrans() {
        matrix.getValues(m)
        val transX = m[Matrix.MTRANS_X]
        val transY = m[Matrix.MTRANS_Y]

        val d = drawable ?: return
        val fixTransX = getFixTrans(transX, viewWidth.toFloat(), d.intrinsicWidth * saveScale)
        val fixTransY = getFixTrans(transY, viewHeight.toFloat(), d.intrinsicHeight * saveScale)

        if (fixTransX != 0f || fixTransY != 0f) {
            matrix.postTranslate(fixTransX, fixTransY)
        }
    }

    private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
        val minTrans: Float
        val maxTrans: Float
        if (contentSize <= viewSize) {
            minTrans = (viewSize - contentSize) / 2f
            maxTrans = (viewSize - contentSize) / 2f
        } else {
            minTrans = viewSize - contentSize
            maxTrans = 0f
        }
        if (trans < minTrans) return -trans + minTrans
        if (trans > maxTrans) return -trans + maxTrans
        return 0f
    }

    fun getZoomMatrix(): Matrix = matrix
}