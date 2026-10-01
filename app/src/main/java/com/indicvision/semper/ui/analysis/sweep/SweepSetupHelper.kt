package com.indicvision.semper.ui.analysis.sweep

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.RangeSlider
import com.indicvision.semper.R
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.databinding.DialogSweepFramePickBinding
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.imaging.RawRgba
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.common.Dialogs
import com.indicvision.semper.ui.common.SerialJob
import com.indicvision.semper.ui.common.WarnChip
import com.indicvision.semper.ui.common.bindInfo
import com.indicvision.semper.ui.common.commitOnDone
import com.indicvision.semper.ui.common.dp
import com.indicvision.semper.ui.common.onButtonChecked
import com.indicvision.semper.ui.common.showUnlessEditing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Parameter-sweep setup UI for the analysis wizard (§5.4.5 parameter sweep): mode
 * toggle, subset/VSG/sample fields, lattice + line-cut previews, and plan
 * summary. Orchestration ([startVsgSweep], progress, lifecycle) stays in the
 * Activity. The range inputs are [SweepRangeFields]; the frame dialog is
 * [SweepFramePicker].
 */
@Suppress("TooManyFunctions") // the wizard-facing API StaticAnalysisActivity calls, and its Callbacks
class SweepSetupHelper(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun goToStep(step: Int, animate: Boolean)
        fun updateWizardChrome()
        fun checkReady()

        /**
         * No longer called: the sweep's "i" buttons open [Dialogs.info]
         * themselves ([bindInfo]). Kept so the wizard's implementation still
         * compiles; it goes when the wizard drops it.
         */
        fun showInfo(titleRes: Int, bodyRes: Int)
        fun commitParamFields()
        fun startVsgSweep()
        fun currentSubsetSize(): Int
        fun maxSubsetForRoi(): Int
        fun refPreviewBitmap(): Bitmap?
        fun renderParamField(field: EditText, value: Int)
        fun confirmOpenFaq(url: String)
    }

    companion object {
        /** Width of the subset window a fresh sweep suggests, centred on the recommendation. */
        const val SUGGESTED_SUBSET_SPAN = 20

        /** Hard bounds on strain window input, in data points — the guardrail against a mistyped huge number. */
        const val STRAIN_WIN_MIN_INPUT = VsgStudy.MIN_WINDOW_POINTS
        const val STRAIN_WIN_MAX_INPUT = VsgStudy.MAX_WINDOW_POINTS

        /** The wizard's settings and sweep pages ([AnalysisViewModel.wizardStep]). */
        private const val SETTINGS_STEP = 2
        private const val SWEEP_STEP = 3
    }

    private lateinit var rgAnalysisMode: MaterialButtonToggleGroup
    private lateinit var advancedParamsCard: View
    private lateinit var sweepSettingsCard: View
    private lateinit var rgLineCutAxis: MaterialButtonToggleGroup
    private lateinit var btnPickSweepFrame: Button
    private lateinit var tvSweepPlan: TextView
    private lateinit var sweepPlanWarn: WarnChip
    private lateinit var lineCutPreview: LineCutPreviewView
    private lateinit var sweepLatticePreview: VsgLatticeView
    lateinit var btnRunSweep: Button
        private set
    private lateinit var latticeSamplesBody: View

    /** The subset, window, step and sample inputs; null until [setup]. */
    private var rangeFields: SweepRangeFields? = null

    private val framePicker = SweepFramePicker(activity, viewModel) { picked ->
        viewModel.vsgFrameIndex = picked
        refreshSweepPlan()
    }

    fun setup() {
        rgAnalysisMode = activity.findViewById(R.id.rgAnalysisMode)
        advancedParamsCard = activity.findViewById(R.id.advancedParamsCard)
        sweepSettingsCard = activity.findViewById(R.id.sweepSettingsCard)
        rgLineCutAxis = activity.findViewById(R.id.rgLineCutAxis)
        btnPickSweepFrame = activity.findViewById(R.id.btnPickSweepFrame)
        tvSweepPlan = activity.findViewById(R.id.tvSweepPlan)
        sweepPlanWarn = WarnChip(activity.findViewById(R.id.sweepPlanWarnRow), callbacks::confirmOpenFaq)
        lineCutPreview = activity.findViewById(R.id.lineCutPreview)
        sweepLatticePreview = activity.findViewById(R.id.sweepLatticePreview)
        // Same compact axes as the result lattice, now that the preview is the
        // same 136dp height -- full/default mode needs more room than that.
        sweepLatticePreview.compact = true
        btnRunSweep = activity.findViewById(R.id.btnRunSweep)
        latticeSamplesBody = activity.findViewById(R.id.latticeSamplesBody)
        val fields = SweepRangeFields(activity, viewModel, callbacks, onChanged = ::refreshSweepPlan)
        rangeFields = fields

        rgAnalysisMode.check(if (viewModel.sweepMode) R.id.rbModeSweep else R.id.rbModeSingle)
        rgAnalysisMode.onButtonChecked { checkedId ->
            viewModel.sweepMode = checkedId == R.id.rbModeSweep
            // Leaving sweep mode while on the sweep page returns to settings.
            if (!viewModel.sweepMode && viewModel.wizardStep == SWEEP_STEP) {
                callbacks.goToStep(SETTINGS_STEP, animate = true)
            } else {
                applyAnalysisModeUi()
                refreshSweepPlan()
            }
        }

        rgLineCutAxis.check(if (viewModel.lineCutHorizontal) R.id.rbAxisX else R.id.rbAxisY)
        rgLineCutAxis.onButtonChecked { checkedId ->
            viewModel.lineCutHorizontal = checkedId == R.id.rbAxisX
            refreshLineCutPreview()
        }

        btnPickSweepFrame.setOnClickListener { framePicker.show(resolvedSweepFrame()) }

        btnRunSweep.setOnClickListener {
            callbacks.commitParamFields()
            callbacks.startVsgSweep()
        }

        activity.findViewById<View>(R.id.btnLatticeSamples).setOnClickListener {
            val expanded = latticeSamplesBody.isVisible
            latticeSamplesBody.isVisible = !expanded
        }

        wireSweepInfoButtons()

        applyAnalysisModeUi()
        fields.showCountsAndStepDepth()
        seedSweepSuggestions()
    }

    /**
     * Single setting keeps Advanced + Compute on page 2. Parameter sweep shows
     * sweep settings on page 2 and routes through Next → page 3 (summary).
     */
    fun applyAnalysisModeUi() {
        val sweep = viewModel.sweepMode
        advancedParamsCard.isVisible = !sweep
        sweepSettingsCard.isVisible = sweep
        if (sweep) refreshSweepPlan()
        callbacks.updateWizardChrome()
        callbacks.checkReady()
    }

    /** Clears focus on sweep numeric fields so in-progress typing commits. */
    fun clearSweepFieldFocus() {
        rangeFields?.clearFocus()
    }

    /** Hands the sweep back to suggested inputs (e.g. Advanced Reset). */
    fun resetUserModified() {
        rangeFields?.userModified = false
    }

    fun onRecommendationChanged() = seedSweepSuggestions()

    /**
     * Seeds the sweep inputs with the app's suggestions — a subset window
     * centred on the SSSIG recommendation and a strain window of 3 to 11 points.
     * Runs until the user edits a sweep control; after that their values stand.
     */
    fun seedSweepSuggestions() {
        val fields = rangeFields ?: return
        fields.seed()
        refreshSweepPlan()
    }

    /**
     * The sweep grid the current inputs describe, capped to the subsets the ROI
     * can hold: x subset sizes × y strain windows, one step per subset.
     */
    fun currentPlan(): List<VsgStudy.Point> = sweepRanges().plan(callbacks.maxSubsetForRoi())

    /** The view model's seven sweep inputs as one value. */
    private fun sweepRanges() = SweepRanges(
        subsetMin = viewModel.subsetMin,
        subsetMax = viewModel.subsetMax,
        strainWinMin = viewModel.strainWinMin,
        strainWinMax = viewModel.strainWinMax,
        subsetSamples = viewModel.subsetSamples,
        strainWinSamples = viewModel.strainWinSamples,
        stepDenominator = viewModel.stepDenominator,
    )

    fun refreshSweepPlan() {
        if (!::tvSweepPlan.isInitialized) return
        rangeFields?.showCountsAndStepDepth()
        refreshSweepFrameUi()

        val plan = currentPlan()
        when {
            plan.isNotEmpty() -> {
                tvSweepPlan.isVisible = true
                sweepPlanWarn.hide()
                tvSweepPlan.text = planSummary(plan)
            }
            viewModel.subsetMin > callbacks.maxSubsetForRoi() -> {
                tvSweepPlan.isVisible = false
                sweepPlanWarn.show(
                    activity.getString(
                        R.string.sweep_plan_subset_too_big_fmt,
                        callbacks.maxSubsetForRoi(),
                    ),
                    activity.getString(R.string.url_faq_sweep_subset_range),
                )
            }
            else -> {
                tvSweepPlan.isVisible = false
                sweepPlanWarn.show(
                    activity.getString(R.string.sweep_plan_empty),
                    activity.getString(R.string.url_faq_sweep_empty_plan),
                )
            }
        }
        refreshLatticePreview(plan)
        refreshLineCutPreview()
        callbacks.checkReady()
    }

    /** Defaults to the middle of the sequence (1-based frame n/2+1). */
    fun resolvedSweepFrame(): Int {
        val n = viewModel.defCount
        if (n <= 0) return 0
        val last = n - 1
        val stored = viewModel.vsgFrameIndex
        return if (stored < 0 || stored > last) {
            (n / 2).coerceIn(0, last)
        } else {
            stored
        }
    }

    /** "N analyses · subset a–b px · window c–d points" for a plan. */
    fun planSummary(plan: List<VsgStudy.Point>): String = activity.resources.getQuantityString(
        R.plurals.sweep_plan_grid_fmt,
        plan.size,
        plan.size,
        plan.minOf { it.subset },
        plan.maxOf { it.subset },
        plan.minOf { it.window },
        plan.maxOf { it.window },
    )

    /** Short per-combination label; becomes the frame name in viewer and report. */
    fun combinationLabel(point: VsgStudy.Point): String = activity.getString(
        R.string.sweep_frame_label_fmt,
        point.subset,
        point.step,
        point.window,
    )

    /** Centre-line cut over the reference image and current ROI. */
    fun refreshLineCutPreview() {
        if (!::lineCutPreview.isInitialized) return
        val size = ImageSize(viewModel.realRefWidth, viewModel.realRefHeight)
        val drawn = Roi(viewModel.roiX, viewModel.roiY, viewModel.roiW, viewModel.roiH)
        val roi = drawn.orFullFrame(viewModel.hasCustomRoi, size)
        if (roi == null) {
            lineCutPreview.setPreview(
                bitmap = null,
                image = ImageSize(1, 1),
                roi = Roi(0, 0, 1, 1),
                horizontal = viewModel.lineCutHorizontal,
                maskBytes = null,
            )
            return
        }
        lineCutPreview.setPreview(
            bitmap = callbacks.refPreviewBitmap(),
            image = size,
            roi = roi,
            horizontal = viewModel.lineCutHorizontal,
            maskBytes = viewModel.roiMaskBytes,
        )
    }

    fun setRunSweepEnabled(enabled: Boolean) {
        if (!::btnRunSweep.isInitialized) return
        btnRunSweep.isEnabled = enabled
    }

    private fun wireSweepInfoButtons() {
        fun info(@IdRes button: Int, @StringRes title: Int, @StringRes body: Int) =
            activity.findViewById<View>(button).bindInfo(activity, title, body)
        info(R.id.btnSweepInfo, R.string.analysis_mode, R.string.info_analysis_mode)
        info(R.id.btnSubsetRangeInfo, R.string.subset_range, R.string.info_subset_range)
        info(R.id.btnVsgMaxInfo, R.string.strain_win_range, R.string.info_strain_win_range)
        info(R.id.btnSamplesInfo, R.string.subset_samples, R.string.info_subset_samples)
        info(R.id.btnStepDepthInfo, R.string.step_depth, R.string.info_step_depth)
        info(R.id.btnSweepOverlapInfo, R.string.subset_overlap, R.string.info_subset_overlap)
        info(R.id.btnLineCutInfo, R.string.line_cut_axis, R.string.info_line_cut_axis)
    }

    private fun refreshSweepFrameUi() {
        if (!::btnPickSweepFrame.isInitialized) return
        val count = viewModel.defCount
        if (count <= 1) {
            btnPickSweepFrame.isVisible = false
            return
        }
        val index = resolvedSweepFrame()
        btnPickSweepFrame.isVisible = true
        btnPickSweepFrame.text = activity.getString(
            R.string.sweep_frame_summary_fmt,
            framePicker.frameLabel(index),
            index + 1,
            count,
        )
    }

    private fun refreshLatticePreview(plan: List<VsgStudy.Point>) {
        if (!::sweepLatticePreview.isInitialized) return
        sweepLatticePreview.onNodeClick = null
        sweepLatticePreview.setNodes(
            plan.map { point ->
                VsgLatticeView.Node(
                    subset = point.subset,
                    step = point.step,
                    window = point.window,
                    vsg = point.vsg,
                    solved = true,
                )
            },
        )
    }
}

