package com.sempermechanics.semper.ui.analysis.sweep

import android.graphics.Paint
import android.graphics.RectF

/**
 * The [SweepLatticeView]'s plot area. Its gutters grow with the label text: at
 * font scale 2.0 a fixed 30 dp cut the y tick "156" to "56" and the x labels off
 * the bottom (readiness AE1V-010). The left gutter fits the widest y tick, the
 * bottom one a row of x labels, and in [compact] mode the top one the y title.
 * At font scale 1.0 the gutters are the view's old fixed ones.
 */
internal class SweepLatticeGutters(private val density: Float) {

    /** The view's compact mode, set before each [frame]. */
    var compact: Boolean = false

    /** Sets [out] to the plot area of a [w] x [h] view; false when none is left. */
    fun frame(out: RectF, paint: Paint, yLabels: List<String>, w: Int, h: Int): Boolean {
        val gap = TICK_GAP_DP * density
        val widest = yLabels.maxOfOrNull { paint.measureText(it) } ?: 0f
        val left = if (compact) maxOf(PAD_LEFT_COMPACT_DP * density, widest + gap * 2) else widest + gap * 2
        val top = if (compact) maxOf(PAD_TOP_DP * density, paint.textSize * TITLE_ROWS + gap) else PAD_TOP_DP * density
        val bottom = h - maxOf(PAD_BOTTOM_DP * density, paint.textSize * X_LABEL_ROWS + gap)
        val right = w - PAD_RIGHT_DP * density
        out.set(left, top, right, bottom)
        return right > left && bottom > top
    }

    companion object {
        /** [compact] gutters' floor (result lattice): tick labels only, unit folded in. */
        const val PAD_LEFT_COMPACT_DP = 30f
        const val PAD_BOTTOM_DP = 20f
        const val PAD_RIGHT_DP = 14f
        const val PAD_TOP_DP = 14f
        const val TICK_GAP_DP = PlotStyle.LATTICE_TICK_GAP_DP

        /** Rows of y ticks in [compact] mode, above the bottom one. */
        const val Y_TICKS = 4

        /** Text heights above the frame in compact mode: the y title, then half the top tick label. */
        const val TITLE_ROWS = 2f

        /** Text heights under the frame: one row of x labels with its descent. */
        const val X_LABEL_ROWS = 1.3f

        /** A tick label's height, ascent to descent, in text sizes. */
        const val TICK_ROW_TEXT_SIZES = 1.2f

        /** Label strides that divide [Y_TICKS], so the bottom and top rows stay labelled. */
        private val TICK_STRIDES = intArrayOf(1, 2, 4)

        /**
         * Every how many y rows, [spacing] apart, a label of [textSize] fits
         * without touching the next: 1 at font scale 1.0, every other row when
         * a large font scale leaves less than a label's height between rows.
         */
        fun tickStride(spacing: Float, textSize: Float): Int =
            TICK_STRIDES.firstOrNull { spacing * it >= textSize * TICK_ROW_TEXT_SIZES } ?: Y_TICKS

        /**
         * The y tick labels, bottom to top: in [compact] [Y_TICKS] even steps of the
         * framed range [winMin]..[winMax], else the lowest and highest VSG swept.
         */
        fun yTickLabels(compact: Boolean, winMin: Int, winMax: Int, vsgLo: Int, vsgHi: Int): List<String> =
            if (compact) {
                (0..Y_TICKS).map { i -> (winMin + (winMax - winMin) * i / Y_TICKS).toString() }
            } else {
                listOf(vsgLo, vsgHi).distinct().map { it.toString() }
            }
    }
}
