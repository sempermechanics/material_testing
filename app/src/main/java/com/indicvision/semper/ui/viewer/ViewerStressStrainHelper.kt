package com.indicvision.semper.ui.viewer

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.data.loadOfFrame
import com.indicvision.semper.report.ElasticModulus
import com.indicvision.semper.report.StressStrain
import com.indicvision.semper.ui.analysis.VsgPlotView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The stress–strain curve in the viewer: the Results on the summary page and
 * in the Details sheet, and the sheet's dimension / Load / Stress rows. The
 * curve needs the mean strain of every frame, so it is a full decode pass over
 * the batch — started only when a Results surface is shown, and cached in the
 * ViewModel so the other surface, a reopen, or the share sheet reuses it.
 */
class ViewerStressStrainHelper(
    private val host: ResultViewerActivity,
    private val vm: ResultViewerViewModel,
) {
    /** Where a curve is drawn: the Details sheet's section or the summary page. */
    class Views(val plot: VsgPlotView, val caption: TextView, val result: TextView)

    private var job: Job? = null

    /** Surfaces waiting for the running build; each is drawn when it lands. */
    private val waiting = mutableListOf<Views>()

    /** True when the session carries a load per frame — the only case with a curve. */
    val hasLoads: Boolean get() = host.loadsN.isNotEmpty()

    /** Whether this session has Results at all: loads, and not a parameter sweep. */
    val showsResults: Boolean get() = hasLoads && !host.isSweep

    /** Dimension, load, torque and stress rows for the frame on screen; none on the summary. */
    fun rows(): List<Pair<String, String>> {
        val loadN = host.loadsN.loadOfFrame(host.currentFrameIndex)
            ?.takeUnless { host.isShowingSummary }
            ?: return emptyList()
        val model = host.stressModel
        val stress = model.stressMPa(loadN)
        return buildList {
            model.dimensions.forEach { (dimension, value) ->
                if (value > 0f) add(row(dimensionLabelRes(dimension), unitRes(dimension), value))
            }
            add(row(R.string.setting_load, R.string.setting_n_fmt, loadN))
            if (!stress.isNaN()) add(row(stressLabelRes(model), R.string.setting_mpa_fmt, stress))
        }
    }

    private fun row(labelRes: Int, valueRes: Int, value: Float): Pair<String, String> =
        host.getString(labelRes) to host.getString(valueRes, fmt(value))

    /**
     * Fills the sheet's stress–strain section, building the curve first if it
     * is not cached. The sheet may be dismissed before the build finishes; the
     * views are then simply updated off screen.
     */
    fun populate(sheetView: View) {
        val section = sheetView.findViewById<View>(R.id.stressStrainSection)
        section.isVisible = showsResults
        if (!showsResults) return
        sheetView.findViewById<TextView>(R.id.tvStressStrainTitle).setText(R.string.results_title)
        fill(
            Views(
                sheetView.findViewById(R.id.plotStressStrain),
                sheetView.findViewById(R.id.tvStressStrainCaption),
                sheetView.findViewById(R.id.tvStressStrainResult),
            ),
        )
    }

    /** Draws the curve into [views], building it first if it is not cached. */
    fun fill(views: Views) {
        val cached = vm.stressStrain
        if (cached != null) {
            draw(views, cached)
        } else {
            build(views)
        }
    }

    private fun build(views: Views) {
        views.plot.isVisible = false
        views.result.isVisible = false
        views.caption.text = host.getString(R.string.stress_strain_progress_fmt, 0, host.loadsN.size)
        waiting += views
        if (job?.isActive == true) return
        job = host.lifecycleScope.launch {
            val files = host.summaryBatchFiles()
            val built = withContext(Dispatchers.Default) {
                StressStrain.build(
                    loadsN = host.loadsN.toList(),
                    model = host.stressModel,
                    frameData = { index -> files.getOrNull(index)?.let { DicResult.decodeDatFile(it) } },
                    onProgress = { done ->
                        host.lifecycleScope.launch(Dispatchers.Main.immediate) {
                            val text = host.getString(R.string.stress_strain_progress_fmt, done, host.loadsN.size)
                            waiting.forEach { it.caption.text = text }
                        }
                    },
                )
            }
            vm.stressStrain = built
            waiting.forEach { draw(it, built) }
            waiting.clear()
        }
    }

    fun cancel() {
        job?.cancel()
        waiting.clear()
    }

    private fun draw(views: Views, curve: StressStrain.Curve) {
        val plot = views.plot
        val caption = views.caption
        val result = views.result
        if (curve.isEmpty) {
            plot.isVisible = false
            result.isVisible = false
            caption.setText(R.string.stress_strain_empty)
            return
        }
        plot.isVisible = true
        plot.compactAxes = true
        // The summary is the whole test, so no frame is highlighted there.
        val current = if (host.isShowingSummary) null else curve.at(host.currentFrameIndex)
        val (xAxis, yAxis) = axisLabels(host, curve.model)
        val modulus = modulusOf(curve)
        val bending = curve.model.plotsLoadDeflection
        plot.setData(
            plotSeries(host, curve, modulus),
            xAxis,
            yAxis,
            highlightX = if (bending) current?.deflectionMm else current?.strainMilli,
            xUnit = if (bending) ViewerBendingResults.UNIT_DEFLECTION else StressStrain.UNIT_STRAIN,
            yUnit = if (bending) ViewerBendingResults.UNIT_LOAD else StressStrain.UNIT_STRESS,
        )
        caption.text = when {
            host.isShowingSummary -> host.resources.getQuantityString(
                if (bending) R.plurals.results_summary_caption_bending_fmt else R.plurals.results_summary_caption_fmt,
                host.loadsN.size,
                curve.points.size,
                host.loadsN.size,
            )
            current == null -> host.getString(R.string.stress_strain_frame_skipped)
            bending -> ViewerBendingResults.frameCaption(host, curve, current.frame)
                ?: host.getString(R.string.stress_strain_frame_skipped)
            else -> host.getString(
                R.string.stress_strain_caption_fmt,
                current.frame + 1,
                fmt(current.loadN),
                fmt(current.stressMPa),
                fmt(current.strainMilli),
            )
        }
        result.isVisible = true
        result.text = resultsText(host, curve, modulus)
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    companion object {
        /**
         * [context] with the day palette, for plots drawn into a PDF: the
         * page is always white, and night ink (light grey axis titles) would
         * vanish on it.
         */
        fun printContext(context: Context): Context {
            val light = Configuration(context.resources.configuration)
            light.uiMode = (light.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            return context.createConfigurationContext(light)
        }

        /** The tensile modulus fit, or null for a model that has none. */
        fun modulusOf(curve: StressStrain.Curve): ElasticModulus.Fit? =
            if (curve.model is StressStrain.Model.Axial) ElasticModulus.fit(curve) else null

        /**
         * The curve, plus — when there is a fit — the fitted elastic line as a
         * muted second series, drawn across the frames it was fitted to. A
         * bending curve with a load point is [ViewerBendingResults]' instead.
         */
        fun plotSeries(
            context: Context,
            curve: StressStrain.Curve,
            modulus: ElasticModulus.Fit?,
        ): List<VsgPlotView.Series> = if (curve.model.plotsLoadDeflection) {
            ViewerBendingResults.plotSeries(context, curve)
        } else {
            ViewerStressStrainResults.plotSeries(context, curve, modulus)
        }

        /**
         * The Results summary under the curve — what the lab report's Results
         * section asks for: E with the frames it came from, and the peak stress;
         * for bending with a load point, [ViewerBendingResults.resultsText].
         */
        fun resultsText(context: Context, curve: StressStrain.Curve, modulus: ElasticModulus.Fit?): String =
            if (curve.model.plotsLoadDeflection) {
                ViewerBendingResults.resultsText(context, curve)
            } else {
                ViewerStressStrainResults.resultsText(context, curve, modulus)
            }

        /** Plot axis titles (x, y) worded for the model: strain and stress, or deflection and load. */
        fun axisLabels(context: Context, model: StressStrain.Model): Pair<String, String> = when {
            model.plotsLoadDeflection -> ViewerBendingResults.axisLabels(context)
            else -> ViewerStressStrainResults.axisLabels(context, model)
        }

        fun stressLabelRes(model: StressStrain.Model): Int = when (model) {
            is StressStrain.Model.Axial -> R.string.setting_stress
            is StressStrain.Model.Flexural -> R.string.setting_stress_flexural
        }

        fun dimensionLabelRes(dimension: StressStrain.Dimension): Int = when (dimension) {
            StressStrain.Dimension.CROSS_SECTION -> R.string.setting_cross_section
            StressStrain.Dimension.SPAN -> R.string.setting_span
            StressStrain.Dimension.WIDTH -> R.string.setting_width
            StressStrain.Dimension.THICKNESS -> R.string.setting_thickness
        }

        private fun unitRes(dimension: StressStrain.Dimension): Int =
            if (dimension == StressStrain.Dimension.CROSS_SECTION) R.string.setting_mm2_fmt else R.string.setting_mm_fmt
    }
}
