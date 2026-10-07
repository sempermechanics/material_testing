package com.sempermechanics.semper.ui.analysis.wizard

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
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
        binding.tvNextReason.text = nextReason()

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

    /** Why Next is off: the first input still missing, the lab test's included; blank when none is. */
    private fun nextReason(): String {
        val context = binding.root.context
        val testType = viewModel.testType
        return when {
            viewModel.refBytes == null -> context.getString(R.string.next_reason_ref)
            viewModel.defFilePaths.isEmpty() -> context.getString(R.string.next_reason_def)
            testType.hasMachineLoad && viewModel.machineLoads == null ->
                context.getString(
                    if (testType == TestType.BENDING) R.string.next_reason_load_typed else R.string.next_reason_load,
                )
            viewModel.typedLoadsMissing() > 0 -> context.resources.getQuantityString(
                R.plurals.next_reason_load_typed_missing_fmt,
                viewModel.typedLoadsMissing(),
                viewModel.typedLoadsMissing(),
            )
            testType.hasMachineLoad -> loadReason(context)
            else -> ""
        }
    }

    /**
     * Why Next is off for a test with a load log that has been read: no frame
     * matched a load, the stress model is missing a dimension, or bending's
     * load point is untapped. Blank when none of these holds.
     */
    private fun loadReason(context: Context): String = when {
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
