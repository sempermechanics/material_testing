package com.sempermechanics.semper.ui.viewer.mechanical

import android.content.Context
import android.content.res.Configuration
import android.os.Trace
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.databinding.SheetSettingsUsedBinding
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.BeamDeflection
import com.sempermechanics.semper.report.ElasticModulus
import com.sempermechanics.semper.report.ElasticRegion
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.analysis.sweep.VsgPlotView
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.ResultViewerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.WeakHashMap

/**
 * The stress–strain curve in the viewer: the Results on the summary page and
 * in the Details sheet (its per-frame rows are [ViewerFrameRows]). The
 * curve needs the mean strain of every frame, so it is a full decode pass over
 * the batch — started only when a Results surface is shown, and cached in the
 * ViewModel so the other surface, a reopen, or the share sheet reuses it.
 */
class ViewerStressStrainHelper(
    private val host: ResultViewerActivity,
    private val vm: ResultViewerViewModel,
) {
    /**
     * Where a curve is drawn: the Details sheet's section or the summary page.
     * [range] is the Whole test / Elastic region toggle, inside its pill clip;
     * [adjust] opens the session's correction: bending's deflection scale and
     * bias, or the tensile curve's.
     */
    class Views(
        val plot: VsgPlotView,
        val caption: TextView,
        val result: TextView,
        val range: MaterialButtonToggleGroup,
        val adjust: MaterialButton,
    )

    private var job: Job? = null

    /** The surfaces drawn so far, by plot, so a new correction redraws what is on screen. */
    private val drawn = WeakHashMap<VsgPlotView, Views>()

    /** True while [bindRange] sets the toggle to match the ViewModel, so its listener ignores it. */
    private var syncingRange = false

    /** Surfaces waiting for the running build; each is drawn when it lands. */
    private val waiting = mutableListOf<Views>()

    /** True when the session carries a load per frame — the only case with a curve. */
    val hasLoads: Boolean get() = host.loadsN.isNotEmpty()

    /** Whether this session has Results at all: loads, and not a parameter sweep. */
    val showsResults: Boolean get() = hasLoads && !host.isSweep

    /**
     * Fills the sheet's stress–strain section, building the curve first if it
     * is not cached. The sheet may be dismissed before the build finishes; the
     * views are then simply updated off screen.
     */
    fun populate(sheet: SheetSettingsUsedBinding) {
        sheet.stressStrainSection.isVisible = showsResults
        if (!showsResults) return
        sheet.tvStressStrainTitle.setText(R.string.results_title)
        fill(
            Views(
                sheet.plotStressStrain,
                sheet.tvStressStrainCaption,
                sheet.tvStressStrainResult,
                sheet.toggleStressStrainRange,
                sheet.btnStressStrainAdjust,
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
        views.adjust.isVisible = false
        (views.range.parent as View).isVisible = false
        views.caption.text = host.getString(R.string.stress_strain_progress_fmt, 0, host.loadsN.size)
        waiting += views
        if (job?.isActive == true) return
        job = host.lifecycleScope.launch {
            val byFrame = SessionPaths.datByPlannedFrame(host.summaryBatchFiles())
            val built = withContext(Dispatchers.Default) {
                // Not suspending inside, so the section opens and closes on one thread.
                Trace.beginSection(TRACE_BUILD)
                try {
                    StressStrain.build(
                        loadsN = host.loadsN.toList(),
                        model = host.stressModel,
                        frameData = { frame -> byFrame[frame]?.let { DicResult.decodeDatFile(it) } },
                        onProgress = { done ->
                            host.lifecycleScope.launch(Dispatchers.Main.immediate) {
                                val text = host.getString(R.string.stress_strain_progress_fmt, done, host.loadsN.size)
                                waiting.forEach { it.caption.text = text }
                            }
                        },
                    )
                } finally {
                    Trace.endSection()
                }
            }
            // Either correction may have changed while the frames were read. The
            // tensile one first: it remaps from the model the curve was built with.
            val current = BeamDeflection.Correction.recorrect(
                StressStrain.recorrect(built, host.curveCorrection),
                host.stressModel,
            )
            vm.stressStrain = current
            waiting.forEach { draw(it, current) }
            waiting.clear()
        }
    }

    fun cancel() {
        job?.cancel()
        waiting.clear()
    }

    /** Redraws every surface on screen from the cached curve, after a deflection correction. */
    fun redraw() {
        val curve = vm.stressStrain ?: return
        drawn.values.toList().filter { it.plot.isAttachedToWindow }.forEach { draw(it, curve) }
    }

    private fun draw(views: Views, curve: StressStrain.Curve) {
        drawn[views.plot] = views
        val plot = views.plot
        val result = views.result
        // One button for both corrections: bending's deflection, or the tensile curve.
        val bending = curve.model.plotsLoadDeflection
        views.adjust.isVisible = (bending || curve.model is StressStrain.Model.Axial) && !curve.isEmpty
        views.adjust.setText(if (bending) R.string.deflection_adjust else R.string.curve_adjust)
        views.adjust.setOnClickListener {
            if (bending) ViewerDeflectionCorrection.show(host) else ViewerCurveCorrection.show(host)
        }
        if (curve.isEmpty) {
            plot.isVisible = false
            result.isVisible = false
            (views.range.parent as View).isVisible = false
            views.caption.setText(R.string.stress_strain_empty)
            return
        }
        plot.isVisible = true
        plot.compactAxes = true
        // The summary is the whole test, so no frame is highlighted there.
        val current = if (host.isShowingSummary) null else curve.at(host.plannedFrameIndex(host.currentFrameIndex))
        val (xAxis, yAxis) = axisLabels(host, curve.model)
        val modulus = modulusOf(curve)
        val region = modulus?.let { ElasticRegion.of(curve, it) }
        bindRange(views, shown = region != null)
        val zoomed = region?.takeIf { vm.showElasticRegion }
        plot.setData(
            zoomed?.let { ViewerStressStrainResults.elasticPlotSeries(host, it) } ?: plotSeries(host, curve, modulus),
            xAxis,
            yAxis,
            highlightX = if (bending) current?.deflectionMm else current?.strainMilli,
            xUnit = if (bending) ViewerBendingResults.UNIT_DEFLECTION else StressStrain.UNIT_STRAIN,
            yUnit = if (bending) ViewerBendingResults.UNIT_LOAD else StressStrain.UNIT_STRESS,
            marks = plotMarks(host, curve, modulus),
        )
        views.caption.text = caption(curve, current, modulus?.takeIf { zoomed != null })
        result.isVisible = true
        result.text = resultsText(host, curve, modulus)
    }

    /**
     * The Whole test / Elastic region toggle, [shown] only when there is an
     * elastic fit to zoom to. The choice lives in the ViewModel, so both
     * surfaces and a reopen keep it. The listener is attached once per view;
     * the [check] made here to match the ViewModel is not a tap, so it does
     * not redraw from inside a draw.
     */
    private fun bindRange(views: Views, shown: Boolean) {
        val group = views.range
        val clip = group.parent as View
        clip.clipToOutline = true
        clip.isVisible = shown
        if (group.tag == null) {
            group.tag = RANGE_BOUND
            group.addOnButtonCheckedListener { _, id, isChecked ->
                if (isChecked && !syncingRange) {
                    vm.showElasticRegion = id == R.id.btnRangeElastic
                    vm.stressStrain?.let { draw(views, it) }
                }
            }
        }
        syncingRange = true
        group.check(if (vm.showElasticRegion) R.id.btnRangeElastic else R.id.btnRangeWhole)
        syncingRange = false
    }

    /** The line under the plot; [zoomedFit] is the fit when the plot shows only its elastic region. */
    private fun caption(curve: StressStrain.Curve, current: StressStrain.Point?, zoomedFit: ElasticModulus.Fit?) =
        when {
            host.isShowingSummary && zoomedFit != null -> host.getString(
                R.string.results_elastic_caption_fmt,
                zoomedFit.firstFrame + 1,
                zoomedFit.lastFrame + 1,
            )
            host.isShowingSummary -> host.resources.getQuantityString(
                if (curve.model.plotsLoadDeflection) {
                    R.plurals.results_summary_caption_bending_fmt
                } else {
                    R.plurals.results_summary_caption_fmt
                },
                host.loadsN.size,
                curve.points.size,
                host.loadsN.size,
            )
            current == null -> host.getString(R.string.stress_strain_frame_skipped)
            curve.model.plotsLoadDeflection -> ViewerBendingResults.frameCaption(host, curve, current.frame)
                ?: host.getString(R.string.stress_strain_frame_skipped)
            else -> host.getString(
                R.string.stress_strain_caption_fmt,
                current.frame + 1,
                fmt(current.loadN),
                fmt(current.stressMPa),
                fmt(current.strainMilli),
            )
        }

    private fun fmt(value: Float): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')

    companion object {
        /** Marks a range toggle whose listener is attached. */
        private const val RANGE_BOUND = "range-bound"

        /** Trace section around the curve build; `LabResultsBenchmark` sums it. */
        const val TRACE_BUILD = "Semper.viewer.stressStrain"

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

        /** The yield point on a tensile curve's plot; none for bending. */
        fun plotMarks(
            context: Context,
            curve: StressStrain.Curve,
            modulus: ElasticModulus.Fit?,
        ): List<VsgPlotView.Mark> =
            if (curve.model.plotsLoadDeflection) {
                emptyList()
            } else {
                listOfNotNull(ViewerStressStrainResults.yieldMark(context, curve, modulus))
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
            is StressStrain.Model.Axial -> R.string.settings_used_stress
            is StressStrain.Model.Flexural -> R.string.settings_used_stress_flexural
        }

        fun dimensionLabelRes(dimension: StressStrain.Dimension): Int = when (dimension) {
            StressStrain.Dimension.CROSS_SECTION -> R.string.settings_used_cross_section
            StressStrain.Dimension.SPAN -> R.string.settings_used_span
            StressStrain.Dimension.WIDTH -> R.string.settings_used_width
            StressStrain.Dimension.THICKNESS -> R.string.settings_used_thickness
        }
    }
}
