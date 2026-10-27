package com.sempermechanics.semper.ui.analysis.sweep

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.dp
import kotlin.math.hypot

/**
 * The parameter space of a virtual strain gauge study, drawn as a 2-D lattice:
 * one node per analysis, subset size across the x-axis and virtual strain gauge
 * size up the y-axis. Because every subset carries its own VSG ladder, the
 * nodes fall into vertical columns — the lattice makes the shape of the sweep,
 * and which corners of it the engine could not solve, legible at a glance.
 *
 * Interactive: tap to focus a solved node, double-tap/long-press to open it.
 *
 * Both plot axes: Y (VSG) is floored at 1; X (subset) uses evenly spaced
 * columns for the present subset sizes. Labels are the app's sans, set
 * horizontally.
 */
class SweepLatticeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    /**
     * Off by default so the planned lattice preview keeps its old passive
     * behaviour; the result lattice explicitly enables interactions.
     */
    var interactionEnabled: Boolean = false

    /**
     * True for the result lattice and the wizard's sweep preview: a tick label
     * on every gridline, subset size's unit folded onto the rightmost tick.
     * False for the run overlay ([LiveSweepLattice]), which labels only the
     * ends of each range (the lowest and highest VSG at the left, the smallest
     * and largest subset under the axis with "subset px" between them) and
     * draws bigger nodes, up to [NODE_RADIUS_LIVE_DP], so the solve being run
     * is easy to follow.
     */
    var compact: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    /**
     * One analysis of the sweep. [solved] is false for a combination the engine
     * skipped; [frameIndex] is its position in the result viewer, or -1 when it
     * was skipped and has no frame to open.
     */
    data class Node(
        val subset: Int,
        val step: Int,
        /** Strain window in data points; null for a sweep stored before windows were counted in points. */
        val window: Int?,
        /** VSG in px, the y axis: what the engine was handed. */
        val vsg: Int,
        val solved: Boolean,
        val frameIndex: Int = -1,
        val failureReason: String = "",
        /** Native / run code when [solved] is false; drives the FAQ Why? hop. */
        val failureCode: Int? = null,
        /** Not run yet: the live sweep lattice draws it as a muted ring; [solved] is ignored. */
        val pending: Boolean = false,
        /** Being solved now: ringed, whatever its state. */
        val current: Boolean = false,
    )

    /** Invoked when a solved node is tapped. */
    var onNodeClick: ((Node) -> Unit)? = null
    var onNodeDoubleClick: ((Node) -> Unit)? = null
    var onNodeLongClick: ((Node) -> Unit)? = null

    /** Solved frame currently focused by the host screen, or -1 for none. */
    var selectedFrameIndex: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    /** Where each node was last drawn, for hit-testing taps. */
    private class Placed(val node: Node, val x: Float, val y: Float)
    private val placed = ArrayList<Placed>()

    private companion object {
        // The gutters (PAD_*) live in SweepLatticeGutters.
        const val NODE_RADIUS_DP = 5f

        /** The run overlay's node radius, where its rows leave room. */
        const val NODE_RADIUS_LIVE_DP = 10f

        /** The run overlay's ring around the node being solved, beyond the node. */
        const val CURRENT_RING_LIVE_DP = 4f

        /** A live node's diameter at most this share of the gap between its column's rows. */
        const val NODE_ROW_SHARE = 0.4f
        const val NODE_STROKE_DP = 2f
        const val CONNECTOR_DP = 1.5f
        const val TICK_GAP_DP = SweepLatticeGutters.TICK_GAP_DP
        const val Y_TICKS = SweepLatticeGutters.Y_TICKS

        /** Y-axis floor: VSG ticks / origin start at 1. */
        const val AXIS_MIN = 1

        /** Head-room above the VSG range so nodes are not clipped. */
        const val Y_MARGIN_FRACTION = 0.12f

        /** Baseline nudge that centres a tick label on its gridline. */
        const val TICK_BASELINE = PlotStyle.LATTICE_TICK_BASELINE

        /** Half a column, so a column's nodes sit at its centre. */
        const val HALF_COLUMN = 0.5f

        /** Tap tolerance around a node centre, in dp. */
        const val TOUCH_RADIUS_DP = 22f
        const val SELECT_RING_DP = 3f
    }

    // Reused every draw — onDraw runs on each lattice interaction.
    private val columnX = HashMap<Int, Float>()
    private val frame = RectF()

    private val gridPaint = PlotStyle.gridPaint(context)
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(CONNECTOR_DP)
        color = ContextCompat.getColor(context, R.color.viewer_plot_connector)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(NODE_STROKE_DP)
    }
    private val textPaint = PlotStyle.labelTextPaint(context)
    private val gutters = SweepLatticeGutters(resources.displayMetrics.density)
    private val path = Path()

    private var nodes: List<Node> = emptyList()
    private var columns: List<Int> = emptyList()
    private var winMin = AXIS_MIN
    private var winMax = AXIS_MIN + 1
    private var vsgLo = AXIS_MIN
    private var vsgHi = AXIS_MIN

    fun setNodes(nodes: List<Node>) {
        this.nodes = nodes
        columns = nodes.map { it.subset }.distinct().sorted()
        val windows = nodes.map { it.vsg }
        // Frame the windows actually swept rather than anchoring at 1: the
        // sweep's own range is the interesting span, and starting below it threw
        // away most of the plot.
        val lo = windows.minOrNull() ?: AXIS_MIN
        val hi = windows.maxOrNull() ?: AXIS_MIN
        vsgLo = lo
        vsgHi = hi
        val yMargin = ((hi - lo) * Y_MARGIN_FRACTION).toInt().coerceAtLeast(1)
        winMin = (lo - yMargin).coerceAtLeast(0)
        winMax = maxOf(hi + yMargin, winMin + 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        placed.clear()
        if (nodes.isEmpty() || columns.isEmpty()) return

        gutters.compact = compact
        if (!gutters.frame(frame, textPaint, yTickLabels, width, height)) return
        val left = frame.left
        val right = frame.right
        val top = frame.top
        val bottom = frame.bottom

        columnX.clear()
        columns.forEachIndexed { i, subset ->
            columnX[subset] = left + (i + HALF_COLUMN) / columns.size * (right - left)
        }
        fun yFor(win: Int) = bottom - (win - winMin).toFloat() / (winMax - winMin) * (bottom - top)

        drawGrid(canvas, columnX, frame, ::yFor)
        drawConnectors(canvas, columnX, ::yFor)
        drawNodes(canvas, columnX, ::yFor)
        drawLabels(canvas, columnX, frame)
        if (compact) {
            // "VSG (px)" over the y ticks: the axis's name and unit (readiness AE1V-018).
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(context.getString(R.string.vsg_lattice_axis_vsg), 0f, top - textPaint.textSize, textPaint)
        }
    }

    /** The y tick labels [drawGrid] draws, bottom to top. */
    internal val yTickLabels: List<String>
        get() = SweepLatticeGutters.yTickLabels(compact, winMin, winMax, vsgLo, vsgHi)

    /** The tick and axis text paint, for tests that measure the labels. */
    internal val labelPaint: Paint get() = textPaint

    /** The plot area of the last draw. */
    internal val plotFrame: RectF get() = frame

    /** Every how many y rows the last draw labelled. */
    internal var yTickStride = 1
        private set

    /** Node where the current press started, for gesture resolution. */
    private var pressedNode: Node? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                pressedNode = nodeAt(e.x, e.y)
                val hit = pressedNode != null
                parent?.requestDisallowInterceptTouchEvent(hit)
                return hit
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val node = nodeAt(e.x, e.y)
                if (node != null) {
                    performClick()
                    onNodeClick?.invoke(node)
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val node = nodeAt(e.x, e.y)
                if (node != null) {
                    onNodeDoubleClick?.invoke(node)
                    return true
                }
                return false
            }

            override fun onLongPress(e: MotionEvent) {
                val node = nodeAt(e.x, e.y)
                if (node != null) onNodeLongClick?.invoke(node)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean = pressedNode != null
        },
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactionEnabled) return super.onTouchEvent(event)
        val handled = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            pressedNode = null
        }
        return handled || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** The nearest node within the tap tolerance of (x, y), or null. */
    private fun nodeAt(x: Float, y: Float): Node? {
        val hit = placed
            .minByOrNull { hypot(it.x - x, it.y - y) }
            ?: return null
        return if (hypot(hit.x - x, hit.y - y) <= dp(TOUCH_RADIUS_DP)) hit.node else null
    }

    /**
     * Each column, and labelled rows: in [compact] [Y_TICKS] even steps of the
     * framed range, in the run overlay the lowest and the highest VSG only.
     */
    private fun drawGrid(canvas: Canvas, columnX: Map<Int, Float>, f: RectF, yFor: (Int) -> Float) {
        columnX.values.forEach { x -> canvas.drawLine(x, f.top, x, f.bottom, gridPaint) }
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = PlotStyle.ink(context)
        val rows = if (compact) {
            (0..Y_TICKS).map { i ->
                f.bottom - (f.bottom - f.top) * i / Y_TICKS to winMin + (winMax - winMin) * i / Y_TICKS
            }
        } else {
            listOf(vsgLo, vsgHi).distinct().map { yFor(it) to it }
        }
        // Every row keeps its grid line; at a large font scale only every
        // other one is labelled, so the labels never overlap (readiness AE1V-010).
        yTickStride = if (compact) SweepLatticeGutters.tickStride(f.height() / Y_TICKS, textPaint.textSize) else 1
        rows.forEachIndexed { i, (y, vsg) ->
            canvas.drawLine(f.left, y, f.right, y, gridPaint)
            if (i % yTickStride == 0) {
                val baseline = y + textPaint.textSize * TICK_BASELINE
                canvas.drawText(vsg.toString(), f.left - dp(TICK_GAP_DP), baseline, textPaint)
            }
        }
    }

    /** A faint ladder down each column, so a subset's VSG series reads as one run. */
    private fun drawConnectors(canvas: Canvas, columnX: Map<Int, Float>, yFor: (Int) -> Float) {
        columns.forEach { subset ->
            val x = columnX[subset] ?: return@forEach
            val ladder = nodes.filter { it.subset == subset }.sortedBy { it.vsg }
            if (ladder.size < 2) return@forEach
            path.reset()
            ladder.forEachIndexed { i, node ->
                if (i == 0) path.moveTo(x, yFor(node.vsg)) else path.lineTo(x, yFor(node.vsg))
            }
            canvas.drawPath(path, connectorPaint)
        }
    }

    /**
     * The node radius: [NODE_RADIUS_DP] in [compact]; in the run overlay up to
     * [NODE_RADIUS_LIVE_DP], shrunk so a column's nearest rows do not touch.
     */
    private fun nodeRadius(yFor: (Int) -> Float): Float {
        if (compact) return dp(NODE_RADIUS_DP)
        val closest = nodes.groupBy { it.subset }.values
            .flatMap { column -> column.map { yFor(it.vsg) }.distinct().sorted().zipWithNext { a, b -> b - a } }
            .minOrNull() ?: Float.MAX_VALUE
        return (closest * NODE_ROW_SHARE).coerceIn(dp(NODE_RADIUS_DP), dp(NODE_RADIUS_LIVE_DP))
    }

    private fun drawNodes(canvas: Canvas, columnX: Map<Int, Float>, yFor: (Int) -> Float) {
        val radius = nodeRadius(yFor)
        val selectedRadius = radius + dp(if (compact) SELECT_RING_DP else CURRENT_RING_LIVE_DP)
        // Single solved colour, not one per curve (SweepPlotView.paletteColor is
        // reserved for the plot's own emphasis -- keying the node fill to it too
        // taught a colour that never matched once more than one node was solved,
        // since only the FOCUSED curve is ever shown in colour there now).
        val solved = ContextCompat.getColor(context, R.color.viewer_plot_node_solved)
        val skipped = ContextCompat.getColor(context, R.color.viewer_plot_node_skipped)
        val pending = ContextCompat.getColor(context, R.color.viewer_plot_muted)
        val ring = PlotStyle.inkStrong(context)
        nodes.forEach { node ->
            val x = columnX[node.subset] ?: return@forEach
            val y = yFor(node.vsg)
            placed.add(Placed(node, x, y))
            when {
                node.pending -> {
                    strokePaint.color = pending
                    canvas.drawCircle(x, y, radius, strokePaint)
                }
                node.solved -> {
                    fillPaint.color = solved
                    canvas.drawCircle(x, y, radius, fillPaint)
                }
                // Hollow ring, no fill: a combination that was attempted and
                // failed. Deliberately transparent at the centre rather than a
                // background-matched disc -- this view is hosted on different
                // surfaces (result lattice, wizard preview card, run overlay), and
                // only a truly empty centre is guaranteed to match all of them.
                else -> {
                    strokePaint.color = skipped
                    canvas.drawCircle(x, y, radius, strokePaint)
                }
            }
            val selected = node.solved &&
                !node.pending &&
                selectedFrameIndex >= 0 &&
                node.frameIndex == selectedFrameIndex
            if (selected || node.current) {
                strokePaint.color = ring
                canvas.drawCircle(x, y, selectedRadius, strokePaint)
            }
        }
    }

    /**
     * The x labels. [compact]: every subset, the rightmost carrying the unit.
     * The run overlay: the smallest and the largest subset, with "subset px"
     * between them (at the left edge when there is one column).
     */
    private fun drawLabels(canvas: Canvas, columnX: Map<Int, Float>, f: RectF) {
        textPaint.color = PlotStyle.ink(context)
        textPaint.textAlign = Paint.Align.CENTER
        val baseline = f.bottom + textPaint.textSize + dp(TICK_GAP_DP)
        val lastColumn = columns.lastOrNull()
        val labelled = if (compact) columns else listOfNotNull(columns.firstOrNull(), lastColumn).distinct()
        labelled.forEach { subset ->
            val x = columnX[subset] ?: return@forEach
            val label = if (compact && subset == lastColumn) {
                context.getString(R.string.vsg_lattice_axis_subset_unit_fmt, subset)
            } else {
                subset.toString()
            }
            canvas.drawText(label, x, baseline, textPaint)
        }
        if (!compact) {
            val title = context.getString(R.string.vsg_lattice_axis_subset_range)
            if (labelled.size > 1) {
                canvas.drawText(title, (f.left + f.right) / 2f, baseline, textPaint)
            } else {
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(title, f.left, baseline, textPaint)
            }
        }
        textPaint.textAlign = Paint.Align.LEFT
    }
}
