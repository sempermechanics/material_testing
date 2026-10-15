package com.sempermechanics.semper.ui.analysis.run

import android.annotation.SuppressLint
import android.provider.Settings
import android.widget.TextView
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withStarted
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Observes [AnalysisViewModel] batch and sweep progress/outcome and routes
 * terminal results into UI callbacks owned by [StaticAnalysisActivity].
 *
 * Both runs live on the view model's scope, so this is the only place that
 * knows whether an Activity is still around to be told how they ended. An
 * outcome that arrives while the screen is stopped waits in the view model
 * and is handled, once, when the screen starts again.
 */
@SuppressLint("SetTextI18n") // same result strings as the former Activity handlers
class BatchRunController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val chrome: RunChrome,
    private val tvResult: TextView,
    private val host: Host,
    /** How long a solved run's sheet says so before the viewer opens; 0 with animations off. */
    private val doneHoldMs: Long = defaultDoneHoldMs(activity),
) {

    /** What the wizard does with each way a run can end. */
    interface Host {
        /** Re-checks what the wizard's buttons allow; once the run's chrome is down. */
        fun checkReady()

        /** A run that stopped partway with its frames saved. */
        fun onPartialRun(outcome: BatchAnalysisOutcome)

        fun openResultViewer()

        /** Why a run produced nothing, naming the frame it stopped on. */
        fun engineFailureMessage(code: Int, frameIndex: Int, frameName: String?): String

        /** [why], when given, is the longer explanation behind the dialog's Why?. */
        fun showEngineFailureDialog(message: String, titleRes: Int, faqUrlRes: Int, why: String? = null)

        fun clearEngineFailFaq()

        fun onSweepProgress(progress: SweepStudyRunner.Progress)

        /** A sweep's end; null when it failed. */
        fun onSweepFinished(outcome: BatchAnalysisOutcome?)
    }

    private val overlayController get() = chrome.overlay

    fun observe() {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.progress.collect { progress ->
                        if (progress == null) return@collect
                        overlayController.updateRun(progress)
                    }
                }
                launch {
                    viewModel.batchOutcome.consumeEach { result ->
                        handleBatchOutcome(result)
                    }
                }
                launch {
                    viewModel.sweepProgress.collect { progress ->
                        if (progress != null) host.onSweepProgress(progress)
                    }
                }
                launch {
                    viewModel.sweepOutcome.consumeEach { result ->
                        handleSweepOutcome(result)
                    }
                }
            }
        }
    }

    /**
     * A sweep ends with the same chrome teardown as a batch run but its own
     * routing: the lattice, not the frame viewer, is what a finished sweep
     * opens. A failure is reported as a null outcome, which is the shape
     * [Host.onSweepFinished] already branched on. A sweep whose session the
     * index refused is told so as a batch run is.
     */
    @VisibleForTesting
    internal fun handleSweepOutcome(result: Result<BatchAnalysisOutcome>) {
        chrome.end()
        host.checkReady()
        val outcome = result.getOrNull()
        if (outcome?.indexUnavailable == true) showNotSaved() else host.onSweepFinished(outcome)
    }

    @VisibleForTesting
    internal fun handleBatchOutcome(result: Result<BatchAnalysisOutcome>) {
        chrome.end()
        // Once, before any branch: Compute was disabled for the run, and a branch
        // that forgot to re-check left it disabled — a failed run could not be
        // re-run after changing a setting, since the sliders never re-check.
        host.checkReady()

        result.onFailure { e ->
            host.clearEngineFailFaq()
            val detail = e.message ?: e::class.java.simpleName
            tvResult.text = activity.getString(R.string.analysis_unexpected_title)
            Dialogs.info(
                activity,
                activity.getText(R.string.analysis_unexpected_title),
                activity.getString(R.string.analysis_unexpected_fmt, detail),
            )
            return
        }

        val outcome = result.getOrThrow()
        when {
            // Ahead of the cancel: a cancelled re-run saves its frames, and must
            // say so when that save could not reach the index.
            outcome.indexUnavailable -> showNotSaved()
            outcome.stop == RunStop.Cancelled -> Unit
            outcome.stop == RunStop.SessionLimit -> AnalysisNavHelper.openSessionLimit(activity)
            // Stopped early (low convergence included) with frames kept. Only a
            // run that saved them may say so: a first frame that kept no points
            // saves nothing, however many frames solved after it.
            outcome.engineErrorCode < 0 && outcome.saved && outcome.totalFrames > 0 -> {
                host.clearEngineFailFaq()
                host.onPartialRun(outcome)
            }
            outcome.engineErrorCode < 0 || outcome.firstFrameValidPoints <= 0 -> {
                showNamedEngineFailure(outcome)
            }
            else -> {
                host.clearEngineFailFaq()
                // The runner records the planned count on a saved run; frames that
                // kept no points are skipped, so kept can be below planned.
                tvResult.text = RunSummaryText.computed(
                    activity.resources,
                    kept = outcome.totalFrames,
                    planned = viewModel.lastPlannedFrames,
                )
                viewModel.lastDefPath = viewModel.defFilePaths.firstOrNull() ?: ""
                viewModel.lastBatchDirPath = outcome.batchDirPath
                showDoneThenOpen(outcome)
            }
        }
    }

    /**
     * The run solved every frame: the sheet says so for [doneHoldMs] (frames,
     * time, lowest convergence from the run's ticks), then the viewer
     * opens: once the screen is started again if it was left in the
     * background meanwhile, since a stopped screen may not start another.
     * Straight to the viewer with no hold, or when the screen is already
     * going. A screen destroyed mid-hold for a configuration change still
     * opens it; one destroyed for any other reason does not.
     */
    private fun showDoneThenOpen(outcome: BatchAnalysisOutcome) {
        if (doneHoldMs <= 0L || activity.isFinishing || activity.isDestroyed) {
            host.openResultViewer()
            return
        }
        // The convergence is the overlay's own snapshot of the run's ticks: the
        // view model's progress replay is already null here (the run's finally).
        overlayController.showDone(outcome.totalFrames, outcome.executionTimeMs)
        activity.lifecycleScope.launch {
            var opened = false
            try {
                delay(doneHoldMs)
                activity.withStarted {
                    overlayController.hide()
                    opened = true
                    host.openResultViewer()
                }
            } finally {
                if (!opened) {
                    overlayController.hide()
                    if (activity.isChangingConfigurations) host.openResultViewer()
                }
            }
        }
    }

    /** The run solved, but the session index could not be read or written, so nothing was saved. */
    private fun showNotSaved() {
        host.clearEngineFailFaq()
        tvResult.setText(R.string.analysis_not_saved_title)
        Dialogs.info(activity, R.string.analysis_not_saved_title, R.string.analysis_index_unavailable_body)
    }

    private fun showNamedEngineFailure(outcome: BatchAnalysisOutcome) {
        // Code 0 is "the first frame kept no points", which is not always the
        // strain window's fault: say which it was, with the run's own VSG and step.
        // A run that went on past such a frame and stopped later saved nothing
        // because of that first frame, so it is the one to explain.
        val zeroPoints = outcome.engineErrorCode == 0 ||
            (outcome.firstFrameValidPoints <= 0 && outcome.failedFrameIndex > 0)
        val errorMessage = if (zeroPoints) {
            EngineFailure.zeroPointsMessage(
                activity,
                outcome.firstFrameCorrelatedPoints,
                viewModel.runResult.value.spec,
            )
        } else {
            host.engineFailureMessage(outcome.engineErrorCode, outcome.failedFrameIndex, outcome.failedFrameName)
        }
        val faqRes = if (zeroPoints) {
            EngineFailure.zeroPointsFaqUrlRes(outcome.firstFrameCorrelatedPoints)
        } else {
            EngineFailure.faqUrlRes(outcome.engineErrorCode)
        }
        val why = if (zeroPoints) null else EngineFailure.whyRes(outcome.engineErrorCode)?.let(activity::getString)
        tvResult.text = "❌ Error: $errorMessage"
        host.showEngineFailureDialog(errorMessage, R.string.analysis_failed_title, faqRes, why)
    }
}

/** [BatchRunController]'s done moment: about 800 ms, none when the system's animator duration scale is 0. */
private fun defaultDoneHoldMs(activity: AppCompatActivity): Long {
    val scale = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    return if (scale == 0f) 0L else DONE_HOLD_MS
}

private const val DONE_HOLD_MS = 800L
