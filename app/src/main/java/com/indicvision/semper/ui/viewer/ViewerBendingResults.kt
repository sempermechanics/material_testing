package com.indicvision.semper.ui.viewer

import android.content.Context
import com.indicvision.semper.R
import com.indicvision.semper.report.BeamDeflection
import com.indicvision.semper.report.StressStrain
import com.indicvision.semper.ui.analysis.VsgPlotView
import java.util.Locale

/**
 * Bending's Results, for a curve whose model
 * [plots load on deflection][StressStrain.Model.plotsLoadDeflection]: the
 * load–deflection graph with its slope line, and the summary the bending
 * lab's Results section asks for — E averaged over the steps, E from the
 * graph's slope, and the photo's scale. [ViewerStressStrainHelper] hands a
 * bending curve here, so the viewer, the share sheet and the DIC report all
 * draw the same thing.
 */
object ViewerBendingResults {

    const val UNIT_DEFLECTION = "mm"
    const val UNIT_LOAD = "N"

    fun axisLabels(context: Context): Pair<String, String> =
        context.getString(R.string.bending_axis_deflection) to context.getString(R.string.bending_axis_load)

    /** Load on deflection, and the fitted slope as a muted line across the measured δ. */
    fun plotSeries(context: Context, curve: StressStrain.Curve): List<VsgPlotView.Series> = buildList {
        add(
            VsgPlotView.Series(
                label = context.getString(R.string.bending_curve_title),
                color = VsgPlotView.paletteColor(context, 0),
                points = curve.plotPoints(),
            ),
        )
        val summary = BeamDeflection.summarize(curve) ?: return@buildList
        val line = summary.slope ?: return@buildList
        val from = summary.steps.minOf { it.deflectionMm }.coerceAtMost(0f).toDouble()
        val to = summary.steps.maxOf { it.deflectionMm }.toDouble()
        add(
            VsgPlotView.Series(
                label = context.getString(R.string.bending_slope_label),
                color = VsgPlotView.paletteColor(context, 1),
                points = listOf(from.toFloat() to line.at(from).toFloat(), to.toFloat() to line.at(to).toFloat()),
                markers = false,
                muted = true,
            ),
        )
    }

    /** The lab's Results lines: E from the graph, the mean E, the scale, and the caution. */
    fun resultsText(context: Context, curve: StressStrain.Curve): String {
        val summary = BeamDeflection.summarize(curve) ?: return context.getString(R.string.bending_no_deflection)
        return buildList {
            val slope = summary.slope
            val slopeE = summary.slopeModulusGPa
            add(
                if (slope == null || slopeE == null) {
                    context.getString(R.string.bending_modulus_slope_none)
                } else {
                    context.getString(
                        R.string.bending_modulus_slope_fmt,
                        gpa(slopeE),
                        String.format(Locale.US, "%.2f", slope.slope),
                        String.format(Locale.US, "%.4f", slope.r2),
                    )
                },
            )
            summary.meanModulusGPa?.let { mean ->
                val used = summary.loadSteps.count { it.modulusGPa != null }
                add(context.resources.getQuantityString(R.plurals.bending_modulus_mean_fmt, used, gpa(mean), used))
            }
            add(context.getString(R.string.bending_scale_fmt, String.format(Locale.US, "%.4f", summary.mmPerPx)))
            add(context.getString(R.string.bending_caution))
        }.joinToString("\n")
    }

    /** Caption for the frame on screen: its load, δ, σb and that step's E. */
    fun frameCaption(context: Context, curve: StressStrain.Curve, frame: Int): String? {
        val step = BeamDeflection.summarize(curve)?.steps?.firstOrNull { it.frame == frame } ?: return null
        return context.getString(
            R.string.bending_frame_caption_fmt,
            frame + 1,
            String.format(Locale.US, "%.1f", step.loadN),
            String.format(Locale.US, "%.3f", step.deflectionMm),
            String.format(Locale.US, "%.2f", step.stressMPa),
            step.modulusGPa?.let(::gpa) ?: "—",
        )
    }

    private fun gpa(value: Float): String = String.format(Locale.US, "%.1f", value)
}
