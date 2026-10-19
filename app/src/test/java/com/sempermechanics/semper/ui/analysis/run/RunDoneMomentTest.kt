package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * A single run that solved every frame holds its sheet for a moment, saying
 * so, before the viewer opens; every other ending goes on at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RunDoneMomentTest {

    private lateinit var screen: ActivityController<AppCompatActivity>
    private lateinit var activity: AppCompatActivity
    private lateinit var sheet: View
    private lateinit var header: TextView
    private lateinit var eta: TextView
    private val viewModel = AnalysisViewModel()
    private var viewersOpened = 0
    private var partials = 0

    @Before
    fun setUp() {
        screen = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
        activity = screen.get()
        sheet = View(activity)
        header = TextView(activity)
        eta = TextView(activity)
    }

    private fun controller(holdMs: Long? = HOLD_MS): BatchRunController {
        val overlay = ComputeOverlayController(
            overlay = sheet,
            header = header,
            percent = TextView(activity),
            status = TextView(activity),
            eta = eta,
            doneIcon = View(activity),
        )
        val chrome = RunChrome(activity, overlay, cancelButton = View(activity)).apply { beginRun {} }
        val host = object : BatchRunController.Host {
            override fun checkReady() = Unit
            override fun onPartialRun(outcome: BatchAnalysisOutcome) {
                partials++
            }

            override fun openResultViewer() {
                viewersOpened++
            }

            override fun engineFailureMessage(code: Int, frameIndex: Int, frameName: String?) = ""
            override fun showEngineFailureDialog(message: String, titleRes: Int, faqUrlRes: Int, why: String?) = Unit
            override fun clearEngineFailFaq() = Unit
            override fun onSweepProgress(progress: SweepStudyRunner.Progress) = Unit
            override fun onSweepFinished(outcome: BatchAnalysisOutcome?) = Unit
        }
        val line = TextView(activity)
        return if (holdMs == null) {
            BatchRunController(activity, viewModel, chrome, line, host)
        } else {
            BatchRunController(activity, viewModel, chrome, line, host, doneHoldMs = holdMs)
        }
    }

    private fun outcome(code: Int = 0, frames: Int = 3) = BatchAnalysisOutcome(
        engineErrorCode = code,
        firstFrameValidPoints = 500,
        totalFrames = frames,
        executionTimeMs = 6_600,
        batchDirPath = "",
        saved = true,
    )

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test
    fun `a solved run says so for a moment, then opens the viewer`() {
        val controller = controller().apply { observe() }
        viewModel.runs.progress.tryEmit(
            BatchProgressUpdate(
                percent = 100f,
                status = "Saving…",
                frameIndex = 3,
                plannedFrames = 3,
                framePercent = 100f,
                perFrameConvergence = floatArrayOf(96f, 91.8f, 97f),
            ),
        )
        advance(200)
        controller.handleBatchOutcome(Result.success(outcome()))

        assertEquals(View.VISIBLE, sheet.visibility)
        assertEquals("3 frames solved", header.text.toString())
        assertEquals("7 s · lowest 91% converged", eta.text.toString())
        assertEquals(0, viewersOpened)

        advance(HOLD_MS - 1)
        assertEquals(0, viewersOpened)
        advance(1)
        assertEquals(1, viewersOpened)
        assertEquals(View.GONE, sheet.visibility)
    }

    /**
     * The ticks as `runBatchAnalysisBody` sends them through the view model,
     * collected by the observing controller as on a device, then the outcome
     * posted and, in the run's finally, the progress replay set back to null:
     * the replay is empty by the time the outcome is handled, so the lowest
     * convergence has to come from what the overlay saw.
     */
    @Test
    fun `the real tick sequence gives the done moment its lowest convergence`() {
        controller().observe()
        val solved = floatArrayOf(87.4f, 88.1f, 88.2f, 88.3f, 88.9f)
        val convergence = FloatArray(5) { Float.NaN }
        val progress = viewModel.runs.progress
        progress.tryEmit(null)
        progress.tryEmit(BatchProgressUpdate(0f, "Caching reference in engine…", plannedFrames = 5))
        advance(200)
        for (frame in 0 until 5) {
            progress.tryEmit(tick(frame, 0f, convergence.copyOf()))
            for (inFrame in listOf(10f, 55f, 90f)) progress.tryEmit(tick(frame, inFrame, null))
            convergence[frame] = solved[frame]
            progress.tryEmit(tick(frame, 100f, convergence.copyOf()))
            advance(200)
        }
        progress.tryEmit(
            BatchProgressUpdate(
                percent = 100f,
                status = "Saving…",
                frameIndex = 5,
                plannedFrames = 5,
                framePercent = 100f,
                perFrameConvergence = convergence.copyOf(),
            ),
        )
        advance(200)

        viewModel.runs.batchOutcome.post(Result.success(outcome(frames = 5).copy(executionTimeMs = 6_000)))
        progress.tryEmit(null)
        advance(1)

        assertEquals("5 frames solved", header.text.toString())
        assertEquals("6 s · lowest 87% converged", eta.text.toString())
        advance(HOLD_MS)
        assertEquals(1, viewersOpened)
    }

    private fun tick(frame: Int, framePercent: Float, convergence: FloatArray?) = BatchProgressUpdate(
        percent = (frame + framePercent / 100f) / 5 * 100f,
        status = "Processing frame ${frame + 1} of 5",
        frameIndex = frame,
        plannedFrames = 5,
        framePercent = framePercent,
        perFrameConvergence = convergence,
    )

    @Test
    fun `a partial run, a low-convergence stop and a cancel go on at once`() {
        controller().handleBatchOutcome(
            Result.success(outcome(code = RunStop.LowConvergence.wireCode, frames = 2)),
        )
        assertEquals(View.GONE, sheet.visibility)
        assertEquals(1, partials)

        controller().handleBatchOutcome(Result.success(outcome(code = RunStop.Cancelled.wireCode)))
        assertEquals(View.GONE, sheet.visibility)
        assertNotEquals("3 frames solved", header.text.toString())

        controller().handleBatchOutcome(Result.failure(IllegalStateException("boom")))
        assertEquals(View.GONE, sheet.visibility)
        advance(HOLD_MS)
        assertEquals(0, viewersOpened)
    }

    @Test
    fun `no hold opens the viewer straight away`() {
        controller(holdMs = 0L).handleBatchOutcome(Result.success(outcome()))

        assertEquals(1, viewersOpened)
        assertEquals(View.GONE, sheet.visibility)
    }

    @Test
    fun `with animations off there is no hold`() {
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

        controller(holdMs = null).handleBatchOutcome(Result.success(outcome()))

        assertEquals(1, viewersOpened)
    }

    @Test
    fun `a screen that is finishing is not held`() {
        val controller = controller()
        activity.finish()

        controller.handleBatchOutcome(Result.success(outcome()))

        assertEquals(View.GONE, sheet.visibility)
        assertEquals(1, viewersOpened)
    }

    @Test
    fun `a screen left during the hold does not open the viewer`() {
        controller().handleBatchOutcome(Result.success(outcome()))
        activity.finish()
        screen.pause().stop().destroy()
        advance(HOLD_MS)

        assertEquals(0, viewersOpened)
        assertEquals(View.GONE, sheet.visibility)
    }

    @Test
    fun `a screen sent to the background during the hold opens the viewer when it is back`() {
        controller().handleBatchOutcome(Result.success(outcome()))
        screen.pause().stop()
        advance(HOLD_MS)
        assertEquals("a stopped screen starts nothing", 0, viewersOpened)
        assertEquals(View.VISIBLE, sheet.visibility)

        screen.start()
        advance(1)

        assertEquals(1, viewersOpened)
        assertEquals(View.GONE, sheet.visibility)
    }

    private companion object {
        const val HOLD_MS = 800L
    }
}
