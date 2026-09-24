package com.indicvision.semper.ui.viewer

import android.content.Context
import com.indicvision.semper.R
import com.indicvision.semper.report.ElasticModulus
import com.indicvision.semper.report.ElasticRegion
import com.indicvision.semper.report.StressStrain
import com.indicvision.semper.ui.analysis.VsgPlotView
import java.util.Locale

/**
 * Results for a stress–strain curve — tensile, or bending without a load
 * point: the curve with the fitted elastic line, and the summary the tensile
 * lab's Results section asks for (E with the frames it came from, and the peak
 * stress). [ViewerStressStrainHelper] hands every other curve to
 * [ViewerBendingResults].
 */
object ViewerStressStrainResults {

    /** The curve, plus — when there is a fit — the fitted line across the frames it was fitted to. */
    fun plotSeries(
        context: Context,
        curve: StressStrain.Curve,
        modulus: ElasticModulus.Fit?,
    ): List<VsgPlotView.Series> = buildList {
        add(
            VsgPlotView.Series(
                label = context.getString(R.string.stress_strain_title),
                color = VsgPlotView.paletteColor(context, 0),
                points = curve.plotPoints(),
            ),
        )
        val fitted = modulus?.let { fit -> curve.points.filter { fit.covers(it.frame) } }.orEmpty()
        if (modulus != null && fitted.isNotEmpty()) {
            val from = fitted.minOf { it.strainMilli }
            val to = fitted.maxOf { it.strainMilli }
            add(
                VsgPlotView.Series(
                    label = context.getString(R.string.modulus_fit_label),
                    color = VsgPlotView.paletteColor(context, 1),
                    points = listOf(from to modulus.stressAt(from), to to modulus.stressAt(to)),
                    markers = false,
                    muted = true,
                ),
            )
        }
    }

    /**
     * The elastic region zoomed in ([ElasticRegion]): the curve there, and the
     * fitted line across it — the viewer's copy of the lab report's elastic graph.
     */
    fun elasticPlotSeries(context: Context, region: ElasticRegion.Plot): List<VsgPlotView.Series> = listOf(
        VsgPlotView.Series(
            label = context.getString(R.string.stress_strain_title),
            color = VsgPlotView.paletteColor(context, 0),
            points = region.points,
        ),
        VsgPlotView.Series(
            label = context.getString(R.string.modulus_fit_label),
            color = VsgPlotView.paletteColor(context, 1),
            points = region.line,
            markers = false,
            muted = true,
        ),
    )

    fun resultsText(context: Context, curve: StressStrain.Curve, modulus: ElasticModulus.Fit?): String = buildList {
        if (curve.model is StressStrain.Model.Axial) {
            add(
                if (modulus == null) {
                    context.getString(R.string.modulus_tensile_none)
                } else {
                    context.getString(
                        R.string.modulus_tensile_fmt,
                        String.format(Locale.US, "%.1f", modulus.modulusGPa),
                        modulus.firstFrame + 1,
                        modulus.lastFrame + 1,
                        String.format(Locale.US, "%.4f", modulus.r2),
                    )
                },
            )
            if (modulus != null && modulus.modulusGPa <= 0f) {
                add(context.getString(R.string.modulus_sign_caution))
            }
        }
        curve.peak?.let { peak ->
            add(
                context.getString(
                    R.string.results_peak_stress_fmt,
                    String.format(Locale.US, "%.2f", peak.stressMPa),
                    peak.frame + 1,
                ),
            )
        }
    }.joinToString("\n")

    fun axisLabels(context: Context, model: StressStrain.Model): Pair<String, String> = when (model) {
        is StressStrain.Model.Axial ->
            context.getString(R.string.stress_strain_axis_strain) to
                context.getString(R.string.stress_strain_axis_stress)
        is StressStrain.Model.Flexural ->
            context.getString(R.string.stress_strain_axis_strain) to
                context.getString(R.string.stress_strain_axis_flexural_stress)
    }
}