/**
 * The sweep's range inputs: the subset and strain-window range sliders with
 * their min/max fields, the step depth and its linked overlap, and the two
 * sample counts. Each commit clamps what was typed or slid, writes it to
 * [viewModel] and back to the controls, then runs [onChanged].
 *
 * The values follow the app's suggestions ([seed]) until the user edits one
 * ([userModified]); after that the user's values stand, only clamped.
 */
@Suppress("TooManyFunctions") // one commit and one write per control
internal class SweepRangeFields(
    activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val callbacks: SweepSetupHelper.Callbacks,
    private val onChanged: () -> Unit,
) {
    private val rangeSubset: RangeSlider = activity.findViewById(R.id.rangeSubset)
    private val etSubsetMinValue: EditText = activity.findViewById(R.id.etSubsetMinValue)
    private val etSubsetMaxValue: EditText = activity.findViewById(R.id.etSubsetMaxValue)
    private val rangeStrainWin: RangeSlider = activity.findViewById(R.id.rangeStrainWin)
    private val etStrainWinMinValue: EditText = activity.findViewById(R.id.etStrainWinMinValue)
    private val etStrainWinMaxValue: EditText = activity.findViewById(R.id.etStrainWinMaxValue)
    private val etStepDepthValue: EditText = activity.findViewById(R.id.etStepDepthValue)
    private val tvSweepOverlapValue: EditText = activity.findViewById(R.id.tvSweepOverlapValue)
    private val etSubsetSamplesValue: EditText = activity.findViewById(R.id.etSubsetSamplesValue)
    private val etStrainWinSamplesValue: EditText = activity.findViewById(R.id.etVsgSamplesValue)

    /** True while a suggestion/clamp is driving the sweep sliders, not the user. */
    private var bindingSweep = false

    /**
     * Set once the user edits any sweep control. Until then the three sweep
     * inputs — min subset, max subset, Max VSG — follow the app's suggestions,
     * which track the SSSIG recommendation. After it, the user is in charge and
     * the app only clamps their input to safe bounds.
     */
    var userModified = false

    init {
        rangeSubset.addOnChangeListener { slider, _, fromUser ->
            onSliderInput(fromUser) { commitSubsetRange(slider.values[0].toInt(), slider.values[1].toInt()) }
        }
        wireSweepField(etSubsetMinValue, { viewModel.subsetMin }) { commitSubsetMin(it) }
        wireSweepField(etSubsetMaxValue, { viewModel.subsetMax }) { commitSubsetMax(it) }
        rangeStrainWin.addOnChangeListener { slider, _, fromUser ->
            onSliderInput(fromUser) { commitStrainWinRange(slider.values[0].toInt(), slider.values[1].toInt()) }
        }
        wireSweepField(etStrainWinMinValue, { viewModel.strainWinMin }) { commitStrainWinMin(it) }
        wireSweepField(etStrainWinMaxValue, { viewModel.strainWinMax }) { commitStrainWinMax(it) }
        wireSweepField(etStepDepthValue, { viewModel.stepDenominator }) { commitStepDepth(it) }
        wireSweepOverlapField()
        wireSweepField(etSubsetSamplesValue, { viewModel.subsetSamples }) { commitSubsetSamples(it) }
        wireSweepField(etStrainWinSamplesValue, { viewModel.strainWinSamples }) { commitStrainWinSamples(it) }
    }

    /** Clears focus on the numeric fields so in-progress typing commits. */
    fun clearFocus() {
        etSubsetMinValue.clearFocus()
        etSubsetMaxValue.clearFocus()
        etStrainWinMinValue.clearFocus()
        etStrainWinMaxValue.clearFocus()
        etStepDepthValue.clearFocus()
        tvSweepOverlapValue.clearFocus()
    }

    /** Shows the two sample counts and the step depth / overlap pair as the view model holds them. */
    fun showCountsAndStepDepth() {
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        callbacks.renderParamField(etStrainWinSamplesValue, viewModel.strainWinSamples)
        writeStepDepth(viewModel.stepDenominator)
    }

    /**
     * Writes the app's suggestion — a subset window centred on the
     * recommendation and the default strain windows — unless the user has
     * taken over. Then their values stand, but an ROI edit can still shrink
     * what it's physically possible to solve, so the displayed subset range
     * keeps up: without this, the plan silently clamped subsetMax while the
     * slider kept showing the old value.
     */
    fun seed() {
        if (userModified) {
            reclampSubsetRangeToRoi()
            return
        }
        val ceiling = effectiveSubsetCeiling()
        val rec = callbacks.currentSubsetSize().coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val (lo, hi) = suggestedSubsetWindow(rec, ceiling)
        viewModel.subsetMin = lo
        viewModel.subsetMax = hi
        viewModel.strainWinMin = VsgStudy.DEFAULT_SWEEP_WINDOW_MIN
        viewModel.strainWinMax = VsgStudy.DEFAULT_SWEEP_WINDOW_MAX
        writeSubsetRange(lo, hi)
        writeStrainWinRange(viewModel.strainWinMin, viewModel.strainWinMax)
    }

    private inline fun onSliderInput(fromUser: Boolean, body: () -> Unit) {
        if (bindingSweep) return
        if (fromUser) userModified = true
        body()
    }

    /** Commits a typed value on focus loss (blank or unparseable puts [current] back); Done drops focus. */
    private fun wireSweepField(field: EditText, current: () -> Int, commit: (Int) -> Unit) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            if (typed == null) {
                callbacks.renderParamField(field, current())
            } else {
                userModified = true
                commit(typed)
            }
        }
        field.commitOnDone()
    }

    private fun effectiveSubsetCeiling(): Int =
        minOf(SubsetRecommender.MAX_SUBSET, callbacks.maxSubsetForRoi())

    /**
     * Keeps the user's own subset range inside what the current ROI can
     * support -- same ceiling, same clamp the plan already applies when
     * generating nodes, just also written back to [viewModel] and the slider
     * so what's displayed matches what will actually be planned.
     */
    private fun reclampSubsetRangeToRoi() {
        val ceiling = effectiveSubsetCeiling()
        val clampedMax = viewModel.subsetMax.coerceAtMost(ceiling)
        val clampedMin = viewModel.subsetMin.coerceAtMost(clampedMax)
        if (clampedMin != viewModel.subsetMin || clampedMax != viewModel.subsetMax) {
            viewModel.subsetMin = clampedMin
            viewModel.subsetMax = clampedMax
            writeSubsetRange(clampedMin, clampedMax)
        }
    }

    private fun oddSubset(raw: Int): Int =
        raw.coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET) or 1

    private fun commitSubsetRange(rawLo: Int, rawHi: Int) {
        val ceiling = effectiveSubsetCeiling()
        val lo = oddSubset(rawLo).coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val hi = oddSubset(rawHi).coerceIn(lo, ceiling)
        viewModel.subsetMin = lo
        viewModel.subsetMax = hi
        writeSubsetRange(lo, hi)
        onChanged()
    }

    private fun commitSubsetMin(raw: Int) {
        val currentMax = viewModel.subsetMax.takeIf { it > 0 } ?: effectiveSubsetCeiling()
        viewModel.subsetMin = oddSubset(raw).coerceAtMost(currentMax)
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        onChanged()
    }

    private fun commitSubsetMax(raw: Int) {
        val floor = viewModel.subsetMin.coerceAtLeast(SubsetRecommender.MIN_SUBSET)
        viewModel.subsetMax = oddSubset(raw).coerceIn(floor, effectiveSubsetCeiling())
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        onChanged()
    }

    private fun oddWindow(raw: Int): Int = VsgStudy.oddWindowPoints(raw)

    private fun commitStrainWinRange(rawLo: Int, rawHi: Int) {
        val lo = oddWindow(rawLo)
        val hi = oddWindow(rawHi).coerceAtLeast(lo)
        viewModel.strainWinMin = lo
        viewModel.strainWinMax = hi
        writeStrainWinRange(lo, hi)
        onChanged()
    }

    private fun commitStrainWinMin(raw: Int) {
        val currentMax = viewModel.strainWinMax.takeIf { it > 0 } ?: SweepSetupHelper.STRAIN_WIN_MAX_INPUT
        viewModel.strainWinMin = oddWindow(raw).coerceAtMost(currentMax)
        writeStrainWinRange(viewModel.strainWinMin, viewModel.strainWinMax)
        onChanged()
    }

    private fun commitStrainWinMax(raw: Int) {
        val floor = viewModel.strainWinMin.coerceAtLeast(SweepSetupHelper.STRAIN_WIN_MIN_INPUT)
        viewModel.strainWinMax = oddWindow(raw).coerceAtLeast(floor)
        writeStrainWinRange(viewModel.strainWinMin, viewModel.strainWinMax)
        onChanged()
    }

    private fun writeStrainWinRange(lo: Int, hi: Int) {
        writeRange(rangeStrainWin, lo, hi)
        callbacks.renderParamField(etStrainWinMinValue, lo)
        callbacks.renderParamField(etStrainWinMaxValue, hi)
    }

    private fun commitStepDepth(raw: Int) {
        viewModel.stepDenominator = raw.coerceIn(VsgStudy.STEP_DENOM_MIN, VsgStudy.STEP_DENOM_MAX)
        viewModel.subsetOverlap = VsgStudy.overlapForDenominator(viewModel.stepDenominator)
        writeStepDepth(viewModel.stepDenominator)
        onChanged()
    }

    private fun commitOverlap(raw: Double) {
        commitStepDepth(VsgStudy.denominatorForOverlap(raw))
    }

    private fun writeStepDepth(denominator: Int) {
        val n = denominator.coerceIn(VsgStudy.STEP_DENOM_MIN, VsgStudy.STEP_DENOM_MAX)
        viewModel.stepDenominator = n
        viewModel.subsetOverlap = VsgStudy.overlapForDenominator(n)
        if (!etStepDepthValue.hasFocus()) {
            callbacks.renderParamField(etStepDepthValue, n)
        }
        tvSweepOverlapValue.showUnlessEditing(String.format(Locale.US, "%.2f", viewModel.subsetOverlap))
    }

    /** The overlap field: a decimal (comma or point) that sets the step depth; unparseable puts it back. */
    private fun wireSweepOverlapField() {
        tvSweepOverlapValue.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = tvSweepOverlapValue.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (typed == null) {
                writeStepDepth(viewModel.stepDenominator)
            } else {
                userModified = true
                commitOverlap(typed)
            }
        }
        tvSweepOverlapValue.commitOnDone()
    }

    private fun commitSubsetSamples(raw: Int) {
        viewModel.subsetSamples = raw.coerceIn(VsgStudy.MIN_SAMPLES, VsgStudy.MAX_SAMPLES)
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        onChanged()
    }

    private fun commitStrainWinSamples(raw: Int) {
        viewModel.strainWinSamples = raw.coerceIn(VsgStudy.MIN_SAMPLES, VsgStudy.MAX_SAMPLES)
        callbacks.renderParamField(etStrainWinSamplesValue, viewModel.strainWinSamples)
        onChanged()
    }

    private fun writeSubsetRange(lo: Int, hi: Int) {
        writeRange(rangeSubset, lo, hi)
        callbacks.renderParamField(etSubsetMinValue, lo)
        callbacks.renderParamField(etSubsetMaxValue, hi)
    }

    /** Moves [slider]'s thumbs to [lo]..[hi], inside its own range, without it counting as the user's edit. */
    private fun writeRange(slider: RangeSlider, lo: Int, hi: Int) {
        bindingSweep = true
        slider.values = listOf(
            lo.toFloat().coerceIn(slider.valueFrom, slider.valueTo),
            hi.toFloat().coerceIn(slider.valueFrom, slider.valueTo),
        )
        bindingSweep = false
    }

    /**
     * A subset window of [SweepSetupHelper.SUGGESTED_SUBSET_SPAN] centred on
     * [rec], shifted whole to fit inside `[MIN_SUBSET, ceiling]` so it never
     * collapses to a single value unless the valid range itself is that narrow.
     */
    private fun suggestedSubsetWindow(rec: Int, ceiling: Int): Pair<Int, Int> {
        val half = SweepSetupHelper.SUGGESTED_SUBSET_SPAN / 2
        var lo = rec - half
        var hi = rec + half
        if (lo < SubsetRecommender.MIN_SUBSET) {
            hi += SubsetRecommender.MIN_SUBSET - lo
            lo = SubsetRecommender.MIN_SUBSET
        }
        if (hi > ceiling) {
            lo -= hi - ceiling
            hi = ceiling
        }
        return oddSubset(lo.coerceAtLeast(SubsetRecommender.MIN_SUBSET)) to oddSubset(hi)
    }
}

