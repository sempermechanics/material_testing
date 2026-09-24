package com.indicvision.semper.ui.analysis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import com.indicvision.semper.report.BeamDeflection
import kotlin.math.max

/**
 * Draws the bending edge taps over the reference in [BeamEdgeTapActivity]:
 * a crosshair on each tapped edge, the line between them, and the circle the
 * deflection is averaged over. Points are in true reference pixels;
 * [imageToView] (the photo's zoom matrix) places them on screen. Never takes
 * touches — the photo under it does.
 */
class BeamEdgeTapOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var imageToView: Matrix = Matrix()
        set(value) {
            field = value
            invalidate()
        }

    var top: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    var bottom: PointF? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        color = MARK_COLOR
    }
    private val halo = Paint(mark).apply {
        strokeWidth = HALO_DP * density
        color = HALO_COLOR
    }
    private val probe = Paint(mark).apply {
        color = PROBE_COLOR
        pathEffect = DashPathEffect(floatArrayOf(DASH_DP * density, DASH_DP * density), 0f)
    }

    private val pt = FloatArray(2)

    private fun toView(p: PointF): PointF {
        pt[0] = p.x
        pt[1] = p.y
        imageToView.mapPoints(pt)
        return PointF(pt[0], pt[1])
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val topImage = top
        val bottomImage = bottom
        if (topImage != null && bottomImage != null) span(canvas, topImage, bottomImage)
        topImage?.let { cross(canvas, toView(it)) }
        bottomImage?.let { cross(canvas, toView(it)) }
    }

    /** The thickness line and the probe circle, sized in image px like [BeamDeflection.Probe]. */
    private fun span(canvas: Canvas, topImage: PointF, bottomImage: PointF) {
        val t = toView(topImage)
        val b = toView(bottomImage)
        canvas.drawLine(t.x, t.y, b.x, b.y, halo)
        canvas.drawLine(t.x, t.y, b.x, b.y, mark)
        val spanView = PointF.length(b.x - t.x, b.y - t.y)
        val spanImage = PointF.length(bottomImage.x - topImage.x, bottomImage.y - topImage.y)
        val scale = if (spanImage > 0f) spanView / spanImage else 1f
        val radius = max(spanImage / 2f, BeamDeflection.MIN_PROBE_RADIUS_PX) * scale
        canvas.drawCircle((t.x + b.x) / 2f, (t.y + b.y) / 2f, radius, probe)
    }

    private fun cross(canvas: Canvas, c: PointF) {
        val arm = ARM_DP * density
        for (paint in listOf(halo, mark)) {
            canvas.drawLine(c.x - arm, c.y, c.x + arm, c.y, paint)
            canvas.drawLine(c.x, c.y - arm, c.x, c.y + arm, paint)
        }
    }

    private companion object {
        const val STROKE_DP = 1.5f
        const val HALO_DP = 3.5f
        const val ARM_DP = 14f
        const val DASH_DP = 4f
        val MARK_COLOR = Color.rgb(0x38, 0xBD, 0xF8)
        val HALO_COLOR = Color.argb(0xAA, 0, 0, 0)
        val PROBE_COLOR = Color.rgb(0xFB, 0xBF, 0x24)
    }
}
