package com.sempermechanics.semper.ui.analysis.load

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.report.BeamDeflection
import com.sempermechanics.semper.ui.analysis.BeamEdgeTapActivity
import kotlin.math.max

/**
 * Draws the bending edge taps over the reference in [BeamEdgeTapActivity]:
 * full-length crosshair lines through each tapped edge, the thickness between
 * them, and the circle the deflection is averaged over. A lone mark's lines
 * are the image's axes; once both are placed they turn with the tap line, one
 * along it and one square to it, which is laid along the edge — so on a beam
 * tilted in the photo the pair is square to the beam when both of those lie
 * on its edges. A pair one above the other draws exactly the axis lines. Points are in true reference pixels;
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
    private val markColor = ContextCompat.getColor(context, R.color.beam_tap_mark)
    private val haloColor = ContextCompat.getColor(context, R.color.beam_tap_halo)

    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GUIDE_DP * density
        color = markColor
    }
    private val guideHalo = Paint(guide).apply {
        strokeWidth = GUIDE_HALO_DP * density
        color = haloColor
    }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_DP * density
        color = markColor
    }
    private val halo = Paint(mark).apply {
        strokeWidth = HALO_DP * density
        color = haloColor
    }
    private val probe = Paint(mark).apply {
        color = ContextCompat.getColor(context, R.color.beam_tap_probe)
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
        // The tap line's direction on screen; the image's vertical until both are placed.
        var alongX = 0f
        var alongY = 1f
        if (topImage != null && bottomImage != null) {
            span(canvas, topImage, bottomImage)
            val t = toView(topImage)
            val b = toView(bottomImage)
            val length = PointF.length(b.x - t.x, b.y - t.y)
            if (length > 0f) {
                alongX = (b.x - t.x) / length
                alongY = (b.y - t.y) / length
            }
        }
        topImage?.let { cross(canvas, toView(it), alongX, alongY) }
        bottomImage?.let { cross(canvas, toView(it), alongX, alongY) }
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

    /**
     * Crosshair lines across the whole view, one along ([alongX], [alongY])
     * and one square to it, and a ring on the mark itself.
     */
    private fun cross(canvas: Canvas, c: PointF, alongX: Float, alongY: Float) {
        // Long enough to cross the view from any point in it, at any angle.
        val reach = PointF.length(width.toFloat(), height.toFloat())
        val ax = alongX * reach
        val ay = alongY * reach
        for (paint in listOf(guideHalo, guide)) {
            canvas.drawLine(c.x + ay, c.y - ax, c.x - ay, c.y + ax, paint)
            canvas.drawLine(c.x - ax, c.y - ay, c.x + ax, c.y + ay, paint)
        }
        val ring = RING_DP * density
        canvas.drawCircle(c.x, c.y, ring, halo)
        canvas.drawCircle(c.x, c.y, ring, mark)
    }

    private companion object {
        const val STROKE_DP = 1.5f
        const val HALO_DP = 3.5f
        const val GUIDE_DP = 1f
        const val GUIDE_HALO_DP = 2.5f
        const val RING_DP = 6f
        const val DASH_DP = 4f
    }
}
