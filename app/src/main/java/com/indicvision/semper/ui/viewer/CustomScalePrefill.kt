package com.indicvision.semper.ui.viewer

import com.indicvision.semper.report.ReportBuilder

/**
 * What the custom colour-scale dialog opens with: the field's custom bounds when
 * it has some, otherwise the auto bounds the scale bar is showing, written the
 * way the bar writes them, so Apply with no edits keeps the scale as it is.
 */
internal object CustomScalePrefill {

    /**
     * @param custom the field's custom bounds in stored units, if any.
     * @param shownMin @param shownMax the bounds of the heatmap on screen, or
     *   null when none is (the summary shows a whole-sequence scale instead).
     * @param multiplier stored units to display units (strain to mε).
     * @return (min, max) text, or null to leave the fields empty.
     */
    fun text(
        custom: Pair<Float, Float>?,
        shownMin: Float?,
        shownMax: Float?,
        multiplier: Float,
    ): Pair<String, String>? {
        val (lo, hi) = custom ?: shownRange(shownMin, shownMax) ?: return null
        return ReportBuilder.formatMetric(lo * multiplier) to ReportBuilder.formatMetric(hi * multiplier)
    }

    private fun shownRange(min: Float?, max: Float?): Pair<Float, Float>? {
        if (min == null || max == null) return null
        return (min to max).takeIf { min.isFinite() && max.isFinite() && max > min }
    }
}
