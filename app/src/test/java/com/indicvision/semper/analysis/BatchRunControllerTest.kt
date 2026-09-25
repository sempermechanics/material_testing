package com.indicvision.semper.analysis

import android.app.Application
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.AnalysisRunCodes
import com.indicvision.semper.ui.analysis.AnalysisViewModel
import com.indicvision.semper.ui.analysis.BatchRunController
import com.indicvision.semper.ui.analysis.ComputeOverlayHelper
import com.indicvision.semper.ui.analysis.EngineFailure
import com.indicvision.semper.ui.analysis.RunSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compute is disabled while a run is in flight, and only a `checkReady()`
 * after the run turns it back on — the settings sliders never re-check it.
 * A branch that skipped that call left Compute dead: after a failed run the
 * user could change the strain window, or any setting, and not run again.
 * Every way a single run can end must leave Compute usable.
 *
 * A first frame that kept no points (code 0) used to be reported as a
 * strain-window failure whatever the cause; the dialog now says which.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BatchRunControllerTest {

    /** The real gate's rule for this: Compute follows `!isProcessing` at check time. */
    private class Gate {
        var processing = true
        var computeEnabled = false
    }

    private data class Shown(val message: String, val faqUrlRes: Int)

    private lateinit var activity: AppCompatActivity

    private fun controller(
        gate: Gate,
        viewModel: AnalysisViewModel = AnalysisViewModel(),
        onDialog: (Shown) -> Unit = {},
    ): BatchRunController {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        val overlay = ComputeOverlayHelper(
            overlay = View(activity),
            title = TextView(activity),
            progress = ProgressBar(activity),
            percent = TextView(activity),
            status = TextView(activity),
            elapsed = TextView(activity),
        )
        return BatchRunController(
            activity = activity,
            viewModel = viewModel,
            overlayHelper = overlay,
            tvResult = TextView(activity),
            setProcessing = { gate.processing = it },
            checkReady = { gate.computeEnabled = !gate.processing },
            onPartialRun = {},
            openResultViewer = {},
            engineFailureMessage = { _, _, _ -> "" },
            showEngineFailureDialog = { message, _, faqUrlRes -> onDialog(Shown(message, faqUrlRes)) },
            clearEngineFailFaq = {},
            onSweepProgress = {},
            onSweepFinished = {},
        )
    }

    private fun outcome(code: Int, validPoints: Int, frames: Int, correlated: Int = -1) =
        AnalysisViewModel.BatchAnalysisOutcome(
            engineErrorCode = code,
            firstFrameValidPoints = validPoints,
            totalFrames = frames,
            executionTimeMs = 0,
            batchDirPath = "",
            failedFrameIndex = if (code < 0 || validPoints == 0) 0 else -1,
            firstFrameCorrelatedPoints = correlated,
        )

    /** The dialog a zero-point first frame raises, for a run with [spec]. */
    private fun zeroPointDialog(correlated: Int, spec: RunSpec): Shown {
        val viewModel = AnalysisViewModel()
        viewModel.resetRunResult("", spec)
        var shown: Shown? = null
        controller(Gate(), viewModel) { shown = it }
            .handleBatchOutcome(Result.success(outcome(code = 0, validPoints = 0, frames = 0, correlated)))
        return checkNotNull(shown) { "no failure dialog" }
    }

    private val strainFailSpec = RunSpec.of(
        subset = 41,
        step = 20,
        strainWindow = 601,
        roi = intArrayOf(30, 30, 452, 452),
        mask = null,
        use6x6 = false,
        debugDir = null,
    )

    private fun assertComputeUsableAfter(name: String, result: Result<AnalysisViewModel.BatchAnalysisOutcome>) {
        val gate = Gate()
        controller(gate).handleBatchOutcome(result)
        assertTrue("Compute stayed disabled after: $name", gate.computeEnabled)
    }

    @Test
    fun `a strain-only failure leaves Compute usable`() {
        // ICGN solved the grid, then every point failed the VSG fill rule: 0 points, code 0.
        assertComputeUsableAfter("VSG failure", Result.success(outcome(code = 0, validPoints = 0, frames = 0)))
    }

    @Test
    fun `points that correlated but fit no strain name the strain window, with the run's VSG and step`() {
        val shown = zeroPointDialog(correlated = 441, spec = strainFailSpec)

        assertEquals(
            activity.resources.getQuantityString(R.plurals.run_fail_strain_window_fmt, 441, 441, 601, 20),
            shown.message,
        )
        assertEquals(R.string.url_faq_engine_vsg, shown.faqUrlRes)
    }

    @Test
    fun `a frame where nothing correlated does not blame the strain window`() {
        val shown = zeroPointDialog(correlated = 0, spec = strainFailSpec)

        assertEquals(activity.getString(R.string.run_fail_no_correlation), shown.message)
        assertEquals(R.string.url_faq_engine_features, shown.faqUrlRes)
    }

    @Test
    fun `a frame that never reached the engine is reported as unreadable`() {
        val shown = zeroPointDialog(correlated = -1, spec = strainFailSpec)

        assertEquals(activity.getString(R.string.sweep_fail_init), shown.message)
        assertEquals(R.string.url_faq_engine_init, shown.faqUrlRes)
    }

    @Test
    fun `every named engine failure leaves Compute usable`() {
        listOf(
            EngineFailure.ENGINE_ERROR_FEATURES,
            EngineFailure.ENGINE_ERROR_ROI,
            EngineFailure.ENGINE_ERROR_INIT,
        ).forEach { code ->
            assertComputeUsableAfter("engine code $code", Result.success(outcome(code, validPoints = 0, frames = 0)))
        }
    }

    @Test
    fun `the other endings leave Compute usable too`() {
        assertComputeUsableAfter(
            "cancel",
            Result.success(outcome(AnalysisRunCodes.ERROR_CANCELLED, validPoints = 0, frames = 0)),
        )
        assertComputeUsableAfter(
            "session limit",
            Result.success(outcome(AnalysisRunCodes.ERROR_SESSION_LIMIT, validPoints = 0, frames = 0)),
        )
        assertComputeUsableAfter(
            "partial run",
            Result.success(outcome(AnalysisRunCodes.ERROR_LOW_CONVERGENCE, validPoints = 500, frames = 2)),
        )
        assertComputeUsableAfter("success", Result.success(outcome(code = 0, validPoints = 500, frames = 3)))
        assertComputeUsableAfter("exception", Result.failure(IllegalStateException("boom")))
    }
}
