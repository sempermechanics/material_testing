package com.sempermechanics.semper.ui.analysis.sweep

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.withClip
import com.sempermechanics.semper.ui.common.dp

/**
 * The [SweepPlotView.Mark]s of a plot (the tensile yield point, say): a diamond
 * at each mark inside the viewport, and a legend for them in the plot's
 * bottom-right corner, which a rising curve leaves empty. A mark outside the
 * axes is not drawn and does not widen them.
 */
internal class SweepPlotMarks(private val plot: View) {

    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** A mark's outline, so it stands off the curve under it. */
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = plot.dp(RING_WIDTH_DP)
        color = PlotStyle.inkStrong(plot.context)
    }

    /**
     * Draws the [marks] inside [b] on [frame]; [sx] and [sy] map data units to
     * view pixels, and [legendPaint] writes the legend beside [rowTextPaint]'s rows.
     */
    @Suppress("LongParameterList") // the plot's frame, its mapping and its two paints
    fun draw(
        canvas: Canvas,
        marks: List<SweepPlotView.Mark>,
        b: PlotBounds,
        frame: RectF,
        sx: (Float) -> Float,
        sy: (Float) -> Float,
        legendPaint: Paint,
        rowTextPaint: Paint,
    ) {
        val shown = marks.filter { it.x in b.xMin..b.xMax && it.y in b.yMin..b.yMax }
        if (shown.isEmpty()) return
        val r = plot.dp(MARK_RADIUS_DP)
        val gap = plot.dp(SweepPlotView.TICK_GAP_DP)
        canvas.withClip(frame) { shown.forEach { diamond(this, sx(it.x), sy(it.y), r, it.color) } }
        val rowHeight = maxOf(rowTextPaint.textSize, r * 2f) + gap
        var baseline = frame.bottom - gap - rowHeight * (shown.size - 1)
        legendPaint.textAlign = Paint.Align.RIGHT
        shown.forEach {
            val textRight = frame.right - gap
            canvas.drawText(it.label, textRight, baseline, legendPaint)
            val cx = textRight - legendPaint.measureText(it.label) - gap - r
            diamond(canvas, cx, baseline - legendPaint.textSize * SweepPlotView.TICK_BASELINE, r, it.color)
            baseline += rowHeight
        }
    }

    private fun diamond(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        path.reset()
        path.moveTo(cx, cy - r)
        path.lineTo(cx + r, cy)
        path.lineTo(cx, cy + r)
        path.lineTo(cx - r, cy)
        path.close()
        fill.color = color
        canvas.drawPath(path, fill)
        canvas.drawPath(path, ring)
    }

    companion object {
        /** Half the diagonal of a mark's diamond. */
        const val MARK_RADIUS_DP = 6f
        private const val RING_WIDTH_DP = 1.5f
    }
}
