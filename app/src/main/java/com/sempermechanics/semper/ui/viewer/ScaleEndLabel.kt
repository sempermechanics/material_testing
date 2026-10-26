package com.sempermechanics.semper.ui.viewer

import java.util.Locale

/**
 * The number on each end of the on-screen colour scale ("≥ 71.4 px"): three
 * significant figures, scientific below 1e-4 and from 1e3 up after rounding.
 * The ends are a display clamp, so five decimals ("71.42400") only added noise
 * (readiness AE1V-021). The custom-scale dialog, the ⓘ sheet, the PDF and the
 * summary GIF keep [com.sempermechanics.semper.report.ReportBuilder.formatMetric].
 */
internal object ScaleEndLabel {

    fun format(value: Float): String {
        // -0f would print "-0.00".
        val v = if (value == 0f) 0f else value
        return String.format(Locale.US, "%.3g", v)
    }
}
