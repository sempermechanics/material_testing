package com.rafad.indicvisiondic.ui.analysis

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.rafad.indicvisiondic.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Minimal XY line plot for the virtual strain gauge study — the two charts
 * §5.4.5 of the Good Practices Guide reads its conclusions off:
 *
 *  - peak strain and strain noise against VSG size (the convergence view), and
 *  - strain along the line cut, one polyline per VSG (the line-scan view).
 *
 * Deliberately small: linear axes, no zoom, and the one gesture is a horizontal
 * scrub that reports the value under the finger through [onScrub]. The numbers
 * next to the chart carry the precision; the chart carries the shape.
 */
@Suppress("TooManyFunctions") // the draw pipeline and the scrub gesture, each piece small
class VsgPlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * @param points x/y pairs in data units, ordered along x
     * @param markers true to draw a dot at each point (sparse convergence
     *   curves) rather than a bare polyline (dense line scans)
     * @param muted true to draw the line translucent, for background context
     */
    data class Series(
        val label: String,
        val color: Int,
        val points: List<Pair<Float, Float>>,
        val markers: Boolean = true,
        val muted: Boolean = false,
    )

    /**
     * One curve's value under the scrub line, carrying the colour it is drawn
     * in — the readout is only readable against several curves if each entry
     * matches the line it came from.
     */
    data class Sample(val label: String, val value: Float, val color: Int)

    private companion object {
        const val AXIS_LABEL_SP = 11f
        const val LINE_WIDTH_DP = 2f
        const val MARKER_RADIUS_DP = 3.5f
        const val PAD_LEFT_DP = 46f
        const val PAD_RIGHT_DP = 12f
        const val PAD_TOP_DP = 10f
        const val PAD_BOTTOM_DP = 30f
        const val GRID_LINES = 4
        const val TICK_GAP_DP = 4f

        /** Nudge that centres a tick label on its gridline, as a share of text size. */
        const val TICK_BASELINE = 0.33f

        /** Head-room above/below the data so markers are not clipped. */
        const val Y_MARGIN_FRACTION = 0.08f

        /** Series colours, reused cyclically for line-scan plots. */
        val PALETTE = intArrayOf(
            0xFF0288D1.toInt(),
            0xFFE53935.toInt(),
            0xFF43A047.toInt(),
            0xFFF5A623.toInt(),
            0xFF8E24AA.toInt(),
            0xFF00897B.toInt(),
            0xFF5D4037.toInt(),
            0xFF3949AB.toInt(),
        )
    }

    /** Colour for the n-th series of a multi-line plot. */
    fun paletteColor(index: Int): Int = PALETTE[index % PALETTE.size]

    private val density = resources.displayMetrics.density

    private fun dp(value: Float) = value * density

    /** Axis labels in px, scaled for the user's font-size setting. */
    private val axisLabelPx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AXIS_LABEL_SP, resources.displayMetrics)

    /** Reused every draw — onDraw runs on each scrub frame. */
    private val frame = Frame()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(LINE_WIDTH_DP)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.surface_outline)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = axisLabelPx
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val path = Path()

    private var series: List<Series> = emptyList()
    private var xLabel: String = ""
    private var yLabel: String = ""

    /** Data-unit x of a vertical guide line, e.g. the recommended VSG. */
    private var highlightX: Float? = null
    private var scrubX: Float? = null
    private var currentBounds: Bounds? = null

    /** Called while scrubbing: x position and y values per visible series. */
    var onScrub: ((x: Float, samples: List<Sample>) -> Unit)? = null

    fun setData(series: List<Series>, xLabel: String, yLabel: String, highlightX: Float? = null) {
        this.series = series
        this.xLabel = xLabel
        this.yLabel = yLabel
        this.highlightX = highlightX
        invalidate()
    }

    private class Bounds(val xMin: Float, val xMax: Float, val yMin: Float, val yMax: Float)

    /** The plot area in view pixels, inside the axis gutters. */
    /** Mutable so one instance can serve every draw. */
    private class Frame(
        var left: Float = 0f,
        var right: Float = 0f,
        var top: Float = 0f,
        var bottom: Float = 0f,
    ) {
        fun set(l: Float, r: Float, t: Float, b: Float) {
            left = l
            right = r
            top = t
            bottom = b
        }
    }

    private fun bounds(): Bounds? {
        val all = series.flatMap { it.points }
        if (all.isEmpty()) return null
        val xs = all.map { it.first }
        val ys = all.map { it.second }
        var yMin = ys.min()
        var yMax = ys.max()
        val span = max(yMax - yMin, abs(yMax) * Y_MARGIN_FRACTION).takeIf { it > 0f } ?: 1f
        yMin -= span * Y_MARGIN_FRACTION
        yMax += span * Y_MARGIN_FRACTION
        val xMin = xs.min()
        val xMax = xs.max()
        return Bounds(xMin, if (xMax > xMin) xMax else xMin + 1f, yMin, yMax)
    }

    @Suppress("CyclomaticComplexMethod") // one branch per optional layer: crosshair, muted series, markers
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = bounds() ?: return
        currentBounds = b

        val left = dp(PAD_LEFT_DP)
        val right = width - dp(PAD_RIGHT_DP)
        val top = dp(PAD_TOP_DP)
        val bottom = height - dp(PAD_BOTTOM_DP)
        if (right <= left || bottom <= top) return

        fun sx(x: Float) = left + (x - b.xMin) / (b.xMax - b.xMin) * (right - left)
        fun sy(y: Float) = bottom - (y - b.yMin) / (b.yMax - b.yMin) * (bottom - top)

        frame.set(left, right, top, bottom)
        drawGrid(canvas, b, frame)
        (scrubX ?: highlightX)?.let {
            gridPaint.color = ContextCompat.getColor(context, R.color.sky_primary)
            canvas.drawLine(sx(it), top, sx(it), bottom, gridPaint)
            gridPaint.color = ContextCompat.getColor(context, R.color.surface_outline)
        }

        for (s in series) {
            if (s.points.isEmpty()) continue
            linePaint.color = s.color
            linePaint.alpha = if (s.muted) ALPHA_MUTED else ALPHA_SOLID
            path.reset()
            s.points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(sx(x), sy(y)) else path.lineTo(sx(x), sy(y))
            }
            canvas.drawPath(path, linePaint)
            if (s.markers) {
                markerPaint.color = s.color
                s.points.forEach { (x, y) -> canvas.drawCircle(sx(x), sy(y), dp(MARKER_RADIUS_DP), markerPaint) }
            }
        }

        drawAxisLabels(canvas, left, right, bottom)
    }

    @Suppress("ReturnCount") // one exit per gesture phase, plus the not-ours fall-throughs
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val b = currentBounds ?: return super.onTouchEvent(event)
        if (!hasFrame()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                updateScrub(event.x, b)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                clearScrub()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** A scrub ends as a click so accessibility services can drive the view. */
    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hasFrame(): Boolean = frame.right > frame.left && frame.bottom > frame.top

    private fun contains(x: Float, y: Float): Boolean =
        x in frame.left..frame.right && y in frame.top..frame.bottom

    private fun updateScrub(xPx: Float, b: Bounds) {
        val clamped = xPx.coerceIn(frame.left, frame.right)
        val ratio = (clamped - frame.left) / (frame.right - frame.left)
        val xData = b.xMin + ratio * (b.xMax - b.xMin)
        scrubX = xData
        val values = series
            .filterNot { it.muted }
            .mapNotNull { entry ->
                interpolateY(entry.points, xData)?.let { y -> Sample(entry.label, y, entry.color) }
            }
        onScrub?.invoke(xData, values)
        invalidate()
    }

    private fun clearScrub() {
        if (scrubX == null) return
        scrubX = null
        onScrub?.invoke(Float.NaN, emptyList())
        invalidate()
    }

    @Suppress("ReturnCount") // empty, both clamps, the degenerate span and the interpolated hit
    private fun interpolateY(points: List<Pair<Float, Float>>, x: Float): Float? {
        if (points.isEmpty()) return null
        if (x <= points.first().first) return points.first().second
        if (x >= points.last().first) return points.last().second
        for (i in 0 until points.lastIndex) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            if (x in x0..x1) {
                val span = (x1 - x0).takeIf { it != 0f } ?: return y0
                val t = (x - x0) / span
                return y0 + t * (y1 - y0)
            }
        }
        return null
    }

    private fun drawGrid(canvas: Canvas, b: Bounds, f: Frame) {
        textPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        for (i in 0..GRID_LINES) {
            val y = f.bottom - (f.bottom - f.top) * i / GRID_LINES
            canvas.drawLine(f.left, y, f.right, y, gridPaint)
            val value = b.yMin + (b.yMax - b.yMin) * i / GRID_LINES
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(format(value), f.left - dp(TICK_GAP_DP), y + textPaint.textSize * TICK_BASELINE, textPaint)
        }
        val baseline = f.bottom + textPaint.textSize + dp(TICK_GAP_DP)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(format(b.xMin), f.left, baseline, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(format(b.xMax), f.right, baseline, textPaint)
    }

    private fun drawAxisLabels(canvas: Canvas, left: Float, right: Float, bottom: Float) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        canvas.drawText(
            xLabel,
            (left + right) / 2f,
            bottom + textPaint.textSize * 2f + dp(TICK_GAP_DP),
            textPaint,
        )
        canvas.save()
        canvas.rotate(-QUARTER_TURN, dp(TICK_GAP_DP) + textPaint.textSize, (bottom) / 2f)
        canvas.drawText(yLabel, dp(TICK_GAP_DP) + textPaint.textSize, bottom / 2f, textPaint)
        canvas.restore()
        textPaint.textAlign = Paint.Align.LEFT
    }

    /** Compact tick label: enough digits to separate neighbouring gridlines. */
    private fun format(value: Float): String = when {
        abs(value) >= LARGE_VALUE -> String.format(Locale.US, "%.0f", value)
        abs(value) >= SMALL_VALUE -> String.format(Locale.US, "%.1f", value)
        else -> String.format(Locale.US, "%.2f", value)
    }
}

private const val ALPHA_SOLID = 255
private const val ALPHA_MUTED = 140
private const val QUARTER_TURN = 90f
private const val LARGE_VALUE = 100f
private const val SMALL_VALUE = 1f
