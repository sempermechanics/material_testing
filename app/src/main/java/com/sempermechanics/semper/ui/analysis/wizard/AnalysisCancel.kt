package com.sempermechanics.semper.ui.analysis.wizard

import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner

/**
 * Single cancel channel for batch analysis and parameter sweeps.
 * [AnalysisViewModel] owns the public API; [SweepStudyRunner] observes the same flag.
 */
object AnalysisCancel {
    /**
     * Hands the flag to the engine. A lambda, not a reference to
     * [SemperNativeLib.setCancelRequested], so the library is loaded on the
     * first cancel and not when this object is.
     *
     * A JVM test has no `semper_core`; it swaps in a no-op here so it can
     * destroy the wizard, whose `onDestroy` cancels, and restores this after
     * (TD-200).
     */
    @VisibleForTesting
    internal var toEngine: (Boolean) -> Unit = { SemperNativeLib.setCancelRequested(it) }

    @Volatile
    var requested: Boolean = false
        set(value) {
            field = value
            toEngine(value)
        }

    fun clear() {
        requested = false
    }
}
