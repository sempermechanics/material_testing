package com.sempermechanics.semper.ui.analysis.wizard

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.TestType
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.load.loadPointMissing
import com.sempermechanics.semper.ui.analysis.load.mechanicalInputsReady
import com.sempermechanics.semper.ui.analysis.load.stressModel
import com.sempermechanics.semper.ui.analysis.load.typedLoadsMissing
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupController
import com.sempermechanics.semper.ui.common.dialog.WarnChip

/**
 * Wizard readiness / Compute / Sweep enablement extracted from
 * [StaticAnalysisActivity.checkReady].
 */
class AnalysisReadyGate(
    private val viewModel: AnalysisViewModel,
    private val binding: ActivityStaticAnalysisBinding,
    private val settings: WizardStepSettingsContentBinding,
    private val frameSizeChip: WarnChip,
) {

    fun apply(isProcessing: Boolean, sweepController: SweepSetupController?) {
        val ready = viewModel.isReadyToCompute() && viewModel.mechanicalInputsReady()

        binding.btnNext.isEnabled = ready && !isProcessing
        val context = binding.root.context
        binding.tvNextReason.text = when {
            viewModel.refBytes == null -> context.getString(R.string.next_reason_ref)
            viewModel.defFilePaths.isEmpty() -> context.getString(R.string.next_reason_def)
            else -> labReason(context)
        }

        val sizeError = viewModel.frameSizeError
        frameSizeChip.showOrHide(sizeError)

        // Compute and Run sweep share every condition but the mode.
        val canRun = ready &&
            viewModel.settingsReviewed &&
            !isProcessing &&
            sizeError == null
        binding.btnCalculateFullField.isEnabled = canRun && !viewModel.sweepMode
        sweepController?.setRunSweepEnabled(canRun && viewModel.sweepMode && sweepController.currentPlan().isNotEmpty())

        settings.btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        binding.btnBack.isEnabled = !isProcessing
    }

    /** What the lab test still needs before Next; blank when nothing (or a plain DIC run). */
    private fun labReason(context: Context): String {
        val test = viewModel.testType
        val missingTyped = viewModel.typedLoadsMissing()
        return when {
            !test.hasMachineLoad -> ""
            viewModel.machineLoads == null -> context.getString(
                if (test == TestType.BENDING) R.string.next_reason_load_typed else R.string.next_reason_load,
            )
            missingTyped > 0 -> context.resources.getQuantityString(
                R.plurals.next_reason_load_typed_missing_fmt,
                missingTyped,
                missingTyped,
            )
            viewModel.machineLoads?.matchedFrames == 0 -> context.getString(R.string.next_reason_load_unmatched)
            !viewModel.stressModel().isComplete -> context.getString(
                if (viewModel.stressModel() is StressStrain.Model.Axial) {
                    R.string.next_reason_cross_section
                } else {
                    R.string.next_reason_dimensions
                },
            )
            viewModel.loadPointMissing() -> context.getString(R.string.next_reason_load_point)
            else -> ""
        }
    }
}