/**
 * The dialog that picks which deformed frame a sweep solves: a scrolling list
 * of the frames, a typed frame number, and a preview of the one picked.
 * OK hands the picked index to [onPicked].
 */
internal class SweepFramePicker(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val onPicked: (Int) -> Unit,
) {
    /** The frame-pick preview decode; a new pick or closing the dialog cancels it. */
    private val framePreview = SerialJob()

    /** A frame's file name, or "Frame n" when it has none. */
    fun frameLabel(index: Int): String =
        viewModel.defOriginalNames.getOrNull(index)?.substringAfterLast('/')
            ?: activity.getString(R.string.sweep_frame_btn_fmt, index + 1)

    /** Opens the dialog on frame [initial]; does nothing for a sequence of one frame. */
    fun show(initial: Int) {
        val count = viewModel.defCount
        if (count <= 1) return
        var selected = initial.coerceIn(0, count - 1)
        val builder = MaterialAlertDialogBuilder(activity)
        // Inflate against the builder's context so the rows pick up the dialog
        // theme overlay rather than the activity's.
        val content = DialogSweepFramePickBinding.inflate(LayoutInflater.from(builder.context))
        val numberField = content.etSweepFrameNumber
        content.tvSweepFrameTotal.text = activity.getString(R.string.sweep_frame_out_of_fmt, count)

        bindPreview(content, selected) { selected }
        val rows = fillFrameChoices(content, count, selected) { which ->
            selected = which
            numberField.setText(frameNumberText(which + 1))
            bindPreview(content, which) { selected }
        }
        numberField.setText(frameNumberText(selected + 1))
        wireFrameNumberField(numberField, current = { selected + 1 }) { typed ->
            val index = (typed - 1).coerceIn(0, count - 1)
            rows.getOrNull(index)?.let { row ->
                row.isChecked = true
                scrollFrameRowIntoView(content, row)
            }
            // Also normalises what was typed — "007" or an out-of-range number.
            numberField.setText(frameNumberText(index + 1))
        }

        builder
            .setTitle(R.string.sweep_frame)
            .setView(content.root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                framePreview.cancel()
                onPicked(selected)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> framePreview.cancel() }
            .setOnDismissListener { framePreview.cancel() }
            .show()
    }

    /**
     * Decodes frame [index] into the dialog's preview; a result that arrives
     * after [selected] has moved on is dropped.
     */
    private fun bindPreview(content: DialogSweepFramePickBinding, index: Int, selected: () -> Int) {
        val preview = content.ivSweepFrameDialogPreview
        val progress = content.progressSweepFramePreview
        val path = viewModel.defFilePaths.getOrNull(index)
        framePreview.cancel()
        if (path.isNullOrBlank()) {
            preview.setImageDrawable(null)
            progress.isVisible = false
            return
        }
        progress.isVisible = true
        framePreview.launch(activity.lifecycleScope) {
            val bmp = decodeFramePreview(path, viewModel.defFrameSizes[path])
            if (index != selected()) {
                bmp?.recycle()
                return@launch
            }
            progress.isVisible = false
            if (bmp != null) {
                preview.setImageBitmap(bmp)
            } else {
                preview.setImageDrawable(null)
            }
        }
    }

    /**
     * Fills the dialog's scrolling frame list and reports the picked index.
     * The row for [selected] is scrolled into view, so reopening the dialog on
     * frame 30 of 50 does not land the user at the top of the list.
     */
    private fun fillFrameChoices(
        content: DialogSweepFramePickBinding,
        count: Int,
        selected: Int,
        onPick: (Int) -> Unit,
    ): List<MaterialRadioButton> {
        val group = content.rgSweepFrames
        val rowPadding = group.dp(FRAME_ROW_PADDING_DP).toInt()
        val rows = List(count) { index ->
            MaterialRadioButton(group.context).apply {
                id = View.generateViewId()
                text = frameLabel(index)
                tag = index
                minimumHeight = group.dp(FRAME_ROW_MIN_HEIGHT_DP).toInt()
                setPadding(paddingLeft, rowPadding, paddingRight, rowPadding)
                group.addView(this)
                isChecked = index == selected
            }
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val index = group.findViewById<View>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            onPick(index)
        }
        rows.getOrNull(selected)?.let { scrollFrameRowIntoView(content, it) }
        return rows
    }

    /** ASCII digits, so the field round-trips through toIntOrNull() in any locale. */
    private fun frameNumberText(oneBased: Int): String = String.format(Locale.US, "%d", oneBased)

    private fun scrollFrameRowIntoView(content: DialogSweepFramePickBinding, row: View) {
        val scroll = content.scrollSweepFrames
        scroll.post { scroll.scrollTo(0, row.top) }
    }

    /**
     * Frame number entry: commits on focus loss (IME Done just drops focus, as
     * the sweep fields do). Anything unparseable reverts to [current].
     */
    private fun wireFrameNumberField(field: EditText, current: () -> Int, onPick: (Int) -> Unit) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            if (typed == null) field.setText(frameNumberText(current())) else onPick(typed)
        }
        field.commitOnDone()
    }

    /**
     * Decode a deformed-frame path for the pick dialog. Handles ordinary
     * containers, native-only formats (TIFF), and RAW RGBA blobs written at import
     * ([size] is the frame's measured size, for those).
     *
     * Each rung runs where it belongs: file reads on IO, the native decoder on
     * [SemperNativeLib.nativeDispatcher] (every JNI call is pinned there), the
     * JVM decodes on Default.
     */
    @Suppress("ReturnCount") // a ladder of decoders; each rung returns what it managed
    private suspend fun decodeFramePreview(path: String, size: Pair<Int, Int>?): Bitmap? {
        withContext(Dispatchers.IO) {
            BitmapDecode.decodeFileForView(path, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE, PREVIEW_MAX_EDGE)
        }?.let { return it }

        val bytes = withContext(Dispatchers.IO) {
            File(path).takeIf(File::exists)?.let { runCatching { it.readBytes() }.getOrNull() }
        } ?: return null

        withContext(SemperNativeLib.nativeDispatcher) {
            runCatching { SemperNativeLib.getPreviewFromBytes(bytes, PREVIEW_MAX_EDGE) }.getOrNull()
        }?.let { return it }

        return withContext(Dispatchers.Default) {
            decodeRawRgba(bytes, size)
                // Last resort: bounds-free BitmapFactory (may still fail for RAW).
                ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    /** A RAW RGBA blob written at import, sampled down to preview size. */
    private fun decodeRawRgba(bytes: ByteArray, size: Pair<Int, Int>?): Bitmap? {
        val (w, h) = size ?: return null
        return RawRgba.preview(bytes, w, h, PREVIEW_MAX_EDGE)
    }

    private companion object {
        /** Longest edge of a frame thumbnail in the pick dialog. */
        const val PREVIEW_MAX_EDGE = 480

        /** A frame row in the pick dialog: vertical padding and touch-target height. */
        const val FRAME_ROW_PADDING_DP = 8f
        const val FRAME_ROW_MIN_HEIGHT_DP = 48f
    }
}
