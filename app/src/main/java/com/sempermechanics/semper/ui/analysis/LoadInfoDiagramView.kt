// A drawing: its geometry is literal fractions of the width, clearest inline,
// so the magic-number rule is suppressed for this file.
@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.analysis

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import com.indicvision.semper.R
import com.indicvision.semper.data.TestType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The machine-load ⓘ's picture of what the load card asks for. Tensile: the
 * round bar pulled along its loading axis, cut to show the cross-section
 * A = π·d²/4, and the photo's X / Y directions the strain-axis toggle picks
 * between. Bending: the simply supported beam with its span L, the hanger
 * load W and the deflection δ at mid-span, the b × t cross-section, and the
 * two edge taps that **Set beam height** asks for.
 *
 * Everything is laid out in hundredths of the width, so the drawing scales
 * with the dialog; the height follows from the width. Colours are the
 * `viewer_plot_*` tokens, which flip with the theme.
 */
class LoadInfoDiagramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Which test to draw; set once, before the dialog shows. */
    var testType: TestType = TestType.TENSILE
        set(value) {
            field = value
            val bending = value == TestType.BENDING
            contentDescription = context.getString(
                if (bending) R.string.info_diagram_desc_bending else R.string.info_diagram_desc_tensile,
            )
            requestLayout()
            invalidate()
        }

    private val ink = DiagramInk(context)
    private val tensile = TensileDiagram(ink)
    private val bending = BendingDiagram(ink)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val ratio = if (testType == TestType.BENDING) BENDING_HEIGHT else TENSILE_HEIGHT
        setMeasuredDimension(width, (width * ratio / 100f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = width / 100f
        if (testType == TestType.BENDING) bending.draw(canvas, u) else tensile.draw(canvas, u)
    }

    private companion object {
        /** Height as a percentage of the width. */
        const val TENSILE_HEIGHT = 66f
        const val BENDING_HEIGHT = 76f
    }
}

/** The paints, labels and arrowheads both drawings share. */
internal class DiagramInk(private val context: Context) {
    private val metrics = context.resources.displayMetrics
    private val density = metrics.density
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics)

    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = color(R.color.viewer_plot_ink_strong)
    }
    val dimension = Paint(line).apply {
        strokeWidth = 1f * density
        color = color(R.color.viewer_plot_ink)
    }
    val dashed = Paint(dimension).apply {
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.viewer_plot_grid) }
    val accent = Paint(line).apply {
        strokeWidth = 2f * density
        color = color(R.color.viewer_plot_node_solved)
    }
    val accentDashed = Paint(accent).apply {
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    val accentDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(R.color.viewer_plot_node_solved) }

    /** Symbols (d, L, W, b, t, δ, F, X, Y) set as in a textbook figure. */
    val symbol = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.viewer_plot_ink_strong)
        textSize = sp(14f)
        typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        textAlign = Paint.Align.CENTER
    }
    val symbolLeft = Paint(symbol).apply { textAlign = Paint.Align.LEFT }
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.viewer_plot_ink)
        textSize = sp(11f)
        textAlign = Paint.Align.CENTER
    }
    val labelLeft = Paint(label).apply { textAlign = Paint.Align.LEFT }
    val labelRight = Paint(label).apply { textAlign = Paint.Align.RIGHT }
    val accentLabel = Paint(label).apply { color = color(R.color.viewer_plot_node_solved) }
    val accentLabelRight = Paint(accentLabel).apply { textAlign = Paint.Align.RIGHT }

    private val head = 6f * density

    fun text(id: Int): String = context.getString(id)

    /** A line from (x1, y1) with an open arrowhead at (x2, y2). */
    fun Canvas.arrow(x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        drawLine(x1, y1, x2, y2, paint)
        val angle = atan2(y2 - y1, x2 - x1) + Math.PI.toFloat()
        drawLine(x2, y2, x2 + head * cos(angle - SPREAD), y2 + head * sin(angle - SPREAD), paint)
        drawLine(x2, y2, x2 + head * cos(angle + SPREAD), y2 + head * sin(angle + SPREAD), paint)
    }

    private companion object {
        const val SPREAD = 0.45f
    }
}

/** The round bar, its loading axis, its cross-section, and the photo's axes. */
internal class TensileDiagram(private val ink: DiagramInk) {

    fun draw(canvas: Canvas, u: Float) = with(ink) {
        val axisY = 16 * u
        drawBar(canvas, u, axisY)
        // The line the machine pulls along, through the bar's centre.
        canvas.drawLine(1 * u, axisY, 99 * u, axisY, accentDashed)
        canvas.drawText(text(R.string.info_diagram_loading_axis), 50 * u, axisY - 6 * u, accentLabel)
        canvas.arrow(8 * u, axisY, 1 * u, axisY, line)
        canvas.arrow(92 * u, axisY, 99 * u, axisY, line)
        canvas.drawText("F", 4 * u, axisY - 3 * u, symbol)
        canvas.drawText("F", 96 * u, axisY - 3 * u, symbol)
        drawCrossSection(canvas, u, axisY)
        drawPhotoAxes(canvas, u)
    }

    /** Grips at both ends, the narrower gauge length between them. */
    private fun drawBar(canvas: Canvas, u: Float, axisY: Float) = with(ink) {
        val grip = 5 * u
        val gauge = 3 * u
        for ((from, to) in GRIPS) {
            canvas.drawRect(from * u, axisY - grip, to * u, axisY + grip, fill)
            canvas.drawRect(from * u, axisY - grip, to * u, axisY + grip, line)
        }
        canvas.drawRect(20 * u, axisY - gauge, 80 * u, axisY + gauge, fill)
        canvas.drawLine(20 * u, axisY - gauge, 80 * u, axisY - gauge, line)
        canvas.drawLine(20 * u, axisY + gauge, 80 * u, axisY + gauge, line)
    }

