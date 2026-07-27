// Sweep setup wires many sliders/fields and seeds their suggested values; the
// literal UI/parameter constants and per-control methods read clearest inline.
@file:Suppress("LongMethod", "MagicNumber", "TooManyFunctions")

package com.rafad.indicvisiondic.ui.analysis

import android.graphics.Bitmap
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.rafad.indicvisiondic.R

/**
 * Parameter-sweep setup UI for the analysis wizard (§5.4.5 VSG study): mode
 * toggle, subset/VSG/sample fields, lattice + line-cut previews, and plan
 * summary. Orchestration ([startVsgSweep], progress, lifecycle) stays in the
 * Activity.
 */
class SweepSetupHelper(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun goToStep(step: Int, animate: Boolean)
        fun updateWizardChrome()
        fun checkReady()
        fun showInfo(titleRes: Int, bodyRes: Int)
        fun commitParamFields()
        fun startVsgSweep()
        fun currentSubsetSize(): Int
        fun maxSubsetForRoi(): Int
        fun refPreviewBitmap(): Bitmap?
        fun renderParamField(field: EditText, value: Int)
    }

    companion object {
        /** Width of the subset window a fresh sweep suggests, centred on the recommendation. */
        const val SUGGESTED_SUBSET_SPAN = 20

        /** Suggested Max VSG, as a multiple of the largest subset in the sweep. */
        const val VSG_SUGGESTION_FACTOR = 3

        /** Hard bounds on Max VSG — the guardrail against a mistyped huge number. */
        const val VSG_MIN_INPUT = 21
        const val VSG_MAX_INPUT = 501
    }

    private lateinit var rgAnalysisMode: MaterialButtonToggleGroup
    private lateinit var advancedParamsCard: View
    private lateinit var lineCutPreviewCard: View
    private lateinit var sweepBody: View
    private lateinit var rangeSubset: RangeSlider
    private lateinit var etSubsetMinValue: EditText
    private lateinit var etSubsetMaxValue: EditText
    private lateinit var etVsgMaxValue: EditText
    private lateinit var etStepDepthValue: EditText
    private lateinit var etSubsetSamplesValue: EditText
    private lateinit var etVsgSamplesValue: EditText
    private lateinit var rgLineCutAxis: MaterialButtonToggleGroup
    private lateinit var btnPickSweepFrame: Button
    private lateinit var tvSweepPlan: TextView
    private lateinit var lineCutPreview: LineCutPreviewView
    private lateinit var sweepLatticePreview: VsgLatticeView
    lateinit var btnRunSweep: Button
        private set
    private lateinit var latticeSamplesBody: View

    /** True while a suggestion/clamp is driving the sweep sliders, not the user. */
    private var bindingSweep = false

    /**
     * Set once the user edits any sweep control. Until then the three sweep
     * inputs — min subset, max subset, Max VSG — follow the app's suggestions,
     * which track the SSSIG recommendation. After it, the user is in charge and
     * the app only clamps their input to safe bounds.
     */
    private var sweepUserModified = false

    fun setup() {
        rgAnalysisMode = activity.findViewById(R.id.rgAnalysisMode)
        advancedParamsCard = activity.findViewById(R.id.advancedParamsCard)
        lineCutPreviewCard = activity.findViewById(R.id.lineCutPreviewCard)
        sweepBody = activity.findViewById(R.id.sweepBody)
        rangeSubset = activity.findViewById(R.id.rangeSubset)
        etSubsetMinValue = activity.findViewById(R.id.etSubsetMinValue)
        etSubsetMaxValue = activity.findViewById(R.id.etSubsetMaxValue)
        etVsgMaxValue = activity.findViewById(R.id.etVsgMaxValue)
        etStepDepthValue = activity.findViewById(R.id.etStepDepthValue)
        etSubsetSamplesValue = activity.findViewById(R.id.etSubsetSamplesValue)
        etVsgSamplesValue = activity.findViewById(R.id.etVsgSamplesValue)
        rgLineCutAxis = activity.findViewById(R.id.rgLineCutAxis)
        btnPickSweepFrame = activity.findViewById(R.id.btnPickSweepFrame)
        tvSweepPlan = activity.findViewById(R.id.tvSweepPlan)
        lineCutPreview = activity.findViewById(R.id.lineCutPreview)
        sweepLatticePreview = activity.findViewById(R.id.sweepLatticePreview)
        btnRunSweep = activity.findViewById(R.id.btnRunSweep)
        latticeSamplesBody = activity.findViewById(R.id.latticeSamplesBody)

        rgAnalysisMode.check(if (viewModel.sweepMode) R.id.rbModeSweep else R.id.rbModeSingle)
        rgAnalysisMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.sweepMode = checkedId == R.id.rbModeSweep
            // Leaving sweep mode while on the sweep page returns to settings.
            if (!viewModel.sweepMode && viewModel.wizardStep == 3) {
                callbacks.goToStep(2, animate = true)
            } else {
                applyAnalysisModeUi()
                refreshSweepPlan()
            }
        }

        rgLineCutAxis.check(if (viewModel.lineCutHorizontal) R.id.rbAxisX else R.id.rbAxisY)
        rgLineCutAxis.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.lineCutHorizontal = checkedId == R.id.rbAxisX
            refreshLineCutPreview()
        }

        btnPickSweepFrame.setOnClickListener { pickSweepFrameWithPreview() }

        btnRunSweep.setOnClickListener {
            callbacks.commitParamFields()
            callbacks.startVsgSweep()
        }

        val sweepSettingsBody = sweepBody
        val sweepChevron = activity.findViewById<ImageView>(R.id.ivSweepSettingsChevron)
        activity.findViewById<View>(R.id.sweepSettingsHeader).setOnClickListener {
            val expanded = sweepSettingsBody.visibility == View.VISIBLE
            sweepSettingsBody.visibility = if (expanded) View.GONE else View.VISIBLE
            sweepChevron.rotation = if (expanded) 0f else 180f
        }

        activity.findViewById<View>(R.id.btnLatticeSamples).setOnClickListener {
            val expanded = latticeSamplesBody.visibility == View.VISIBLE
            latticeSamplesBody.visibility = if (expanded) View.GONE else View.VISIBLE
        }

        wireControls()
        wireSweepInfoButtons()

        applyAnalysisModeUi()
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        callbacks.renderParamField(etVsgSamplesValue, viewModel.vsgSamples)
        callbacks.renderParamField(etStepDepthValue, viewModel.stepDenominator)
        seedSweepSuggestions()
    }

    /**
     * Single setting keeps Advanced + Compute on page 2. Parameter sweep hides
     * Advanced, shows the line-cut preview, and routes through Next → page 3.
     */
    fun applyAnalysisModeUi() {
        val sweep = viewModel.sweepMode
        advancedParamsCard.visibility = if (sweep) View.GONE else View.VISIBLE
        lineCutPreviewCard.visibility = if (sweep) View.VISIBLE else View.GONE
        if (sweep) refreshLineCutPreview()
        callbacks.updateWizardChrome()
        callbacks.checkReady()
    }

    /** Clears focus on sweep numeric fields so in-progress typing commits. */
    fun clearSweepFieldFocus() {
        if (!::etSubsetMinValue.isInitialized) return
        etSubsetMinValue.clearFocus()
        etSubsetMaxValue.clearFocus()
        etVsgMaxValue.clearFocus()
    }

    /** Hands the sweep back to suggested inputs (e.g. Advanced Reset). */
    fun resetUserModified() {
        sweepUserModified = false
    }

    fun onRecommendationChanged() = seedSweepSuggestions()

    /**
     * Seeds the sweep inputs with the app's suggestions — a subset window
     * centred on the SSSIG recommendation and a Max VSG a few times the subset.
     * Runs until the user edits a sweep control; after that their values stand.
     */
    fun seedSweepSuggestions() {
        if (!::rangeSubset.isInitialized) return
        if (sweepUserModified) {
            refreshSweepPlan()
            return
        }
        val ceiling = effectiveSubsetCeiling()
        val rec = callbacks.currentSubsetSize().coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val (lo, hi) = suggestedSubsetWindow(rec, ceiling)
        viewModel.subsetMin = lo
        viewModel.subsetMax = hi
        viewModel.vsgMax = (VSG_SUGGESTION_FACTOR * hi).coerceIn(VSG_MIN_INPUT, VSG_MAX_INPUT)
        writeSubsetRange(lo, hi)
        callbacks.renderParamField(etVsgMaxValue, viewModel.vsgMax)
        refreshSweepPlan()
    }

    /**
     * The sweep grid the current inputs describe, capped to the subsets the ROI
     * can hold: x subset sizes × y VSG sizes × z step sizes.
     */
    fun currentPlan(): List<VsgStudy.Point> {
        val ceiling = callbacks.maxSubsetForRoi()
        if (viewModel.subsetMin > ceiling) return emptyList()
        return VsgStudy.plan(
            subsetMin = viewModel.subsetMin,
            subsetMax = viewModel.subsetMax.coerceAtMost(ceiling),
            subsetSamples = viewModel.subsetSamples,
            vsgMax = viewModel.vsgMax,
            vsgSamples = viewModel.vsgSamples,
            stepDenominator = viewModel.stepDenominator,
        )
    }

    fun refreshSweepPlan() {
        if (!::tvSweepPlan.isInitialized) return
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        callbacks.renderParamField(etVsgSamplesValue, viewModel.vsgSamples)
        callbacks.renderParamField(etStepDepthValue, viewModel.stepDenominator)
        refreshSweepFrameUi()

        val plan = currentPlan()
        tvSweepPlan.text = when {
            plan.isNotEmpty() -> planSummary(plan)
            viewModel.subsetMin > callbacks.maxSubsetForRoi() ->
                activity.getString(R.string.sweep_plan_subset_too_big_fmt, callbacks.maxSubsetForRoi())
            else -> activity.getString(R.string.sweep_plan_empty)
        }
        refreshLatticePreview(plan)
        refreshLineCutPreview()
        callbacks.checkReady()
    }

    /** Defaults to the last frame — the most deformed one in a monotonic test. */
    fun resolvedSweepFrame(): Int {
        val last = maxOf(0, viewModel.defCount - 1)
        val stored = viewModel.vsgFrameIndex
        return if (stored < 0 || stored > last) last else stored
    }

    /** "N analyses · subset a–b px · VSG c–d px" for a plan. */
    fun planSummary(plan: List<VsgStudy.Point>): String = activity.getString(
        R.string.sweep_plan_grid_fmt,
        plan.size,
        plan.minOf { it.subset },
        plan.maxOf { it.subset },
        plan.minOf { it.vsg },
        plan.maxOf { it.vsg },
    )

    /** Short per-combination label; becomes the frame name in viewer and report. */
    fun combinationLabel(point: VsgStudy.Point): String = activity.getString(
        R.string.sweep_frame_label_fmt,
        point.subset,
        point.step,
        point.strainWindow,
        point.vsg,
    )

    /** Centre-line cut over the reference image and current ROI. */
    fun refreshLineCutPreview() {
        if (!::lineCutPreview.isInitialized) return
        val w = viewModel.realRefWidth
        val h = viewModel.realRefHeight
        if (w <= 0 || h <= 0) {
            lineCutPreview.setPreview(
                bitmap = null,
                imageW = 1,
                imageH = 1,
                roiX = 0,
                roiY = 0,
                roiW = 1,
                roiH = 1,
                horizontal = viewModel.lineCutHorizontal,
                maskBytes = null,
            )
            return
        }
        val roiX = if (viewModel.hasCustomRoi) viewModel.roiX else 0
        val roiY = if (viewModel.hasCustomRoi) viewModel.roiY else 0
        val roiW = if (viewModel.hasCustomRoi && viewModel.roiW > 0) viewModel.roiW else w
        val roiH = if (viewModel.hasCustomRoi && viewModel.roiH > 0) viewModel.roiH else h
        lineCutPreview.setPreview(
            bitmap = callbacks.refPreviewBitmap(),
            imageW = w,
            imageH = h,
            roiX = roiX,
            roiY = roiY,
            roiW = roiW,
            roiH = roiH,
            horizontal = viewModel.lineCutHorizontal,
            maskBytes = viewModel.roiMaskBytes,
        )
    }

    fun setRunSweepEnabled(enabled: Boolean) {
        if (!::btnRunSweep.isInitialized) return
        btnRunSweep.isEnabled = enabled
        btnRunSweep.alpha = if (enabled) 1.0f else 0.4f
    }

    // ------------------------------------------------------------------
    // Wiring + commits
    // ------------------------------------------------------------------

    private fun wireControls() {
        rangeSubset.addOnChangeListener { slider, _, fromUser ->
            onSliderInput(fromUser) { commitSubsetRange(slider.values[0].toInt(), slider.values[1].toInt()) }
        }

        wireSweepField(etSubsetMinValue, { viewModel.subsetMin }) { commitSubsetMin(it) }
        wireSweepField(etSubsetMaxValue, { viewModel.subsetMax }) { commitSubsetMax(it) }
        wireSweepField(etVsgMaxValue, { viewModel.vsgMax }) { commitVsgMax(it) }
        wireSweepField(etStepDepthValue, { viewModel.stepDenominator }) { commitStepDepth(it) }
        wireSweepField(etSubsetSamplesValue, { viewModel.subsetSamples }) { commitSubsetSamples(it) }
        wireSweepField(etVsgSamplesValue, { viewModel.vsgSamples }) { commitVsgSamples(it) }
    }

    private inline fun onSliderInput(fromUser: Boolean, body: () -> Unit) {
        if (bindingSweep) return
        if (fromUser) sweepUserModified = true
        body()
    }

    private fun wireSweepField(field: EditText, current: () -> Int, commit: (Int) -> Unit) {
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            if (typed == null) {
                callbacks.renderParamField(field, current())
            } else {
                sweepUserModified = true
                commit(typed)
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus()
                activity.getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    private fun wireSweepInfoButtons() {
        activity.findViewById<View>(R.id.btnSweepInfo)
            .setOnClickListener { callbacks.showInfo(R.string.analysis_mode, R.string.info_analysis_mode) }
        activity.findViewById<View>(R.id.btnSubsetRangeInfo)
            .setOnClickListener { callbacks.showInfo(R.string.subset_range, R.string.info_subset_range) }
        activity.findViewById<View>(R.id.btnVsgMaxInfo)
            .setOnClickListener { callbacks.showInfo(R.string.vsg_max, R.string.info_vsg_max) }
        activity.findViewById<View>(R.id.btnSamplesInfo)
            .setOnClickListener { callbacks.showInfo(R.string.subset_samples, R.string.info_subset_samples) }
        activity.findViewById<View>(R.id.btnStepDepthInfo)
            .setOnClickListener { callbacks.showInfo(R.string.step_depth, R.string.info_step_depth) }
        activity.findViewById<View>(R.id.btnLineCutInfo)
            .setOnClickListener { callbacks.showInfo(R.string.line_cut_axis, R.string.info_line_cut_axis) }
    }

    private fun effectiveSubsetCeiling(): Int =
        minOf(SubsetRecommender.MAX_SUBSET, callbacks.maxSubsetForRoi())

    private fun oddSubset(raw: Int): Int =
        raw.coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET) or 1

    private fun commitSubsetRange(rawLo: Int, rawHi: Int) {
        val ceiling = effectiveSubsetCeiling()
        val lo = oddSubset(rawLo).coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val hi = oddSubset(rawHi).coerceIn(lo, ceiling)
        viewModel.subsetMin = lo
        viewModel.subsetMax = hi
        writeSubsetRange(lo, hi)
        refreshSweepPlan()
    }

    private fun commitSubsetMin(raw: Int) {
        val currentMax = viewModel.subsetMax.takeIf { it > 0 } ?: effectiveSubsetCeiling()
        viewModel.subsetMin = oddSubset(raw).coerceAtMost(currentMax)
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        refreshSweepPlan()
    }

    private fun commitSubsetMax(raw: Int) {
        val floor = viewModel.subsetMin.coerceAtLeast(SubsetRecommender.MIN_SUBSET)
        viewModel.subsetMax = oddSubset(raw).coerceIn(floor, effectiveSubsetCeiling())
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        refreshSweepPlan()
    }

    private fun commitVsgMax(raw: Int) {
        viewModel.vsgMax = raw.coerceIn(VSG_MIN_INPUT, VSG_MAX_INPUT)
        callbacks.renderParamField(etVsgMaxValue, viewModel.vsgMax)
        refreshSweepPlan()
    }

    private fun commitStepDepth(raw: Int) {
        viewModel.stepDenominator = raw.coerceIn(2, 6)
        callbacks.renderParamField(etStepDepthValue, viewModel.stepDenominator)
        refreshSweepPlan()
    }

    private fun commitSubsetSamples(raw: Int) {
        viewModel.subsetSamples = raw.coerceIn(VsgStudy.MIN_SAMPLES, VsgStudy.MAX_SAMPLES)
        callbacks.renderParamField(etSubsetSamplesValue, viewModel.subsetSamples)
        refreshSweepPlan()
    }

    private fun commitVsgSamples(raw: Int) {
        viewModel.vsgSamples = raw.coerceIn(VsgStudy.MIN_SAMPLES, VsgStudy.MAX_SAMPLES)
        callbacks.renderParamField(etVsgSamplesValue, viewModel.vsgSamples)
        refreshSweepPlan()
    }

    private fun writeSubsetRange(lo: Int, hi: Int) {
        bindingSweep = true
        rangeSubset.values = listOf(
            lo.toFloat().coerceIn(rangeSubset.valueFrom, rangeSubset.valueTo),
            hi.toFloat().coerceIn(rangeSubset.valueFrom, rangeSubset.valueTo),
        )
        bindingSweep = false
        callbacks.renderParamField(etSubsetMinValue, lo)
        callbacks.renderParamField(etSubsetMaxValue, hi)
    }

    /**
     * A subset window of [SUGGESTED_SUBSET_SPAN] centred on [rec], shifted whole
     * to fit inside `[MIN_SUBSET, ceiling]` so it never collapses to a single
     * value unless the valid range itself is that narrow.
     */
    private fun suggestedSubsetWindow(rec: Int, ceiling: Int): Pair<Int, Int> {
        val half = SUGGESTED_SUBSET_SPAN / 2
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

    private fun frameLabel(index: Int): String =
        viewModel.defOriginalNames.getOrNull(index)?.substringAfterLast('/')
            ?: activity.getString(R.string.sweep_frame_btn_fmt, index + 1)

    private fun pickSweepFrameWithPreview() {
        val count = viewModel.defCount
        if (count <= 1) return
        var selected = resolvedSweepFrame().coerceIn(0, count - 1)
        val header = activity.layoutInflater.inflate(R.layout.dialog_sweep_frame_pick, null)
        val preview = header.findViewById<ImageView>(R.id.ivSweepFrameDialogPreview)
        val caption = header.findViewById<TextView>(R.id.tvSweepFrameDialogCaption)

        fun bindPreview(index: Int) {
            caption.text = activity.getString(R.string.sweep_frame_of_fmt, index + 1, count)
            val path = viewModel.defFilePaths.getOrNull(index)
            if (path.isNullOrBlank()) {
                preview.setImageDrawable(null)
                return
            }
            val bmp = android.graphics.BitmapFactory.decodeFile(path)
            if (bmp != null) {
                val maxEdge = 480
                val scale = minOf(1f, maxEdge.toFloat() / maxOf(bmp.width, bmp.height))
                val w = (bmp.width * scale).toInt().coerceAtLeast(1)
                val h = (bmp.height * scale).toInt().coerceAtLeast(1)
                preview.setImageBitmap(
                    if (scale < 1f) {
                        android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                    } else {
                        bmp
                    },
                )
            } else {
                preview.setImageDrawable(null)
            }
        }
        bindPreview(selected)

        val labels = Array(count) { frameLabel(it) }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sweep_frame)
            .setView(header)
            .setSingleChoiceItems(labels, selected) { _, which ->
                selected = which
                bindPreview(which)
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                viewModel.vsgFrameIndex = selected
                refreshSweepPlan()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshSweepFrameUi() {
        if (!::btnPickSweepFrame.isInitialized) return
        val count = viewModel.defCount
        if (count <= 1) {
            btnPickSweepFrame.visibility = View.GONE
            return
        }
        val index = resolvedSweepFrame()
        btnPickSweepFrame.visibility = View.VISIBLE
        btnPickSweepFrame.text = activity.getString(
            R.string.sweep_frame_summary_fmt,
            frameLabel(index),
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
                    window = point.strainWindow,
                    vsg = point.vsg,
                    solved = true,
                )
            },
        )
    }
}
