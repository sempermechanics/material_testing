@file:Suppress("CyclomaticComplexMethod", "MagicNumber", "LongParameterList")

package com.indicvision.semper.ui.analysis

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.indicvision.semper.R
import com.indicvision.semper.report.StressStrain

/**
 * Wizard readiness / Compute / Sweep enablement extracted from
 * [StaticAnalysisActivity.checkReady].
 */
object AnalysisReadyGate {

    fun apply(
        activity: AppCompatActivity,
        viewModel: AnalysisViewModel,
        isProcessing: Boolean,
        btnNext: Button,
        tvNextReason: TextView,
        frameSizeWarnRow: View,
        btnCalculateFullField: Button,
        btnDefineRoi: Button,
        btnBack: Button,
        sweepHelper: SweepSetupHelper?,
    ) {
        val ready = viewModel.isReadyToCompute() && viewModel.mechanicalInputsReady()

        val nextEnabled = ready && !isProcessing
        btnNext.isEnabled = nextEnabled
        btnNext.alpha = if (nextEnabled) 1.0f else 0.4f
        tvNextReason.text = when {
            viewModel.refBytes == null -> activity.getString(R.string.next_reason_ref)
            viewModel.defFilePaths.isEmpty() -> activity.getString(R.string.next_reason_def)
            viewModel.testType.hasMachineLoad && viewModel.machineLoads == null ->
                activity.getString(R.string.next_reason_load)
            viewModel.testType.hasMachineLoad && viewModel.machineLoads?.matchedFrames == 0 ->
                activity.getString(R.string.next_reason_load_unmatched)
            viewModel.testType.hasMachineLoad && !viewModel.stressModel().isComplete ->
                activity.getString(
                    if (viewModel.stressModel() is StressStrain.Model.Axial) {
                        R.string.next_reason_cross_section
                    } else {
                        R.string.next_reason_dimensions
                    },
                )
            viewModel.testType.hasMachineLoad && viewModel.loadPointMissing() ->
                activity.getString(R.string.next_reason_load_point)
            else -> ""
        }

        val sizeError = viewModel.frameSizeError
        if (sizeError != null) {
            frameSizeWarnRow.findViewById<TextView>(R.id.tvWarnText).text = sizeError
            frameSizeWarnRow.isVisible = true
        } else {
            frameSizeWarnRow.isVisible = false
        }

        val computeEnabled = ready &&
            viewModel.settingsReviewed &&
            !isProcessing &&
            sizeError == null &&
            !viewModel.sweepMode
        btnCalculateFullField.isEnabled = computeEnabled
        btnCalculateFullField.alpha = if (computeEnabled) 1.0f else 0.4f

        val sweepEnabled = ready &&
            viewModel.settingsReviewed &&
            !isProcessing &&
            sizeError == null &&
            viewModel.sweepMode &&
            sweepHelper != null &&
            sweepHelper.currentPlan().isNotEmpty()
        sweepHelper?.setRunSweepEnabled(sweepEnabled)

        btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        btnBack.isEnabled = !isProcessing
    }
}
