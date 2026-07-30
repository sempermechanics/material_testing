package com.indicvision.semper.ui.analysis

import androidx.annotation.StringRes
import com.indicvision.semper.R

/**
 * Why the engine produced nothing, as a string resource.
 *
 * The codes are the same for a single analysis, a batch and a sweep, and they
 * surface in three places — the failure dialog, the sweep summary and the
 * lattice's per-node reason. One mapping, resolved wherever there is a Context
 * to translate with, so a reason can never read differently depending on which
 * screen showed it.
 */
object EngineFailure {

    /** AKAZE could not match the pair. */
    const val ENGINE_ERROR_FEATURES = -1

    /** The ROI held no valid points. */
    const val ENGINE_ERROR_ROI = -2

    /** An image failed to decode, or the engine could not start. */
    const val ENGINE_ERROR_INIT = -3

    /**
     * The full explanation for [engineErrorCode] — what to change and why. Every
     * string here takes the raw code as `%1$d` so the unknown case can name it;
     * the known ones simply ignore it.
     */
    @StringRes
    fun reasonRes(engineErrorCode: Int): Int = when (engineErrorCode) {
        ENGINE_ERROR_FEATURES -> R.string.sweep_fail_features
        ENGINE_ERROR_ROI -> R.string.sweep_fail_roi
        ENGINE_ERROR_INIT -> R.string.sweep_fail_init
        AnalysisRunCodes.ERROR_LOW_CONVERGENCE -> R.string.error_low_convergence
        else -> R.string.sweep_fail_unknown
    }

    /**
     * A one-line label for the same failure, in the terms the measurement is
     * discussed in rather than the engine's — decorrelation, a subset that will
     * not fit, a strain window nothing survived.
     *
     * This is what a lattice node shows: at a glance across a grid of them, the
     * pattern of *which* combinations failed is the information, and a paragraph
     * per node buries it. The full text stays for the dialog.
     *
     * Zero and unrecognised codes land on the strain-window case deliberately: a
     * combination that returns no points has almost always asked for a window the
     * ROI cannot support at that step.
     */
    @StringRes
    fun shortReasonRes(engineErrorCode: Int): Int = when (engineErrorCode) {
        ENGINE_ERROR_FEATURES -> R.string.sweep_reason_decorrelated
        ENGINE_ERROR_ROI -> R.string.sweep_reason_subset_too_big
        ENGINE_ERROR_INIT -> R.string.sweep_reason_decode
        AnalysisRunCodes.ERROR_LOW_CONVERGENCE -> R.string.sweep_reason_low_convergence
        else -> R.string.sweep_reason_vsg
    }
}
