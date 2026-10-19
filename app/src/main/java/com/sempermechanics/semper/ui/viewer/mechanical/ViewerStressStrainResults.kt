package com.sempermechanics.semper.ui.viewer.mechanical

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.report.ElasticRegion
import com.sempermechanics.semper.report.LabReportFormat
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.report.TensileResults
import com.sempermechanics.semper.ui.analysis.sweep.SweepPlotView
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
        results: TensileResults?,
    ): List<SweepPlotView.Series> = buildList {
        add(
            SweepPlotView.Series(
                label = context.getString(R.string.stress_strain_title),
                color = SweepPlotView.paletteColor(context, 0),
                points = curve.plotPoints(),
            ),
        )
        results?.fitLine?.let { line ->
            add(
                SweepPlotView.Series(
                    label = context.getString(R.string.modulus_fit_label),
                    color = SweepPlotView.paletteColor(context, 1),
                    points = line,
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
    fun elasticPlotSeries(context: Context, region: ElasticRegion.Plot): List<SweepPlotView.Series> = listOf(
        SweepPlotView.Series(
            label = context.getString(R.string.stress_strain_title),
            color = SweepPlotView.paletteColor(context, 0),
            points = region.points,
        ),
        SweepPlotView.Series(
            label = context.getString(R.string.modulus_fit_label),
            color = SweepPlotView.paletteColor(context, 1),
            points = region.line,
            markers = false,
            muted = true,
        ),
    )

    /**
     * The 0.2 % offset yield point as a plot [SweepPlotView.Mark], or null
     * without a fit or before the curve yields. The plot drops it when the
     * view (the elastic region) does not reach it.
     */
    fun yieldMark(context: Context, results: TensileResults?): SweepPlotView.Mark? {
        val point = results?.yieldPoint ?: return null
        return SweepPlotView.Mark(
            x = point.strainMilli,
            y = point.stressMPa,
            label = context.getString(R.string.results_yield_mark_fmt, one(point.stressMPa)),
            color = SweepPlotView.paletteColor(context, 1),
        )
    }

    fun resultsText(context: Context, curve: StressStrain.Curve, results: TensileResults?): String = buildList {
        val modulus = results?.modulus
        if (curve.model is StressStrain.Model.Axial) {
            add(
                if (modulus == null) {
                    context.getString(R.string.modulus_tensile_none)
                } else {
                    context.getString(
                        R.string.modulus_tensile_fmt,
                        LabReportFormat.gpa(modulus.modulusGPa),
                        modulus.firstFrame + 1,
                        modulus.lastFrame + 1,
                        String.format(Locale.US, "%.4f", modulus.r2),
                    )
                },
            )
            if (modulus != null && modulus.modulusGPa <= 0f) {
                add(context.getString(R.string.modulus_sign_caution))
            }
            results?.let { yieldText(context, it) }?.let(::add)
        }
        curve.peakStress?.let { peak ->
            add(
                context.getString(
                    if (peak.onCurve) R.string.results_peak_stress_fmt else R.string.results_peak_stress_off_curve_fmt,
                    String.format(Locale.US, "%.2f", peak.stressMPa),
                    peak.frame + 1,
                ),
            )
        }
    }.joinToString("\n")

    /** Rp0.2 with the frames it lies between, or why there is none; null without a positive E. */
    private fun yieldText(context: Context, results: TensileResults): String? {
        val modulus = results.modulus
        if (modulus == null || modulus.modulusGPa <= 0f) return null
        return results.yieldPoint?.let { point ->
            context.getString(
                R.string.results_yield_fmt,
                one(point.stressMPa),
                String.format(Locale.US, "%.2f", point.strainMilli),
                point.frameBefore + 1,
                point.frameAfter + 1,
            )
        } ?: context.getString(R.string.results_yield_none)
    }

    private fun one(value: Float): String = String.format(Locale.US, "%.1f", value)

    fun axisLabels(context: Context, model: StressStrain.Model): Pair<String, String> = when (model) {
        is StressStrain.Model.Axial ->
            context.getString(R.string.stress_strain_axis_strain) to
                context.getString(R.string.stress_strain_axis_stress)
        is StressStrain.Model.Flexural ->
            context.getString(R.string.stress_strain_axis_strain) to
                context.getString(R.string.stress_strain_axis_flexural_stress)
    }
}