    /** A cut through the gauge length, opened out below the bar as its round face. */
    private fun drawCrossSection(canvas: Canvas, u: Float, axisY: Float) = with(ink) {
        val cx = 50 * u
        val cy = 42 * u
        val r = 10 * u
        canvas.drawLine(cx, axisY + 4 * u, cx, cy - r - 1 * u, dashed)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, line)
        canvas.arrow(cx, cy, cx - r, cy, dimension)
        canvas.arrow(cx, cy, cx + r, cy, dimension)
        canvas.drawText("d", cx, cy - 2 * u, symbol)
        canvas.drawText("A = π·d²/4", cx + r + 3 * u, cy + 1.5f * u, symbolLeft)
        canvas.drawText(text(R.string.info_diagram_cross_section), cx, cy + r + 6 * u, label)
    }

    /** The photo's X (across) and Y (down), which the strain-axis toggle chooses between. */
    private fun drawPhotoAxes(canvas: Canvas, u: Float) = with(ink) {
        val ox = 5 * u
        val oy = 40 * u
        canvas.arrow(ox, oy, ox + 12 * u, oy, dimension)
        canvas.arrow(ox, oy, ox, oy + 12 * u, dimension)
        canvas.drawText("X", ox + 15 * u, oy + 1.5f * u, symbol)
        canvas.drawText("Y", ox + 3.5f * u, oy + 13 * u, symbol)
        canvas.drawText(text(R.string.info_diagram_photo), ox, oy - 3 * u, labelLeft)
    }

    private companion object {
        val GRIPS = listOf(8f to 20f, 80f to 92f)
    }
}

/** The beam on its supports, W and δ at mid-span, the b × t section, and the beam-height taps. */
internal class BendingDiagram(private val ink: DiagramInk) {
    private val path = Path()

    fun draw(canvas: Canvas, u: Float) = with(ink) {
        val top = 22 * u
        val bottom = 30 * u
        canvas.drawRect(6 * u, top, 94 * u, bottom, fill)
        canvas.drawRect(6 * u, top, 94 * u, bottom, line)
        support(canvas, u, 12 * u, bottom)
        support(canvas, u, 88 * u, bottom)
        // Hanger load at mid-span, and the deflection it causes there.
        canvas.arrow(50 * u, 5 * u, 50 * u, top - 0.5f * u, line)
        canvas.drawText("W", 55 * u, 12 * u, symbol)
        canvas.arrow(57 * u, bottom + 0.5f * u, 57 * u, bottom + 5 * u, dimension)
        canvas.drawText("δ", 61 * u, bottom + 5 * u, symbol)
        drawBeamHeight(canvas, u, top, bottom)
        drawSpan(canvas, u, bottom + 8 * u)
        drawSection(canvas, u)
    }

    private fun support(canvas: Canvas, u: Float, x: Float, y: Float) {
        path.reset()
        path.moveTo(x, y)
        path.lineTo(x - 4 * u, y + 7 * u)
        path.lineTo(x + 4 * u, y + 7 * u)
        path.close()
        canvas.drawPath(path, ink.fill)
        canvas.drawPath(path, ink.line)
    }

    /** The two taps under the load: the top and bottom edges, t apart in the photo. */
    private fun drawBeamHeight(canvas: Canvas, u: Float, top: Float, bottom: Float) = with(ink) {
        val x = 44 * u
        canvas.drawLine(x, top, x, bottom, accent)
        canvas.drawCircle(x, top, 1.4f * u, accentDot)
        canvas.drawCircle(x, bottom, 1.4f * u, accentDot)
        canvas.drawText(text(R.string.info_diagram_beam_height), x - 2 * u, top - 3 * u, accentLabelRight)
    }

    /** L: centre to centre of the supports. */
    private fun drawSpan(canvas: Canvas, u: Float, y: Float) = with(ink) {
        canvas.drawLine(12 * u, y - 3 * u, 12 * u, y + 2 * u, dimension)
        canvas.drawLine(88 * u, y - 3 * u, 88 * u, y + 2 * u, dimension)
        canvas.arrow(50 * u, y, 12 * u, y, dimension)
        canvas.arrow(50 * u, y, 88 * u, y, dimension)
        canvas.drawText("L", 50 * u, y + 5.5f * u, symbol)
        canvas.drawText(text(R.string.info_diagram_span), 30 * u, y + 5 * u, label)
    }

    /** The beam cut across: width b by thickness t. */
    private fun drawSection(canvas: Canvas, u: Float) = with(ink) {
        val left = 64 * u
        val right = 86 * u
        val top = 56 * u
        val bottom = 64 * u
        val midX = (left + right) / 2
        val midY = (top + bottom) / 2
        canvas.drawRect(left, top, right, bottom, fill)
        canvas.drawRect(left, top, right, bottom, line)
        canvas.arrow(midX, bottom + 3 * u, left, bottom + 3 * u, dimension)
        canvas.arrow(midX, bottom + 3 * u, right, bottom + 3 * u, dimension)
        canvas.drawText("b", midX, bottom + 8.5f * u, symbol)
        canvas.arrow(right + 3 * u, midY, right + 3 * u, top, dimension)
        canvas.arrow(right + 3 * u, midY, right + 3 * u, bottom, dimension)
        canvas.drawText("t", right + 7 * u, midY + 1.5f * u, symbol)
        canvas.drawText(text(R.string.info_diagram_cross_section), left - 3 * u, bottom - 1 * u, labelRight)
    }
}
