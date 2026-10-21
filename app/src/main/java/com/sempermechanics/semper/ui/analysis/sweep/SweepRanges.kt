package com.sempermechanics.semper.ui.analysis.sweep

/**
 * The axes of a parameter sweep as the wizard edits them: the subset range
 * (px) and how many sizes to sample across it, the strain-window range (in
 * data points, not px) and how many windows to sample, and the step-depth
 * denominator (`step = round(subset / N)`).
 *
 * Today these are seven `var`s on `AnalysisViewModel` (`subsetMin` … `stepDenominator`),
 * the seven-int `"sweepRanges"` array of the wizard's saved state, and the
 * seven parameters of [SweepStudy.plan]. The ranges are 0 until a recommendation
 * or default seeds them ([UNSEEDED]).
 */
data class SweepRanges(
    val subsetMin: Int,
    val subsetMax: Int,
    val strainWinMin: Int,
    val strainWinMax: Int,
    val subsetSamples: Int = SweepStudy.DEFAULT_SUBSET_SAMPLES,
    val strainWinSamples: Int = SweepStudy.DEFAULT_VSG_SAMPLES,
    val stepDenominator: Int = SweepStudy.DEFAULT_STEP_DENOM,
) {

    /**
     * The combinations this sweep solves when the ROI holds subsets up to
     * [subsetCeiling] (`RoiResolveHelper.maxSubsetForRoi`): none when even
     * [subsetMin] is over it, else [SweepStudy.plan] with [subsetMax] capped at
     * it. The rule `SweepSetupController.currentPlan` applies.
     */
    fun plan(subsetCeiling: Int): List<SweepStudy.Point> = if (subsetMin > subsetCeiling) {
        emptyList()
    } else {
        SweepStudy.plan(
            subsetMin = subsetMin,
            subsetMax = subsetMax.coerceAtMost(subsetCeiling),
            subsetSamples = subsetSamples,
            strainWinMin = strainWinMin,
            strainWinMax = strainWinMax,
            strainWinSamples = strainWinSamples,
            stepDenominator = stepDenominator,
        )
    }

    /**
     * The saved-state array, in `WizardState.save`'s order: subset min, max,
     * window min, max, subset samples, window samples, step denominator.
     */
    fun toIntArray(): IntArray = intArrayOf(
        subsetMin,
        subsetMax,
        strainWinMin,
        strainWinMax,
        subsetSamples,
        strainWinSamples,
        stepDenominator,
    )

    companion object {
        private const val PACKED_SIZE = 7

        /** The view model's state before anything seeds the ranges. */
        val UNSEEDED = SweepRanges(subsetMin = 0, subsetMax = 0, strainWinMin = 0, strainWinMax = 0)

        /**
         * From [toIntArray]'s layout, or null unless it holds exactly seven ints:
         * `WizardState.restoreScalars` ignores any other array the same way.
         */
        fun fromIntArray(packed: IntArray?): SweepRanges? = packed?.takeIf { it.size == PACKED_SIZE }?.let {
            val next = it.iterator()
            SweepRanges(
                subsetMin = next.nextInt(),
                subsetMax = next.nextInt(),
                strainWinMin = next.nextInt(),
                strainWinMax = next.nextInt(),
                subsetSamples = next.nextInt(),
                strainWinSamples = next.nextInt(),
                stepDenominator = next.nextInt(),
            )
        }
    }
}

/**
 * The deformed frame a sweep is solved against, as the wizard edits it: the
 * middle of the sequence until one is picked ([Middle]), or a picked one
 * ([At]). It used to be an `Int` with -1 for the middle, which is still how
 * the saved state holds it ([savedIndex], [fromSaved]).
 */
sealed interface SweepFrame {

    /** The middle of the sequence: 0-based frame n/2, 1-based n/2+1. */
    data object Middle : SweepFrame

    /** The 0-based frame [index], as picked. */
    data class At(val index: Int) : SweepFrame

    /** The saved state's `Int`: -1 for [Middle]. */
    val savedIndex: Int get() = (this as? At)?.index ?: -1

    /**
     * The 0-based frame of a sequence of [count] this is: the picked one while
     * it exists, else the middle; 0 with no frames.
     */
    fun resolve(count: Int): Int {
        if (count <= 0) return 0
        val last = count - 1
        val picked = (this as? At)?.index?.takeIf { it in 0..last }
        return picked ?: (count / 2).coerceIn(0, last)
    }

    companion object {
        /** From the saved state's `Int` ([savedIndex]): any negative is the middle. */
        fun fromSaved(index: Int): SweepFrame = if (index < 0) Middle else At(index)
    }
}
