package com.indicvision.semper.ui.viewer

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.report.StressStrain
import com.indicvision.semper.ui.analysis.VsgPlotView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The stress–strain curve in the viewer: the Details sheet's plot and its
 * dimension / Load / Stress rows. The curve needs the mean strain of every
 * frame, so it is a full decode pass over the batch — started only from the
 * sheet, never on viewer open (the same rule as the summary's colour scan),
 * and cached in the ViewModel so a second open, or the share sheet, reuses it.
 */
class ViewerStressStrainHelper(
    private val host: ResultViewerActivity,
    private val vm: ResultViewerViewModel,
) {
    private var job: Job? = null

    /** True when the session carries a load per frame — the only case with a curve. */
    val hasLoads: Boolean get() = host.loadsN.isNotEmpty()

    /** Dimension, load, torque and stress rows for the frame on screen; none on the summary. */
    fun rows(): List<Pair<String, String>> {
        val loadN = host.loadsN.getOrNull(host.currentFrameIndex)
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
        val shown = hasLoads && !host.isSweep
        section.isVisible = shown
        if (!shown) return
        sheetView.findViewById<TextView>(R.id.tvStressStrainTitle).setText(R.string.stress_strain_title)
        val cached = vm.stressStrain
        if (cached != null) {
            draw(sheetView, cached)
        } else {
            build(sheetView)
        }
    }

    private fun build(sheetView: View) {
        val caption = sheetView.findViewById<TextView>(R.id.tvStressStrainCaption)
        sheetView.findViewById<View>(R.id.plotStressStrain).isVisible = false
        caption.text = host.getString(R.string.stress_strain_progress_fmt, 0, host.loadsN.size)
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
                            caption.text = host.getString(R.string.stress_strain_progress_fmt, done, host.loadsN.size)
                        }
                    },
                )
            }
            vm.stressStrain = built
            draw(sheetView, built)
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun draw(sheetView: View, curve: StressStrain.Curve) {
        val plot = sheetView.findViewById<VsgPlotView>(R.id.plotStressStrain)
        val caption = sheetView.findViewById<TextView>(R.id.tvStressStrainCaption)
        if (curve.isEmpty) {
            plot.isVisible = false
            caption.setText(R.string.stress_strain_empty)
            return
        }
        plot.isVisible = true
        plot.compactAxes = true
        val current = curve.at(host.currentFrameIndex)
        val (strainAxis, stressAxis) = axisLabels(host, curve.model)
        plot.setData(
            listOf(
                VsgPlotView.Series(
                    label = host.getString(R.string.stress_strain_title),
                    color = VsgPlotView.paletteColor(host, 0),
                    points = curve.plotPoints(),
                ),
            ),
            strainAxis,
            stressAxis,
            highlightX = current?.strainMilli,
            xUnit = StressStrain.UNIT_STRAIN,
            yUnit = StressStrain.UNIT_STRESS,
        )
        caption.text = if (current == null) {
            host.getString(R.string.stress_strain_frame_skipped)
        } else {
            host.getString(
                R.string.stress_strain_caption_fmt,
                current.frame + 1,
                fmt(current.loadN),
                fmt(current.stressMPa),
                fmt(current.strainMilli),
            )
        }
    }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    companion object {
        /** Plot axis titles (strain, stress) worded for the model. */
        fun axisLabels(context: Context, model: StressStrain.Model): Pair<String, String> = when (model) {
            is StressStrain.Model.Axial ->
                context.getString(R.string.stress_strain_axis_strain) to
                    context.getString(R.string.stress_strain_axis_stress)
            is StressStrain.Model.Flexural ->
                context.getString(R.string.stress_strain_axis_strain) to
                    context.getString(R.string.stress_strain_axis_flexural_stress)
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
