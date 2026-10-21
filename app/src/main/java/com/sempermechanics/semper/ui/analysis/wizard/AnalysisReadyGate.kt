package com.sempermechanics.semper.ui.analysis.wizard

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.databinding.WizardStepSettingsContentBinding
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.recommend.RunEstimate
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

    /** The wizard's inputs as the view model holds them now. */
    private val inputs: WizardInputs get() = viewModel.inputs.value

    /**
     * Enables Next, Compute and Run sweep for what the wizard holds, unless
     * [isProcessing]. [sweepController] is the sweep page's; before its
     * views are found its Run button is not there to enable.
     */
    fun apply(isProcessing: Boolean, sweepController: SweepSetupController) {
        val ready = viewModel.isReadyToCompute() && inputs.mechanicalInputsReady()

        binding.btnNext.isEnabled = ready && !isProcessing
        binding.tvNextReason.text = nextReason()

        val sizeError = inputs.frameSizeError
        frameSizeChip.showOrHide(sizeError)

        // Compute and Run sweep share every condition but the mode.
        val canRun = ready &&
            inputs.settingsReviewed &&
            !isProcessing &&
            sizeError == null
        binding.btnCalculateFullField.isEnabled = canRun && !inputs.sweepMode
        sweepController.setRunSweepEnabled(canRun && inputs.sweepMode && sweepController.currentPlan().isNotEmpty())

        settings.btnDefineRoi.isEnabled = (inputs.refBytes != null) && !isProcessing
        binding.btnBack.isEnabled = !isProcessing
        showEstimate()
    }

    /**
     * "1100 × 800 px · 8,800 points" on the ROI row, and "Compute · about
     * 1 min" on the button. Runs on every step or region change, so the count
     * follows both. The time comes from this phone's past runs; without any,
     * the button says Compute alone.
     */
    private fun showEstimate() {
        val subset = settings.sliderSubsetSize.value.toInt()
        val step = settings.sliderStepSize.value.toInt()
        val roi = Roi.forSolve(subset, inputs.hasCustomRoi, inputs.roi, inputs.refSize)
        val points = roi?.let { RunEstimate.gridPoints(it.w, it.h, step) } ?: 0
        val res = binding.root.resources
        showRoi(points)

        val rate = AppSettings.runPointsPerSecond(binding.root.context)
        val seconds = RunEstimate.seconds(points, inputs.deformedFrames.size, rate)
        binding.btnCalculateFullField.text = if (seconds != null) {
            res.getString(R.string.run_compute_eta_fmt, RunEstimate.duration(res, seconds))
        } else {
            res.getString(R.string.run_analysis)
        }
    }

    /**
     * The ROI row's line: the region, "Full image" or "w × h px", then its
     * [points] once there are any. A sweep solves at a step per combination
     * (subset ÷ N), not the single step the count assumes, so in sweep mode
     * the row names the region alone. The mode toggle re-checks readiness,
     * so the line follows it both ways.
     */
    private fun showRoi(points: Int) {
        val res = binding.root.resources
        val region = if (inputs.hasCustomRoi) {
            res.getString(R.string.roi_custom_fmt, inputs.roi.w, inputs.roi.h)
        } else {
            res.getString(R.string.roi_full_image)
        }
        val counted = points > 0 && !inputs.sweepMode
        settings.tvInstruction.text = if (counted) RunEstimate.regionLabel(res, region, points) else region
    }

    /** Why Next is off: the first input still missing, the lab test's included; blank when none is. */
    private fun nextReason(): String {
        val context = binding.root.context
        val inputs = inputs
        val testType = inputs.lab.testType
        return when {
            inputs.refBytes == null -> context.getString(R.string.next_reason_ref)
            inputs.deformedFrames.isEmpty() -> context.getString(R.string.next_reason_def)
            testType.hasMachineLoad && inputs.lab.machineLoads == null ->
                context.getString(
                    if (testType == TestType.BENDING) R.string.next_reason_load_typed else R.string.next_reason_load,
                )
            inputs.typedLoadsMissing() > 0 -> context.resources.getQuantityString(
                R.plurals.next_reason_load_typed_missing_fmt,
                inputs.typedLoadsMissing(),
                inputs.typedLoadsMissing(),
            )
            testType.hasMachineLoad -> loadReason(context, inputs.lab)
            else -> ""
        }
    }

    /**
     * Why Next is off for a test with a load log that has been read: no frame
     * matched a load, the stress model is missing a dimension, or bending's
     * load point is untapped. Blank when none of these holds.
     */
    private fun loadReason(context: Context, lab: LabWizardState): String = when {
        lab.machineLoads?.matchedFrames == 0 -> context.getString(R.string.next_reason_load_unmatched)
        !lab.stressModel().isComplete -> context.getString(
            if (lab.stressModel() is StressStrain.Model.Axial) {
                R.string.next_reason_cross_section
            } else {
                R.string.next_reason_dimensions
            },
        )
        lab.loadPointMissing() -> context.getString(R.string.next_reason_load_point)
        else -> ""
    }
}
