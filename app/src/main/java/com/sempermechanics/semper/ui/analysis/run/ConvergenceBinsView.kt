package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.annotation.ColorRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.PlotStyle
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import kotlin.math.roundToInt

/**
 * The run overlay's convergence per frame: one bin per planned frame on a
 * 0–100% scale, sitting on a thin track, with a dashed line where
 * [ConvergenceGate] counts a frame as low. The left axis is labelled 100 at
 * the top and 50 at that line.
 *
 * A finished frame's bin is as tall as its convergence, blue, or red under the
 * gate. The frame being solved is a pale outlined bin as tall as its own
 * progress. Frames not started are empty slots. Over [MAX_BINS] frames,
 * neighbours share a bin that shows the lowest of them, so a low frame never
 * hides behind a good one.
 *
 * Bins are at most [MAX_BIN_DP] wide, so a short run is a centred group, not
 * a few wide blocks. Up to [MAX_LABELLED_BINS] bins, each finished bin carries
 * its convergence above it and the running bin its progress ([labelsFor]).
 */
class ConvergenceBinsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Where a bin's frames are. */
    enum class State { WAITING, RUNNING, DONE }

    /**
     * One bin. [percent] is the lowest convergence of a [State.DONE] bin (NaN
     * when none of its frames kept points) or how far a [State.RUNNING] bin's
     * frames have come. [lowPercent] is a running bin's lowest finished frame
     * when that is under the gate, else NaN.
     */
    data class Bin(val state: State, val percent: Float, val lowPercent: Float = Float.NaN) {
        /** A finished bin under the gate. */
        val isLow: Boolean get() = state == State.DONE && percent < MIN_PERCENT
    }

    /** The bins as last set, in frame order. */
    @VisibleForTesting
    var bins: List<Bin> = emptyList()
        private set

    private val donePaint = fill(R.color.sky_primary)
    private val lowPaint = fill(R.color.semantic_danger)
    private val runningPaint = fill(R.color.sky_container)
    private val slotPaint = fill(R.color.surface_outline).apply { alpha = SLOT_ALPHA }
    private val trackPaint = fill(R.color.surface_outline)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = PlotStyle.px(context, OUTLINE_DP)
        color = ContextCompat.getColor(context, R.color.sky_primary)
    }
    private val gatePaint = PlotStyle.gridPaint(context, R.color.viewer_plot_muted).apply {
        val dash = PlotStyle.px(context, DASH_DP)
        pathEffect = DashPathEffect(floatArrayOf(dash, dash), 0f)
    }
    private val valuePaint = text(R.color.text_secondary)
    private val axisPaint = text(R.color.viewer_plot_muted).apply { textAlign = Paint.Align.RIGHT }
    private val valueColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val lowValueColor = ContextCompat.getColor(context, R.color.semantic_danger)
    private val runningValueColor = ContextCompat.getColor(context, R.color.sky_primary)
    private val box = RectF()
    private val rect = RectF()

    /**
     * Shows a run of [planned] frames at [frameIndex] (-1 before the first,
     * [planned] once all are solved), [framePercent] into it, with each
     * frame's [convergence] so far (NaN until solved). Kept, not copied.
     */
    fun setRun(planned: Int, frameIndex: Int, framePercent: Float, convergence: FloatArray?) {
        bins = binsFor(planned, frameIndex, framePercent, convergence)
        contentDescription = if (planned > 0) {
            val converged = convergence?.count { !it.isNaN() && it >= MIN_PERCENT } ?: 0
            resources.getQuantityString(
                R.plurals.run_bins_cd,
                converged,
                (frameIndex + 1).coerceIn(1, planned),
                planned,
                converged,
                MIN_PERCENT.roundToInt(),
            )
        } else {
            null
        }
        invalidate()
    }

    /** No run: nothing drawn. */
    fun clear() = setRun(planned = 0, frameIndex = -1, framePercent = 0f, convergence = null)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val track = PlotStyle.px(context, TRACK_DP)
        val labelGap = PlotStyle.px(context, LABEL_GAP_DP)
        box.set(
            paddingLeft + axisPaint.measureText(AXIS_TOP_LABEL) + labelGap,
            paddingTop - valuePaint.ascent() + labelGap,
            (width - paddingRight).toFloat(),
            height - paddingBottom - track,
        )
        if (box.width() <= 0f || box.height() <= 0f || bins.isEmpty()) return
        rect.set(box.left, box.bottom, box.right, box.bottom + track)
        canvas.drawRect(rect, trackPaint)
        val slots = slotsFor(bins.size, box.width(), resources.displayMetrics.density)
        val labels = labelsFor(bins)
        bins.forEachIndexed { i, bin ->
            val left = box.left + slots.start + i * (slots.binWidth + slots.gap)
            drawBin(canvas, bin, left, slots.binWidth)
            labels[i]?.let { drawValue(canvas, bin, it, left + slots.binWidth / 2) }
        }
        val gateY = y(MIN_PERCENT)
        canvas.drawLine(box.left, gateY, box.right, gateY, gatePaint)
        drawAxis(canvas, AXIS_TOP_LABEL, y(FULL))
        drawAxis(canvas, AXIS_GATE_LABEL, gateY)
    }

    /** [value] just above [bin]'s top: the running bin's in blue, a low one's in red. */
    private fun drawValue(canvas: Canvas, bin: Bin, value: Int, centreX: Float) {
        valuePaint.color = when {
            bin.state == State.RUNNING -> runningValueColor
            bin.isLow -> lowValueColor
            else -> valueColor
        }
        val top = if (bin.state == State.RUNNING) runningTop(bin) else y(bin.percent)
        canvas.drawText(value.toString(), centreX, top - PlotStyle.px(context, LABEL_GAP_DP), valuePaint)
    }

    /** An axis [label] in the gutter, centred on [atY]. */
    private fun drawAxis(canvas: Canvas, label: String, atY: Float) {
        val baseline = atY - (axisPaint.ascent() + axisPaint.descent()) / 2
        canvas.drawText(label, box.left - PlotStyle.px(context, LABEL_GAP_DP), baseline, axisPaint)
    }

    private fun drawBin(canvas: Canvas, bin: Bin, left: Float, width: Float) {
        rect.set(left, box.top, left + width, box.bottom)
        canvas.drawRect(rect, slotPaint)
        when (bin.state) {
            State.WAITING -> Unit
            State.DONE -> if (!bin.percent.isNaN()) {
                rect.top = y(bin.percent)
                canvas.drawRect(rect, if (bin.isLow) lowPaint else donePaint)
            }
            State.RUNNING -> {
                rect.top = runningTop(bin)
                canvas.drawRect(rect, runningPaint)
                if (!bin.lowPercent.isNaN()) {
                    val top = rect.top
                    rect.top = y(bin.lowPercent)
                    canvas.drawRect(rect, lowPaint)
                    rect.top = top
                }
                val inset = outlinePaint.strokeWidth / 2
                rect.inset(inset, inset)
                canvas.drawRect(rect, outlinePaint)
            }
        }
    }

    /** Never flat: a frame just started still shows where the run is. */
    private fun runningTop(bin: Bin): Float =
        minOf(y(bin.percent), box.bottom - PlotStyle.px(context, MIN_RUNNING_DP))

    private fun y(percent: Float): Float = box.bottom - percent.coerceIn(0f, FULL) / FULL * box.height()

    /** Value and axis labels: the app's sans at the charts' label size, centred. */
    private fun text(@ColorRes colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = PlotStyle.axisLabelPx(context)
        color = ContextCompat.getColor(context, colorRes)
        textAlign = Paint.Align.CENTER
    }

    private fun fill(@ColorRes colorRes: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, colorRes)
    }

    /**
     * Where the bins sit across the plot: the first one [start] px in, each
     * [binWidth] wide with [gap] between.
     */
    internal data class Slots(val start: Float, val binWidth: Float, val gap: Float)

    companion object {
        /** Above this many planned frames, neighbouring frames share a bin. */
        const val MAX_BINS = 100

        /** Up to this many bins, each one is labelled with its value. */
        const val MAX_LABELLED_BINS = 12

        /** The widest a bin is drawn, dp. */
        const val MAX_BIN_DP = 18f

        /** The gap between bins drawn at [MAX_BIN_DP], dp, room allowing. */
        const val CAPPED_GAP_DP = 8f

        private const val MIN_PERCENT = AnalysisViewModel.MIN_CONVERGENCE_PERCENT
        private const val FULL = 100f
        private const val TRACK_DP = 3f
        private const val OUTLINE_DP = 1.5f
        private const val MIN_RUNNING_DP = 4f
        private const val DASH_DP = 3f
        private const val MIN_GAP_DP = 1f
        private const val MAX_GAP_DP = 3f
        private const val GAP_FRACTION = 0.2f
        private const val SLOT_ALPHA = 110
        private const val LABEL_GAP_DP = 3f

        // The axis carries the unit; the bars' own numbers stay bare (readiness AE1-112).
        private val AXIS_TOP_LABEL = "${FULL.toInt()}%"
        private val AXIS_GATE_LABEL = "${MIN_PERCENT.toInt()}%"

        /** The y axis's labels, top first. */
        @VisibleForTesting
        internal fun axisLabels(): List<String> = listOf(AXIS_TOP_LABEL, AXIS_GATE_LABEL)

        /**
         * The slots for [count] bins across [width] px at [density]: the width
         * split evenly with a gap of a fifth of a pitch (1–3 dp), or, where that
         * would draw bins wider than [MAX_BIN_DP], bins that wide
         * [CAPPED_GAP_DP] apart (less when that would not fit), centred.
         */
        @VisibleForTesting
        internal fun slotsFor(count: Int, width: Float, density: Float): Slots {
            val gap = (width / count * GAP_FRACTION).coerceIn(MIN_GAP_DP * density, MAX_GAP_DP * density)
            val fit = ((width - gap * (count - 1)) / count).coerceAtLeast(1f)
            val cap = MAX_BIN_DP * density
            if (fit <= cap) return Slots(0f, fit, gap)
            val cappedGap = if (count > 1) {
                ((width - cap * count) / (count - 1)).coerceIn(gap, CAPPED_GAP_DP * density)
            } else {
                0f
            }
            return Slots((width - cap * count - cappedGap * (count - 1)) / 2, cap, cappedGap)
        }

        /**
         * The number drawn above each of [bins]: a finished bin's convergence
         * (none when it kept no points), the running bin's progress, none for a
         * waiting one; none at all over [MAX_LABELLED_BINS] bins. Truncated, so
         * a frame just under the gate never reads as 50.
         */
        @VisibleForTesting
        internal fun labelsFor(bins: List<Bin>): List<Int?> {
            if (bins.size > MAX_LABELLED_BINS) return List(bins.size) { null }
            return bins.map { bin ->
                when {
                    bin.state == State.WAITING || bin.percent.isNaN() -> null
                    else -> bin.percent.coerceIn(0f, FULL).toInt()
                }
            }
        }

        /**
         * The bins for a run of [planned] frames: one a frame, or, over
         * [maxBins], as few frames a bin as keeps the count at or under it.
         */
        @VisibleForTesting
        internal fun binsFor(
            planned: Int,
            frameIndex: Int,
            framePercent: Float,
            convergence: FloatArray?,
            maxBins: Int = MAX_BINS,
        ): List<Bin> {
            if (planned <= 0) return emptyList()
            val perBin = (planned + maxBins - 1) / maxBins
            val count = (planned + perBin - 1) / perBin
            return List(count) { b ->
                binOf(b * perBin until minOf(planned, (b + 1) * perBin), frameIndex, framePercent, convergence)
            }
        }

        /**
         * A frame is finished once the run is past it or it has a value. Values
         * under 0 or NaN (a frame that kept no points) count as finished but
         * set no height, as [ConvergenceGate] neither counts nor breaks on them.
         */
        private fun binOf(frames: IntRange, frameIndex: Int, framePercent: Float, convergence: FloatArray?): Bin {
            var done = 0
            var lowest = Float.NaN
            for (i in frames) {
                val value = convergence?.getOrNull(i) ?: Float.NaN
                if (i >= frameIndex && value.isNaN()) continue
                done++
                if (value >= 0f && (lowest.isNaN() || value < lowest)) lowest = value
            }
            val size = frames.last - frames.first + 1
            return when {
                done == size -> Bin(State.DONE, lowest)
                frameIndex !in frames -> Bin(State.WAITING, 0f)
                else -> {
                    val running = done + framePercent.coerceIn(0f, FULL) / FULL
                    Bin(State.RUNNING, running / size * FULL, lowest.takeIf { it < MIN_PERCENT } ?: Float.NaN)
                }
            }
        }
    }
}
