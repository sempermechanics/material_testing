package com.sempermechanics.semper.ui.analysis.sweep

import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.RangeSlider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.common.commitOnDone
import com.sempermechanics.semper.ui.common.showUnlessEditing
import java.util.Locale

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
    private val callbacks: SweepSetupController.Callbacks,
    private val onChanged: () -> Unit,
) {
    private val rangeSubset: RangeSlider = activity.findViewById(R.id.rangeSubset)
    private val etSubsetMinValue: EditText = activity.findViewById(R.id.etSubsetMinValue)
    private val etSubsetMaxValue: EditText = activity.findViewById(R.id.etSubsetMaxValue)
    private val rangeStrainWin: RangeSlider = activity.findViewById(R.id.rangeStrainWin)
    private val etStrainWinMinValue: EditText = activity.findViewById(R.id.etStrainWinMinValue)
    private val etStrainWinMaxValue: EditText = activity.findViewById(R.id.etStrainWinMaxValue)
    private val etStepDepthValue: EditText = activity.findViewById(R.id.etStepDepthValue)
    private val etSweepOverlapValue: EditText = activity.findViewById(R.id.etSweepOverlapValue)
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

    /** The sweep's axes as the view model holds them now. */
    private val ranges: SweepRanges get() = viewModel.inputs.value.sweepRanges

    /** Writes [change] of the sweep's axes to the view model, in one edit. */
    private fun editRanges(change: (SweepRanges) -> SweepRanges) =
        viewModel.edit { it.copy(sweepRanges = change(it.sweepRanges)) }

    init {
        rangeSubset.addOnChangeListener { slider, _, fromUser ->
            onSliderInput(fromUser) { commitSubsetRange(slider.values[0].toInt(), slider.values[1].toInt()) }
        }
        wireSweepField(etSubsetMinValue, { ranges.subsetMin }) { commitSubsetMin(it) }
        wireSweepField(etSubsetMaxValue, { ranges.subsetMax }) { commitSubsetMax(it) }
        rangeStrainWin.addOnChangeListener { slider, _, fromUser ->
            onSliderInput(fromUser) { commitStrainWinRange(slider.values[0].toInt(), slider.values[1].toInt()) }
        }
        wireSweepField(etStrainWinMinValue, { ranges.strainWinMin }) { commitStrainWinMin(it) }
        wireSweepField(etStrainWinMaxValue, { ranges.strainWinMax }) { commitStrainWinMax(it) }
        wireSweepField(etStepDepthValue, { ranges.stepDenominator }) { commitStepDepth(it) }
        wireSweepOverlapField()
        wireSweepField(etSubsetSamplesValue, { ranges.subsetSamples }) { commitSubsetSamples(it) }
        wireSweepField(etStrainWinSamplesValue, { ranges.strainWinSamples }) { commitStrainWinSamples(it) }
    }

    /** Clears focus on the numeric fields so in-progress typing commits. */
    fun clearFocus() {
        etSubsetMinValue.clearFocus()
        etSubsetMaxValue.clearFocus()
        etStrainWinMinValue.clearFocus()
        etStrainWinMaxValue.clearFocus()
        etStepDepthValue.clearFocus()
        etSweepOverlapValue.clearFocus()
    }

    /** Shows the two sample counts and the step depth / overlap pair as the view model holds them. */
    fun showCountsAndStepDepth() {
        val now = ranges
        callbacks.renderParamField(etSubsetSamplesValue, now.subsetSamples)
        callbacks.renderParamField(etStrainWinSamplesValue, now.strainWinSamples)
        writeStepDepth(now.stepDenominator)
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
        editRanges {
            it.copy(
                subsetMin = lo,
                subsetMax = hi,
                strainWinMin = SweepStudy.DEFAULT_SWEEP_WINDOW_MIN,
                strainWinMax = SweepStudy.DEFAULT_SWEEP_WINDOW_MAX,
            )
        }
        writeSubsetRange(lo, hi)
        writeStrainWinRange(SweepStudy.DEFAULT_SWEEP_WINDOW_MIN, SweepStudy.DEFAULT_SWEEP_WINDOW_MAX)
    }

    /**
     * Takes the view model's ranges as the user's own, as a Re-run brings
     * them, and shows them: suggestions leave them alone from here ([seed]),
     * and only the ROI's ceiling still clamps them.
     */
    fun adopt() {
        userModified = true
        val now = ranges
        writeSubsetRange(now.subsetMin, now.subsetMax)
        writeStrainWinRange(now.strainWinMin, now.strainWinMax)
        showCountsAndStepDepth()
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
        val now = ranges
        val clampedMax = now.subsetMax.coerceAtMost(ceiling)
        val clampedMin = now.subsetMin.coerceAtMost(clampedMax)
        if (clampedMin != now.subsetMin || clampedMax != now.subsetMax) {
            editRanges { it.copy(subsetMin = clampedMin, subsetMax = clampedMax) }
            writeSubsetRange(clampedMin, clampedMax)
        }
    }

    private fun oddSubset(raw: Int): Int =
        raw.coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET) or 1

    private fun commitSubsetRange(rawLo: Int, rawHi: Int) {
        val ceiling = effectiveSubsetCeiling()
        val lo = oddSubset(rawLo).coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val hi = oddSubset(rawHi).coerceIn(lo, ceiling)
        editRanges { it.copy(subsetMin = lo, subsetMax = hi) }
        writeSubsetRange(lo, hi)
        onChanged()
    }

    private fun commitSubsetMin(raw: Int) {
        val currentMax = ranges.subsetMax.takeIf { it > 0 } ?: effectiveSubsetCeiling()
        editRanges { it.copy(subsetMin = oddSubset(raw).coerceAtMost(currentMax)) }
        writeSubsetRange(ranges.subsetMin, ranges.subsetMax)
        onChanged()
    }

    private fun commitSubsetMax(raw: Int) {
        val floor = ranges.subsetMin.coerceAtLeast(SubsetRecommender.MIN_SUBSET)
        val ceiling = effectiveSubsetCeiling()
        editRanges { it.copy(subsetMax = oddSubset(raw).coerceIn(floor, ceiling)) }
        writeSubsetRange(ranges.subsetMin, ranges.subsetMax)
        onChanged()
    }

    private fun oddWindow(raw: Int): Int = SweepStudy.oddWindowPoints(raw)

    private fun commitStrainWinRange(rawLo: Int, rawHi: Int) {
        val lo = oddWindow(rawLo)
        val hi = oddWindow(rawHi).coerceAtLeast(lo)
        editRanges { it.copy(strainWinMin = lo, strainWinMax = hi) }
        writeStrainWinRange(lo, hi)
        onChanged()
    }

    private fun commitStrainWinMin(raw: Int) {
        val currentMax = ranges.strainWinMax.takeIf { it > 0 } ?: SweepSetupController.STRAIN_WIN_MAX_INPUT
        editRanges { it.copy(strainWinMin = oddWindow(raw).coerceAtMost(currentMax)) }
        writeStrainWinRange(ranges.strainWinMin, ranges.strainWinMax)
        onChanged()
    }

    private fun commitStrainWinMax(raw: Int) {
        val floor = ranges.strainWinMin.coerceAtLeast(SweepSetupController.STRAIN_WIN_MIN_INPUT)
        editRanges { it.copy(strainWinMax = oddWindow(raw).coerceAtLeast(floor)) }
        writeStrainWinRange(ranges.strainWinMin, ranges.strainWinMax)
        onChanged()
    }

    private fun writeStrainWinRange(lo: Int, hi: Int) {
        writeRange(rangeStrainWin, lo, hi)
        callbacks.renderParamField(etStrainWinMinValue, lo)
        callbacks.renderParamField(etStrainWinMaxValue, hi)
    }

    private fun commitStepDepth(raw: Int) {
        // writeStepDepth clamps and stores it, with the overlap it implies.
        writeStepDepth(raw)
        onChanged()
    }

    private fun commitOverlap(raw: Double) {
        commitStepDepth(SweepStudy.denominatorForOverlap(raw))
    }

    private fun writeStepDepth(denominator: Int) {
        val n = denominator.coerceIn(SweepStudy.STEP_DENOM_MIN, SweepStudy.STEP_DENOM_MAX)
        val overlap = SweepStudy.overlapForDenominator(n)
        viewModel.edit { it.copy(sweepRanges = it.sweepRanges.copy(stepDenominator = n), subsetOverlap = overlap) }
        if (!etStepDepthValue.hasFocus()) {
            callbacks.renderParamField(etStepDepthValue, n)
        }
        etSweepOverlapValue.showUnlessEditing(String.format(Locale.US, "%.2f", overlap))
    }

    /** The overlap field: a decimal (comma or point) that sets the step depth; unparseable puts it back. */
    private fun wireSweepOverlapField() {
        etSweepOverlapValue.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = etSweepOverlapValue.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (typed == null) {
                writeStepDepth(ranges.stepDenominator)
            } else {
                userModified = true
                commitOverlap(typed)
            }
        }
        etSweepOverlapValue.commitOnDone()
    }

    private fun commitSubsetSamples(raw: Int) {
        val samples = raw.coerceIn(SweepStudy.MIN_SAMPLES, SweepStudy.MAX_SAMPLES)
        editRanges { it.copy(subsetSamples = samples) }
        callbacks.renderParamField(etSubsetSamplesValue, samples)
        onChanged()
    }

    private fun commitStrainWinSamples(raw: Int) {
        val samples = raw.coerceIn(SweepStudy.MIN_SAMPLES, SweepStudy.MAX_SAMPLES)
        editRanges { it.copy(strainWinSamples = samples) }
        callbacks.renderParamField(etStrainWinSamplesValue, samples)
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
     * A subset window of [SweepSetupController.SUGGESTED_SUBSET_SPAN] centred on
     * [rec], shifted whole to fit inside `[MIN_SUBSET, ceiling]` so it never
     * collapses to a single value unless the valid range itself is that narrow.
     */
    private fun suggestedSubsetWindow(rec: Int, ceiling: Int): Pair<Int, Int> {
        val half = SweepSetupController.SUGGESTED_SUBSET_SPAN / 2
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
