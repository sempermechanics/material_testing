package com.indicvision.semper.ui.analysis

/**
 * Decides when a run has lost the speckle and should stop.
 *
 * Convergence below [AnalysisViewModel.MIN_CONVERGENCE_PERCENT] on
 * [AnalysisViewModel.LOW_CONVERGENCE_STRIKES] consecutive solves means the image
 * pair has decorrelated, not that one frame was unlucky — the frames after it
 * will be no better, and finishing the run only spends minutes producing fields
 * nobody should trust.
 *
 * Shared by the batch path and the sweep so the two cannot drift apart: the same
 * rule, counted the same way, whether the consecutive solves are successive
 * frames or successive parameter combinations.
 */
class ConvergenceGate(
    private val minPercent: Float = AnalysisViewModel.MIN_CONVERGENCE_PERCENT,
    private val strikes: Int = AnalysisViewModel.LOW_CONVERGENCE_STRIKES,
) {
    private var consecutiveLow = 0

    /** True once the run should stop. Stays true afterwards. */
    var shouldStop: Boolean = false
        private set

    /**
     * Records one solve's convergence and reports whether to stop.
     *
     * A negative value means the engine reported nothing for this solve; it
     * neither counts as a strike nor clears the streak, since absence of a
     * reading says nothing about correlation either way.
     */
    fun record(convergencePercent: Float): Boolean {
        if (convergencePercent < 0f) return shouldStop
        if (convergencePercent < minPercent) {
            consecutiveLow++
            if (consecutiveLow >= strikes) shouldStop = true
        } else {
            consecutiveLow = 0
        }
        return shouldStop
    }
}
