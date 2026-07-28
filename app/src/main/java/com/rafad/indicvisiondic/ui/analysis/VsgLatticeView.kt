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
import kotlin.math.hypot

/**
 * The parameter space of a virtual strain gauge study, drawn as a 2-D lattice:
 * one node per analysis, subset size across the x-axis and virtual strain gauge
 * size up the y-axis. Because every subset carries its own VSG ladder, the
 * nodes fall into vertical columns — the lattice makes the shape of the sweep,
 * and which corners of it the engine could not solve, legible at a glance.
 *
 * Interactive: tapping a solved node opens that analysis via [onNodeClick].
 */
class VsgLatticeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /**
     * One analysis of the sweep. [solved] is false for a combination the engine
     * skipped; [frameIndex] is its position in the result viewer, or -1 when it
     * was skipped and has no frame to open.
     */
    data class Node(
        val subset: Int,
        val step: Int,
        val window: Int,
        val vsg: Int,
        val solved: Boolean,
        val frameIndex: Int = -1,
    )

    /** Invoked when a solved node is tapped. */
    var onNodeClick: ((Node) -> Unit)? = null

    /** Where each node was last drawn, for hit-testing taps. */
    private class Placed(val node: Node, val x: Float, val y: Float)
    private val placed = ArrayList<Placed>()

    private companion object {
        const val AXIS_LABEL_SP = 11f
        const val PAD_LEFT_DP = 44f
        const val PAD_RIGHT_DP = 14f
        const val PAD_TOP_DP = 14f
        const val PAD_BOTTOM_DP = 44f
        const val NODE_RADIUS_DP = 5f
        const val NODE_STROKE_DP = 2f
        const val CONNECTOR_DP = 1.5f
        const val GRID_DP = 1f
        const val TICK_GAP_DP = 5f
        const val Y_TICKS = 4

        /** Head-room above/below the VSG range so nodes are not clipped. */
        const val Y_MARGIN_FRACTION = 0.12f

        /** Baseline nudge that centres a tick label on its gridline. */
        const val TICK_BASELINE = 0.34f

        /** Half a column, so a column's nodes sit at its centre. */
        const val HALF_COLUMN = 0.5f

        /** How far below the x-axis ticks the axis title sits, in text heights. */
        const val AXIS_TITLE_OFFSET = 2.4f

        /** Tap tolerance around a node centre, in dp. */
        const val TOUCH_RADIUS_DP = 22f
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    /** Axis labels in px, scaled for the user's font-size setting. */
    private val axisLabelPx =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, AXIS_LABEL_SP, resources.displayMetrics)

    // Reused every draw — onDraw runs on each lattice interaction.
    private val columnX = HashMap<Int, Float>()
    private val frame = Frame()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(GRID_DP)
        color = ContextCompat.getColor(context, R.color.surface_outline)
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(CONNECTOR_DP)
        color = ContextCompat.getColor(context, R.color.sky_container)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(NODE_STROKE_DP)
    }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = axisLabelPx
        color = ContextCompat.getColor(context, R.color.text_secondary)
    }
    private val path = Path()

    private var nodes: List<Node> = emptyList()
    private var columns: List<Int> = emptyList()
    private var vsgMin = 0
    private var vsgMax = 1

    fun setNodes(nodes: List<Node>) {
        this.nodes = nodes
        columns = nodes.map { it.subset }.distinct().sorted()
        val vsgs = nodes.map { it.vsg }
        val lo = vsgs.minOrNull() ?: 0
        val hi = vsgs.maxOrNull() ?: 1
        val margin = ((hi - lo) * Y_MARGIN_FRACTION).toInt().coerceAtLeast(1)
        vsgMin = lo - margin
        vsgMax = if (hi > lo) hi + margin else lo + margin
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        placed.clear()
        if (nodes.isEmpty() || columns.isEmpty()) return

        val left = dp(PAD_LEFT_DP)
        val right = width - dp(PAD_RIGHT_DP)
        val top = dp(PAD_TOP_DP)
        val bottom = height - dp(PAD_BOTTOM_DP)
        if (right <= left || bottom <= top) return

        columnX.clear()
        columns.forEachIndexed { i, subset ->
            columnX[subset] = left + (i + HALF_COLUMN) / columns.size * (right - left)
        }
        frame.set(left, right, top, bottom)
        fun yFor(vsg: Int) = bottom - (vsg - vsgMin).toFloat() / (vsgMax - vsgMin) * (bottom - top)

        drawGrid(canvas, columnX, frame)
        drawConnectors(canvas, columnX, ::yFor)
        drawNodes(canvas, columnX, ::yFor)
        drawLabels(canvas, columnX, frame)
    }

    /** The node a press started on, so a tap and its release resolve to the same one. */
    private var pressedNode: Node? = null

    @Suppress("ReturnCount") // one branch per touch phase reads clearest
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            // Claim the gesture only when it starts on a node — otherwise a drag
            // that begins on the lattice still scrolls the page around it.
            MotionEvent.ACTION_DOWN -> {
                pressedNode = nodeAt(event.x, event.y)
                return pressedNode != null
            }
            MotionEvent.ACTION_UP -> {
                val up = nodeAt(event.x, event.y)
                if (up != null && up == pressedNode) {
                    performClick()
                    onNodeClick?.invoke(up)
                }
                pressedNode = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> pressedNode = null
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** The nearest solved node within the tap tolerance of (x, y), or null. */
    private fun nodeAt(x: Float, y: Float): Node? {
        val hit = placed
            .filter { it.node.solved }
            .minByOrNull { hypot(it.x - x, it.y - y) }
            ?: return null
        return if (hypot(hit.x - x, hit.y - y) <= dp(TOUCH_RADIUS_DP)) hit.node else null
    }

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

    private fun drawGrid(canvas: Canvas, columnX: Map<Int, Float>, f: Frame) {
        columnX.values.forEach { x -> canvas.drawLine(x, f.top, x, f.bottom, gridPaint) }
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        for (i in 0..Y_TICKS) {
            val y = f.bottom - (f.bottom - f.top) * i / Y_TICKS
            canvas.drawLine(f.left, y, f.right, y, gridPaint)
            val vsg = vsgMin + (vsgMax - vsgMin) * i / Y_TICKS
            val baseline = y + textPaint.textSize * TICK_BASELINE
            canvas.drawText(vsg.toString(), f.left - dp(TICK_GAP_DP), baseline, textPaint)
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

    private fun drawNodes(canvas: Canvas, columnX: Map<Int, Float>, yFor: (Int) -> Float) {
        val radius = dp(NODE_RADIUS_DP)
        val solved = ContextCompat.getColor(context, R.color.sky_primary)
        val skipped = ContextCompat.getColor(context, R.color.semantic_danger)
        nodes.forEach { node ->
            val x = columnX[node.subset] ?: return@forEach
            val y = yFor(node.vsg)
            placed.add(Placed(node, x, y))
            if (node.solved) {
                fillPaint.color = solved
                canvas.drawCircle(x, y, radius, fillPaint)
            } else {
                // Hollow red ring: a combination that was attempted and failed.
                canvas.drawCircle(x, y, radius, holePaint)
                strokePaint.color = skipped
                canvas.drawCircle(x, y, radius, strokePaint)
            }
        }
    }

    private fun drawLabels(canvas: Canvas, columnX: Map<Int, Float>, f: Frame) {
        textPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
        textPaint.textAlign = Paint.Align.CENTER
        columns.forEach { subset ->
            val x = columnX[subset] ?: return@forEach
            canvas.drawText(subset.toString(), x, f.bottom + textPaint.textSize + dp(TICK_GAP_DP), textPaint)
        }

        textPaint.color = ContextCompat.getColor(context, R.color.text_primary)
        canvas.drawText(
            context.getString(R.string.vsg_lattice_axis_subset),
            (f.left + f.right) / 2f,
            f.bottom + textPaint.textSize * AXIS_TITLE_OFFSET + dp(TICK_GAP_DP),
            textPaint,
        )
        canvas.save()
        val pivot = f.bottom / 2f
        canvas.rotate(-QUARTER_TURN, textPaint.textSize, pivot)
        canvas.drawText(context.getString(R.string.vsg_lattice_axis_vsg), textPaint.textSize, pivot, textPaint)
        canvas.restore()
        textPaint.textAlign = Paint.Align.LEFT
    }
}

private const val QUARTER_TURN = 90f
