package com.sempermechanics.semper.analysis

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.VsgLatticeActivity
import com.sempermechanics.semper.ui.analysis.run.BatchRunController
import com.sempermechanics.semper.ui.analysis.run.ComputeOverlayController
import com.sempermechanics.semper.ui.analysis.run.EngineFailure
import com.sempermechanics.semper.ui.analysis.run.RunChrome
import com.sempermechanics.semper.ui.analysis.run.RunEnd
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.RunStatusLine
import com.sempermechanics.semper.ui.analysis.run.engineFailureMessage
import com.sempermechanics.semper.ui.analysis.run.notice
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import com.sempermechanics.semper.ui.viewer.ViewerSweepArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/**
 * How the wizard shows each way a run ends ([RunEnd]), in the one renderer a
 * batch run and a sweep share.
 *
 * Compute is disabled while a run is in flight, and only a re-check after the
 * run turns it back on — the settings sliders never re-check it. A branch that
 * skipped that call left Compute dead: after a failed run the user could change
 * the strain window, or any setting, and not run again. Every way a run can end
 * must leave Compute usable.
 *
 * A first frame that kept no points (code 0) used to be reported as a
 * strain-window failure whatever the cause; the dialog now says which. A run
 * whose result opens closes the wizard (owner decision, 2026-10-15): Home
 * launched it, so the result's Back lands on Home, and New analysis starts empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BatchRunControllerTest {

    /** The real gate's rule for this: Compute follows `!chrome.isBusy` at check time. */
    private class Gate {
        var computeEnabled = false
    }

    private lateinit var activity: AppCompatActivity
    private lateinit var chrome: RunChrome
    private lateinit var status: RunStatusLine
    private lateinit var line: TextView
    private lateinit var faq: View
    private val context: Application = ApplicationProvider.getApplicationContext()

    /** A controller over a fresh screen whose run is in flight when its outcome arrives. */
    private fun controller(gate: Gate = Gate()): BatchRunController {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        val overlay = ComputeOverlayController(
            overlay = View(activity),
            header = TextView(activity),
            percent = TextView(activity),
            status = TextView(activity),
            eta = TextView(activity),
        )
        chrome = RunChrome(activity, overlay, cancelButton = View(activity)).apply { beginRun {} }
        line = TextView(activity).apply { text = "before" }
        faq = View(activity).apply { isVisible = false }
        status = RunStatusLine(activity, chrome, line, faq)
        return BatchRunController(
            activity,
            AnalysisViewModel(),
            chrome,
            status,
            recheck = { gate.computeEnabled = !chrome.isBusy },
            doneHoldMs = 0L,
        )
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

    /** A saved single run of [kept] frames of [planned], in session `saved`. */
    private fun row(kept: Int = 3, planned: Int = kept): SessionRecord =
        sessionRecord(id = "saved", frameCount = kept, sessionDir = "/sessions/saved").copy(plannedFrameCount = planned)

    private val sweepRow = sessionRecord(id = "sweep", frameCount = 2, sessionDir = "/sessions/sweep")
        .copy(sweepSubsets = listOf(21, 31), sweepSteps = listOf(5, 5), sweepStrainWindows = listOf(15, 15))

    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog

    private fun messageOf(dialog: AlertDialog) = dialog.findViewById<TextView>(android.R.id.message)?.text.toString()

    private fun startedClass() = shadowOf(activity).nextStartedActivity?.component?.className

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `every way a run ends leaves Compute usable`() {
        listOf(
            RunEnd.Cancelled,
            RunEnd.SessionLimit,
            RunEnd.NotSaved,
            RunEnd.Unexpected("boom"),
            RunEnd.Failed(EngineFailure.ENGINE_ERROR_FEATURES, 0, null),
            RunEnd.Failed(0, -1, null, RunEnd.EmptyFirstFrame(441, strainFailSpec)),
            RunEnd.Partial(row(kept = 2, planned = 4), RunStop.LowConvergence.wireCode, 3, 2, "c.png"),
            RunEnd.Solved(row(), 0),
            RunEnd.SweepSaved(sweepRow),
            RunEnd.SweepFailed(ViewerArgs.ofFrames("/sessions/sweep", 64, 64, 5)),
        ).forEach { end ->
            val gate = Gate()
            controller(gate).render(end)
            assertTrue("Compute stayed disabled after: $end", gate.computeEnabled)
        }
    }

    // ------------------------------------------------------------ failures

    @Test
    fun `points that correlated but fit no strain name the strain window, with the run's VSG and step`() {
        val notice = RunEnd.Failed(0, -1, null, RunEnd.EmptyFirstFrame(441, strainFailSpec)).notice(context)

        assertEquals(
            context.resources.getQuantityString(R.plurals.run_fail_strain_window_fmt, 441, 441, 601, 20),
            notice.message,
        )
        assertEquals(R.string.url_faq_engine_vsg, notice.faqUrlRes)
        assertNull(notice.why)
    }

    @Test
    fun `a frame where nothing correlated does not blame the strain window`() {
        val notice = RunEnd.Failed(0, -1, null, RunEnd.EmptyFirstFrame(0, strainFailSpec)).notice(context)

        assertEquals(context.getString(R.string.run_fail_no_correlation), notice.message)
        assertEquals(R.string.url_faq_engine_features, notice.faqUrlRes)
    }

    @Test
    fun `a frame that never reached the engine is reported as unreadable`() {
        val notice = RunEnd.Failed(0, -1, null, RunEnd.EmptyFirstFrame(-1, strainFailSpec)).notice(context)

        assertEquals(context.getString(R.string.sweep_fail_init), notice.message)
        assertEquals(R.string.url_faq_engine_init, notice.faqUrlRes)
    }

    @Test
    fun `a convergence failure keeps its decorrelation explanation for Why`() {
        val convergence = RunEnd.Failed(RunStop.LowConvergence.wireCode, 1, null).notice(context)
        assertEquals(context.getString(R.string.error_low_convergence_why), convergence.why)
        assertEquals(R.string.url_faq_engine_convergence, convergence.faqUrlRes)

        // A reason that already says it all has no Why? text of its own: Why? opens the FAQ.
        assertNull(RunEnd.Failed(EngineFailure.ENGINE_ERROR_FEATURES, 1, null).notice(context).why)
    }

    @Test
    fun `a failure names the frame it stopped on`() {
        val reason = context.getString(EngineFailure.reasonRes(-3), -3)
        assertEquals(reason, engineFailureMessage(context, -3, frameIndex = -1, frameName = null))
        assertEquals(
            context.getString(R.string.failure_frame_fmt, 3, "c.png") + reason,
            engineFailureMessage(context, -3, frameIndex = 2, frameName = "c.png"),
        )
        assertEquals(
            context.getString(R.string.failure_frame_no_name_fmt, 3) + reason,
            engineFailureMessage(context, -3, frameIndex = 2, frameName = null),
        )
    }

    @Test
    fun `a failure says why in its dialog and on the status line, and leaves its FAQ there`() {
        val failed = RunEnd.Failed(EngineFailure.ENGINE_ERROR_FEATURES, 2, "c.png")
        controller().render(failed)
        val message = failed.notice(activity).message

        assertEquals(message, messageOf(latestDialog()))
        assertEquals(activity.getString(R.string.run_failed_status_fmt, message), line.text.toString())
        assertTrue(faq.isVisible)
        assertTrue(faq.hasOnClickListeners())
        assertNull("no viewer", shadowOf(activity).nextStartedActivity)

        status.setFaq(null)
        assertFalse(faq.isVisible)
    }

    @Test
    fun `a run that threw says what it said, batch run or sweep`() {
        controller().render(RunEnd.Unexpected("boom"))

        assertEquals(activity.getString(R.string.analysis_unexpected_title), line.text.toString())
        assertEquals(activity.getString(R.string.analysis_unexpected_fmt, "boom"), messageOf(latestDialog()))
        assertNull("no toast stands in for it", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an index that could not be written says the run was not saved, not that the limit is reached`() {
        controller().render(RunEnd.NotSaved)

        assertNull("no session-limit screen", shadowOf(activity).nextStartedActivity)
        assertEquals(activity.getString(R.string.analysis_not_saved_title), line.text.toString())
        val dialog = latestDialog()
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.analysis_index_unavailable_body), messageOf(dialog))
    }

    @Test
    fun `a cancelled run, which kept nothing, opens nothing and says nothing`() {
        val before = ShadowDialog.getLatestDialog()
        controller().render(RunEnd.Cancelled)

        assertEquals(before, ShadowDialog.getLatestDialog())
        assertNull("no viewer", shadowOf(activity).nextStartedActivity)
        assertEquals("before", line.text.toString())
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `a full quota at save time still opens the session-limit screen`() {
        controller().render(RunEnd.SessionLimit)

        assertTrue(shadowOf(activity).nextStartedActivity != null)
        assertFalse("the wizard stays", activity.isFinishing)
    }

    // ------------------------------------------------------------ results

    @Test
    fun `a finished run opens its saved row and closes the wizard, so the result's Back goes Home`() {
        controller().render(RunEnd.Solved(row(), 0))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(ResultViewerActivity::class.java.name, started.component?.className)
        assertEquals("the run's own session", "saved", started.getStringExtra(IntentKeys.SESSION_LOCAL_ID))
        assertEquals("/sessions/saved", started.getStringExtra(IntentKeys.BATCH_DIR_PATH))
        assertTrue("the wizard is not left under the result", activity.isFinishing)
    }

    /** The status line a finished run of [kept] frames of [planned] leaves. */
    private fun successLine(kept: Int, planned: Int): String {
        controller().render(RunEnd.Solved(row(kept, planned), 0))
        return line.text.toString()
    }

    @Test
    fun `a finished run counts its frames with a plural, not a hard-coded English line`() {
        assertEquals("✅ Computed 1 frame", successLine(kept = 1, planned = 1))
        assertEquals("✅ Computed 3 frames", successLine(kept = 3, planned = 3))
    }

    @Test
    fun `frames dropped for keeping no points are counted, not hidden`() {
        assertEquals(
            "✅ Computed 3 of 5 frames — 2 frames kept no valid points",
            successLine(kept = 3, planned = 5),
        )
        assertEquals(
            "✅ Computed 4 of 5 frames — 1 frame kept no valid points",
            successLine(kept = 4, planned = 5),
        )
    }

    @Test
    fun `a run that stopped early names the frame, counts what it kept, and keeps the rest behind Why`() {
        val stopped = RunEnd.Partial(row(kept = 2, planned = 4), RunStop.LowConvergence.wireCode, 3, 2, "3.png")
        controller().render(stopped)
        idle()

        val dialog = latestDialog()
        val title = dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()
        assertEquals("Stopped at frame 3", title)
        assertEquals("2 of 4 frames kept. Convergence fell below 50% on 2 frames in a row.", messageOf(dialog))
        assertFalse("the dialog explains first", activity.isFinishing)

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        idle()
        assertTrue("Why? leaves the results one tap away", dialog.isShowing)
        val why = messageOf(latestDialog())
        assertTrue(why, why.contains("Frame 3 (3.png)"))
        assertTrue(why, why.contains(activity.getString(R.string.error_low_convergence_why)))
    }

    @Test
    fun `a stop with frames saved opens its result from View results and closes the wizard`() {
        val stopped = RunEnd.Partial(row(kept = 2, planned = 4), RunStop.LowConvergence.wireCode, 3, 2, null)
        controller().render(stopped)
        idle()

        latestDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()

        assertEquals(ResultViewerActivity::class.java.name, startedClass())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a finished sweep opens its lattice from its row and closes the wizard`() {
        controller().render(RunEnd.SweepSaved(sweepRow))

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(VsgLatticeActivity::class.java.name, started.component?.className)
        assertEquals(listOf(21, 31), started.getIntArrayExtra(IntentKeys.SWEEP_SUBSETS)?.toList())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a sweep that dropped combinations says so as it opens`() {
        val partial = sweepRow.copy(sweepSkippedNodes = listOf(SkippedNode(41, 5, 15, -3)))
        controller().render(RunEnd.SweepSaved(partial))

        assertEquals(
            activity.resources.getQuantityString(R.plurals.sweep_partial_fmt, 1, 1, 3),
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals(VsgLatticeActivity::class.java.name, startedClass())
    }

    @Test
    fun `a sweep that solved nothing opens its lattice of failed nodes`() {
        val nodes = ViewerSweepArgs(emptyList(), emptyList(), emptyList(), lineCutHorizontal = true, skippedJson = "[]")
        val lattice = ViewerArgs.ofFrames("/sessions/sweep", 64, 64, 5).copy(sweep = nodes)
        controller().render(RunEnd.SweepFailed(lattice))

        assertEquals(VsgLatticeActivity::class.java.name, startedClass())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `the status line clears on new input, but not mid-run`() {
        controller()
        status.show("done")
        status.clear()
        assertEquals("done", line.text.toString())

        chrome.end()
        status.clear()
        assertEquals("", line.text.toString())
    }
}
