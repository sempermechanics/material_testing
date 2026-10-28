package com.sempermechanics.semper.ui.viewer.inspect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.style.TextAppearanceSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.FieldHistogram
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.ui.common.dp

/**
 * Gray histogram of one field's accepted values. No grid, no top or right spine.
 * X runs from the data min to the data max; Y runs from 0 to the tallest bin.
 */
class FieldHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onBinSelected: ((Int) -> Unit)? = null

    private var histogram: FieldHistogram? = null
    private var unit: String = ""
    private var selectedBin: Int = -1

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    // The sheet's caption (TextAppearance.Semper.Caption), not monospace (readiness AE1V-025);
    // its colour is the plot ink, set in onDraw.
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        TextAppearanceSpan(context, R.style.TextAppearance_Semper_Caption).updateMeasureState(it)
    }
    private val barRect = RectF()

    /** The plot area, from [framePlot]. */
    private val plot = RectF()

    init {
        isClickable = true
    }

    fun setHistogram(histogram: FieldHistogram, unit: String) {
        this.histogram = histogram
        this.unit = unit
        selectedBin = -1
        contentDescription = context.resources.getQuantityString(
            R.plurals.viewer_histogram_desc_fmt,
            histogram.n,
            histogram.n,
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val hist = histogram
        val peak = hist?.counts?.maxOrNull() ?: 0
        if (hist == null || peak <= 0) return

        val muted = ContextCompat.getColor(context, R.color.viewer_plot_ink)
        val strong = ContextCompat.getColor(context, R.color.viewer_plot_ink_strong)
        axisPaint.color = muted
        textPaint.color = muted

        if (!framePlot(hist, width, height)) return
        val left = plot.left
        val right = plot.right
        val top = plot.top
        val bottom = plot.bottom

        val k = hist.binCount
        val innerW = right - left
        val gap = if (innerW / k > dp(2f)) dp(1f) else 0f
        val barW = (innerW - gap * (k - 1).coerceAtLeast(0)) / k
        val plotH = bottom - top

        for (i in 0 until k) {
            val h = hist.counts[i].toFloat() / peak * plotH
            val x = left + i * (barW + gap)
            barPaint.color = if (i == selectedBin) strong else muted
            barRect.set(x, bottom - h, x + barW, bottom)
            canvas.drawRect(barRect, barPaint)
        }

        canvas.drawLine(left, bottom, right, bottom, axisPaint)
        canvas.drawLine(left, bottom, left, top, axisPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        val tickGap = dp(TICK_GAP_DP)
        canvas.drawText("0", left - tickGap, bottom + textPaint.textSize * TICK_BASELINE, textPaint)
        canvas.drawText(
            peak.toString(),
            left - tickGap,
            top + textPaint.textSize * TICK_BASELINE,
            textPaint,
        )

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(context.getString(R.string.viewer_histogram_y_axis), 0f, -textPaint.fontMetrics.ascent, textPaint)

        val baseline = bottom + tickGap - textPaint.fontMetrics.ascent
        canvas.drawText(ReportBuilder.formatMetric(hist.min), left, baseline, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        val maxLabel = "${ReportBuilder.formatMetric(hist.max)} $unit"
        canvas.drawText(maxLabel, right, baseline, textPaint)
    }

    /**
     * Wraps the plot: a fixed plot height plus gutters that fit the text, so a
     * large font scale makes the view taller instead of clipping its labels
     * (readiness AE1-104).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val fm = textPaint.fontMetrics
        val wanted = (topGutter(fm) + dp(PLOT_HEIGHT_DP) + bottomGutter(fm)).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(wanted, heightMeasureSpec),
        )
    }

    /**
     * Sets [plot] for a [w] x [h] view from the measured labels: the left gutter
     * fits the widest y tick, the top one the y title above half a tick label,
     * the bottom one a row of x labels. False when no plot area is left.
     */
    private fun framePlot(hist: FieldHistogram, w: Int, h: Int): Boolean {
        val fm = textPaint.fontMetrics
        val peak = hist.counts.maxOrNull() ?: 0
        val widestTick = maxOf(textPaint.measureText("0"), textPaint.measureText(peak.toString()))
        val left = maxOf(dp(PAD_LEFT_DP), widestTick + dp(TICK_GAP_DP) * 2)
        plot.set(left, topGutter(fm), w - dp(PAD_RIGHT_DP), h - bottomGutter(fm))
        return plot.right > plot.left && plot.bottom > plot.top
    }

    /** The y title's row, a gap, then the part of the top tick label above its anchor. */
    private fun topGutter(fm: Paint.FontMetrics): Float =
        maxOf(
            dp(PAD_TOP_DP) + textPaint.textSize,
            fm.descent - fm.ascent + dp(TICK_GAP_DP) + (-fm.ascent - textPaint.textSize * TICK_BASELINE),
        )

    /** A gap and one row of x labels, ascent to descent. */
    private fun bottomGutter(fm: Paint.FontMetrics): Float =
        maxOf(dp(PAD_BOTTOM_DP), dp(TICK_GAP_DP) + fm.descent - fm.ascent)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (histogram == null) return super.onTouchEvent(event)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> true
            MotionEvent.ACTION_UP -> {
                selectAt(event.x)
                performClick()
                true
            }
            else -> super.onTouchEvent(event)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun selectAt(x: Float) {
        val hist = histogram ?: return
        if (!framePlot(hist, width, height)) return
        val left = plot.left
        val right = plot.right
        val rel = ((x - left) / (right - left)).coerceIn(0f, LAST_BIN_EDGE)
        val index = (rel * hist.binCount).toInt().coerceIn(0, hist.binCount - 1)
        selectedBin = index
        onBinSelected?.invoke(index)
        invalidate()
    }

    companion object {
        private const val PAD_LEFT_DP = 42f
        private const val PAD_RIGHT_DP = 8f
        private const val PAD_TOP_DP = 10f
        private const val PAD_BOTTOM_DP = 20f
        private const val TICK_GAP_DP = 4f
        private const val TICK_BASELINE = 0.33f

        /** The bars' height; the gutters around it grow with the text. */
        private const val PLOT_HEIGHT_DP = 108f

        /** Just short of the right edge, so a touch there selects the last bin. */
        private const val LAST_BIN_EDGE = 0.9999f
    }
}
