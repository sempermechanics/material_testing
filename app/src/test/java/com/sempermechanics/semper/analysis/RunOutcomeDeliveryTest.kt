package com.sempermechanics.semper.analysis

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.run.BatchRunController
import com.sempermechanics.semper.ui.analysis.run.ComputeOverlayController
import com.sempermechanics.semper.ui.analysis.run.RunChrome
import com.sempermechanics.semper.ui.analysis.run.RunEnd
import com.sempermechanics.semper.ui.analysis.run.RunStatusLine
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.PendingOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * TD-166: a run that ends while the wizard is in the background must still
 * tear its chrome down and open its result when the wizard comes back, and
 * only once. The outcomes used to be a no-replay `SharedFlow`, collected
 * only while the screen was started, so an outcome with no collector was
 * dropped and the wizard sat behind a run overlay that never went away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RunOutcomeDeliveryTest {

    private val built = mutableListOf<ActivityController<AppCompatActivity>>()

    /** Results the screens opened so far: viewers and lattices. */
    private var openedCount = 0
    private var computeEnabled = false

    /** [openedCount], after counting what every screen has started since the last call. */
    private fun opened(): Int {
        built.forEach { ac -> while (shadowOf(ac.get()).nextStartedActivity != null) openedCount++ }
        return openedCount
    }

    @After
    fun tearDown() = built.forEach { runCatching { it.pause().stop().destroy() } }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A started wizard observing [viewModel]; its chrome is up when [runInFlight]. */
    private fun screen(
        viewModel: AnalysisViewModel,
        runInFlight: Boolean = true,
    ): ActivityController<AppCompatActivity> {
        val ac = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
        built += ac
        val activity = ac.get()
        val overlay = ComputeOverlayController(
            overlay = View(activity),
            header = TextView(activity),
            percent = TextView(activity),
            status = TextView(activity),
            eta = TextView(activity),
        )
        val chrome = RunChrome(activity, overlay, cancelButton = View(activity))
        if (runInFlight) chrome.beginRun {}
        val status = RunStatusLine(activity, chrome, TextView(activity), View(activity))
        // No done moment: these tests count the viewer opening as the outcome is handled.
        BatchRunController(
            activity,
            viewModel,
            chrome,
            status,
            recheck = { computeEnabled = !chrome.isBusy },
            doneHoldMs = 0L,
        ).observe()
        idle()
        return ac
    }

    /** A run that solved [frames] frames and saved them. */
    private fun solved(frames: Int = 3): RunEnd =
        RunEnd.Solved(sessionRecord(id = "run", frameCount = frames, sessionDir = "/sessions/run"), 0)

    /** A sweep of [frames] combinations, saved. */
    private fun sweep(frames: Int): RunEnd = RunEnd.SweepSaved(
        sessionRecord(id = "sweep", frameCount = frames, sessionDir = "/sessions/sweep")
            .copy(sweepSubsets = List(frames) { 21 }, sweepSteps = List(frames) { 5 }),
    )

    @Test
    fun `a batch run that ends while the wizard is stopped is handled when it starts again`() {
        val viewModel = AnalysisViewModel()
        val ac = screen(viewModel)
        ac.pause().stop()
        idle()

        viewModel.runs.batchOutcome.post(solved())
        idle()
        assertEquals("nothing handled while stopped", 0, opened())
        assertTrue(viewModel.batchOutcome.isPending)

        ac.start().resume()
        idle()
        assertEquals(1, opened())
        assertTrue("chrome down, Compute usable", computeEnabled)
        assertFalse(viewModel.batchOutcome.isPending)
    }

    @Test
    fun `a handled batch outcome is not handled again when the wizard restarts`() {
        val viewModel = AnalysisViewModel()
        val ac = screen(viewModel)
        viewModel.runs.batchOutcome.post(solved())
        idle()
        assertEquals(1, opened())

        ac.pause().stop()
        idle()
        ac.start().resume()
        idle()
        // A second screen over the same view model sees nothing either.
        screen(viewModel)
        assertEquals(1, opened())
    }

    @Test
    fun `a sweep that ends while the wizard is stopped is handled once when it starts again`() {
        val viewModel = AnalysisViewModel()
        val ac = screen(viewModel)
        ac.pause().stop()
        idle()

        viewModel.runs.sweepOutcome.post(sweep(frames = 9))
        idle()
        assertEquals("nothing handled while stopped", 0, opened())

        ac.start().resume()
        idle()
        assertEquals("its lattice", 1, opened())
        assertTrue(computeEnabled)

        ac.pause().stop()
        idle()
        ac.start().resume()
        idle()
        assertEquals("handled once", 1, opened())
    }

    @Test
    fun `a run the destroyed screen cancelled ends quietly on the next screen, once`() {
        // onDestroy flips the cancel flag on every destroy. A screen recreated
        // over the same view model (a configuration change the manifest does not
        // absorb) never raised the run's chrome; the cancelled outcome must not
        // open anything there, only leave Compute usable.
        val viewModel = AnalysisViewModel()
        viewModel.runs.batchOutcome.post(RunEnd.Cancelled)

        screen(viewModel, runInFlight = false)
        assertEquals(0, opened())
        assertTrue(computeEnabled)
        assertFalse(viewModel.batchOutcome.isPending)

        computeEnabled = false
        screen(viewModel, runInFlight = false)
        assertFalse("taken by the first screen only", computeEnabled)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `of two collectors that see one outcome, only one handles it`() = runTest(UnconfinedTestDispatcher()) {
        val pending = PendingOutcome<String>()
        val handled = mutableListOf<String>()
        val first = launch { pending.consumeEach { handled += "first:$it" } }
        val second = launch { pending.consumeEach { handled += "second:$it" } }

        pending.post("done")
        pending.post("again")

        assertEquals(2, handled.size)
        assertEquals(listOf("done", "again"), handled.map { it.substringAfter(':') })
        assertFalse(pending.isPending)
        first.cancel()
        second.cancel()
    }
}
