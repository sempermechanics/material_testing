package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import kotlinx.coroutines.launch

/**
 * Observes [AnalysisViewModel] batch progress/outcome and routes terminal
 * results into UI callbacks owned by [StaticAnalysisActivity].
 */
@SuppressLint("SetTextI18n") // same result strings as the former Activity handlers
@Suppress("LongParameterList") // Activity-bound callbacks; grouping would just rename the fan-out
class BatchRunController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val overlayHelper: ComputeOverlayHelper,
    private val tvResult: TextView,
    private val setProcessing: (Boolean) -> Unit,
    private val checkReady: () -> Unit,
    private val onPartialRun: (AnalysisViewModel.BatchAnalysisOutcome) -> Unit,
    private val openResultViewer: () -> Unit,
    private val engineFailureMessage: (code: Int, frameIndex: Int, frameName: String?) -> String,
    private val showEngineFailureDialog: (code: Int, titleRes: Int, frameIndex: Int, frameName: String?) -> Unit,
) {

    fun observe() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.progress.collect { progress ->
                        if (progress == null) return@collect
                        overlayHelper.update(
                            percent = progress.percent,
                            status = progress.status,
                            title = progress.status,
                            pointsSolved = progress.pointsSolved,
                            convergencePercent = progress.convergencePercent,
                        )
                    }
                }
                launch {
                    viewModel.batchOutcome.collect { result ->
                        handleBatchOutcome(result)
                    }
                }
            }
        }
    }

    private fun handleBatchOutcome(result: Result<AnalysisViewModel.BatchAnalysisOutcome>) {
        setProcessing(false)
        overlayHelper.hide()
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        result.onFailure { e ->
            val detail = e.message ?: e::class.java.simpleName
            tvResult.text = activity.getString(R.string.analysis_unexpected_title)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.analysis_unexpected_title)
                .setMessage(activity.getString(R.string.analysis_unexpected_fmt, detail))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            checkReady()
            return
        }

        val outcome = result.getOrThrow()
        when {
            outcome.engineErrorCode == AnalysisRunCodes.ERROR_CANCELLED -> checkReady()
            outcome.engineErrorCode == AnalysisRunCodes.ERROR_SESSION_LIMIT -> {
                checkReady()
                AnalysisNavHelper.openSessionLimit(activity)
            }
            outcome.engineErrorCode == AnalysisRunCodes.ERROR_LOW_CONVERGENCE &&
                outcome.totalFrames > 0 -> onPartialRun(outcome)
            outcome.engineErrorCode < 0 && outcome.totalFrames > 0 -> onPartialRun(outcome)
            outcome.engineErrorCode < 0 -> {
                val errorMsg = engineFailureMessage(
                    outcome.engineErrorCode,
                    outcome.failedFrameIndex,
                    outcome.failedFrameName,
                )
                tvResult.text = "❌ Error: $errorMsg"
                showEngineFailureDialog(
                    outcome.engineErrorCode,
                    R.string.analysis_failed_title,
                    outcome.failedFrameIndex,
                    outcome.failedFrameName,
                )
            }
            outcome.firstFrameValidPoints <= 0 -> {
                tvResult.text = activity.getString(R.string.analysis_no_data_title)
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.analysis_no_data_title)
                    .setMessage(R.string.analysis_no_data)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            else -> {
                tvResult.text = "✅ Computed ${outcome.totalFrames} frames!"
                viewModel.lastDefPath = viewModel.defFilePaths.firstOrNull() ?: ""
                viewModel.lastBatchDirPath = outcome.batchDirPath
                viewModel.hasCompletedAnalysis = true
                checkReady()
                openResultViewer()
            }
        }
    }
}
