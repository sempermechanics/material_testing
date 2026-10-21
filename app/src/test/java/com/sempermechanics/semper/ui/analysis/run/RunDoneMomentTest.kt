package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
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
    private var openedCount = 0

    /** Viewers the screen has opened so far. */
    private val viewersOpened: Int
        get() {
            while (shadowOf(activity).nextStartedActivity != null) openedCount++
            return openedCount
        }

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
        val status = RunStatusLine(activity, chrome, TextView(activity), View(activity))
        return if (holdMs == null) {
            BatchRunController(activity, viewModel, chrome, status, recheck = {})
        } else {
            BatchRunController(activity, viewModel, chrome, status, recheck = {}, doneHoldMs = holdMs)
        }
    }

    /** A run that solved [frames] frames in [executionTimeMs] and saved them. */
    private fun solved(frames: Int = 3, executionTimeMs: Int = 6_600) = RunEnd.Solved(
        sessionRecord(id = "run", frameCount = frames, sessionDir = "/sessions/run").copy(plannedFrameCount = frames),
        executionTimeMs,
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
        controller.render(solved())

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

        viewModel.runs.batchOutcome.post(solved(frames = 5, executionTimeMs = 6_000))
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
        val kept = sessionRecord(id = "run", frameCount = 2).copy(plannedFrameCount = 3)
        controller().render(RunEnd.Partial(kept, RunStop.LowConvergence.wireCode, 2, 1, null))
        assertEquals(View.GONE, sheet.visibility)
        assertTrue("its frames-kept dialog", ShadowDialog.getLatestDialog().isShowing)

        controller().render(RunEnd.Cancelled)
        assertEquals(View.GONE, sheet.visibility)
        assertNotEquals("3 frames solved", header.text.toString())

        controller().render(RunEnd.Unexpected("boom"))
        assertEquals(View.GONE, sheet.visibility)
        advance(HOLD_MS)
        assertEquals(0, viewersOpened)
    }

    @Test
    fun `no hold opens the viewer straight away`() {
        controller(holdMs = 0L).render(solved())

        assertEquals(1, viewersOpened)
        assertEquals(View.GONE, sheet.visibility)
    }

    @Test
    fun `with animations off there is no hold`() {
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)

        controller(holdMs = null).render(solved())

        assertEquals(1, viewersOpened)
    }

    @Test
    fun `a screen that is finishing is not held`() {
        val controller = controller()
        activity.finish()

        controller.render(solved())

        assertEquals(View.GONE, sheet.visibility)
        assertEquals(1, viewersOpened)
    }

    @Test
    fun `a screen left during the hold does not open the viewer`() {
        controller().render(solved())
        activity.finish()
        screen.pause().stop().destroy()
        advance(HOLD_MS)

        assertEquals(0, viewersOpened)
        assertEquals(View.GONE, sheet.visibility)
    }

    @Test
    fun `a screen sent to the background during the hold opens the viewer when it is back`() {
        controller().render(solved())
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
