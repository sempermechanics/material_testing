package com.indicvision.semper.ui.analysis

import androidx.annotation.StringRes
import com.indicvision.semper.R

/** Maps engine error codes to the same user-facing strings on every screen. */
object EngineFailure {

    /** AKAZE could not match the pair. */
    const val ENGINE_ERROR_FEATURES = -1

    /** The ROI held no valid points. */
    const val ENGINE_ERROR_ROI = -2

    /** An image failed to decode, or the engine could not start. */
    const val ENGINE_ERROR_INIT = -3

    private enum class Cause {
        FEATURES,
        ROI,
        INIT,
        CONVERGENCE,
        VSG,
        UNKNOWN,
    }

    private fun cause(engineErrorCode: Int): Cause = when (engineErrorCode) {
        ENGINE_ERROR_FEATURES -> Cause.FEATURES
        ENGINE_ERROR_ROI -> Cause.ROI
        ENGINE_ERROR_INIT -> Cause.INIT
        AnalysisRunCodes.ERROR_LOW_CONVERGENCE -> Cause.CONVERGENCE
        0 -> Cause.VSG
        in 1..Int.MAX_VALUE -> Cause.UNKNOWN
        else -> Cause.VSG
    }

    /** Full explanation for dialogs. */
    @StringRes
    fun reasonRes(engineErrorCode: Int): Int = when (cause(engineErrorCode)) {
        Cause.FEATURES -> R.string.sweep_fail_features
        Cause.ROI -> R.string.sweep_fail_roi
        Cause.INIT -> R.string.sweep_fail_init
        Cause.CONVERGENCE -> R.string.error_low_convergence
        Cause.UNKNOWN -> R.string.sweep_fail_unknown
        Cause.VSG -> R.string.sweep_reason_vsg
    }

    /** One-line label for lattice nodes. Code 0 → strain window; unknown positive → [Cause.UNKNOWN]. */
    @StringRes
    fun shortReasonRes(engineErrorCode: Int): Int = when (cause(engineErrorCode)) {
        Cause.FEATURES -> R.string.sweep_reason_decorrelated
        Cause.ROI -> R.string.sweep_reason_subset_too_big
        Cause.INIT -> R.string.sweep_reason_decode
        Cause.CONVERGENCE -> R.string.sweep_reason_low_convergence
        Cause.UNKNOWN -> R.string.sweep_reason_unknown
        Cause.VSG -> R.string.sweep_reason_vsg
    }

    @StringRes
    fun faqUrlRes(engineErrorCode: Int): Int = when (cause(engineErrorCode)) {
        Cause.FEATURES -> R.string.url_faq_engine_features
        Cause.ROI -> R.string.url_faq_engine_roi
        Cause.INIT -> R.string.url_faq_engine_init
        Cause.CONVERGENCE -> R.string.url_faq_engine_convergence
        Cause.UNKNOWN -> R.string.url_faq_engine_vsg
        Cause.VSG -> R.string.url_faq_engine_vsg
    }
}
