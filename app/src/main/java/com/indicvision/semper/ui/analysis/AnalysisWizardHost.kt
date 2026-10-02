package com.indicvision.semper.ui.analysis

import com.indicvision.semper.ui.analysis.run.BatchRunController
import com.indicvision.semper.ui.analysis.sweep.SweepSetupHelper

/**
 * What the wizard's parts call back on the screen that hosts them: the sweep
 * setup's callbacks, what each way a run ends does, and the refreshes every
 * changed input asks for.
 */
interface AnalysisWizardHost :
    SweepSetupHelper.Callbacks,
    BatchRunController.Host {

    /** Drops a previous run's status line when an input changes; not while busy. */
    fun clearRunStatus()
}
