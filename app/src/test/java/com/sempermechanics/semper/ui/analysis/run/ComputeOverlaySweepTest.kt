package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.LiveSweepLattice
import com.sempermechanics.semper.ui.analysis.sweep.SweepLatticeView
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner.NodeOutcome
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** What the run overlay says and shows for a sweep's ticks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ComputeOverlaySweepTest {

    private lateinit var activity: AppCompatActivity
    private lateinit var status: TextView
    private lateinit var header: TextView
    private lateinit var percent: TextView
    private lateinit var bar: ProgressBar
    private lateinit var bins: ConvergenceBinsView
    private lateinit var sweepGroup: FrameLayout
    private lateinit var last: TextView
    private lateinit var lattice: SweepLatticeView
    private lateinit var overlay: ComputeOverlayController

    private val plan = listOf(
        SweepStudy.Point(subset = 21, step = 7, window = 5),
        SweepStudy.Point(subset = 31, step = 10, window = 5),
        SweepStudy.Point(subset = 31, step = 10, window = 9),
        SweepStudy.Point(subset = 41, step = 14, window = 9),
    )

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        status = TextView(activity)
        header = TextView(activity)
        percent = TextView(activity)
        bar = ProgressBar(activity)
        bins = ConvergenceBinsView(activity)
        sweepGroup = FrameLayout(activity)
        last = TextView(activity)
        lattice = SweepLatticeView(activity).apply { compact = true }
        overlay = ComputeOverlayController(
            overlay = View(activity),
            header = header,
            percent = percent,
            status = status,
            eta = TextView(activity),
            bar = bar,
            bins = bins,
            sweepLattice = LiveSweepLattice(sweepGroup, lattice, last),
        )
    }

    private fun flush() {
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
    }

    private fun tick(
        runIndex: Int,
        outcomes: List<NodeOutcome>,
        points: Int = 0,
        convergence: Float = -1f,
        lastSolved: SweepStudyRunner.Solved? = null,
    ) = SweepStudyRunner.Progress(
        runIndex = runIndex,
        totalRuns = plan.size,
        percent = outcomes.count { it != NodeOutcome.PENDING } * 100 / plan.size,
        point = plan[runIndex],
        pointsSolved = points,
        convergencePercent = convergence,
        outcomes = outcomes,
        lastSolved = lastSolved,
    )

    @Test
    fun `a sweep shows its lattice instead of the bins or the bar, and a batch run swaps back`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)
        assertEquals(View.VISIBLE, sweepGroup.visibility)
        assertEquals(View.GONE, bins.visibility)
        assertEquals(View.GONE, bar.visibility)
        assertEquals("Sweep", header.text.toString())
        assertEquals("4 analyses", status.text.toString())

        overlay.show()
        assertEquals(View.GONE, sweepGroup.visibility)
        assertEquals(View.VISIBLE, bins.visibility)
    }

    @Test
    fun `the header names the analysis and the status the combination being solved`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)

        val outcomes = listOf(NodeOutcome.SOLVED, NodeOutcome.SKIPPED, NodeOutcome.PENDING, NodeOutcome.PENDING)
        overlay.updateSweep(tick(2, outcomes))
        flush()

        assertEquals("Analysis 3 of 4", header.text.toString())
        // Window 9 points at step 10 is a VSG of (9 - 1) × 10 + 1 = 81 px.
        assertEquals("Subset 31 · window 81 px", status.text.toString())
        assertEquals("50.0%", percent.text.toString())
    }

    @Test
    fun `once every combination has ended the header counts the solved ones while it saves`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)

        val outcomes = listOf(NodeOutcome.SOLVED, NodeOutcome.SKIPPED, NodeOutcome.SOLVED, NodeOutcome.SOLVED)
        overlay.updateSweep(tick(3, outcomes))
        flush()

        assertEquals("Solved 3 of 4 analyses · saving", header.text.toString())
        assertEquals(View.GONE, status.visibility)
        assertEquals("100.0%", percent.text.toString())
    }

    @Test
    fun `the run overlay's lattice draws its range labels, not the compact ticks`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)
        assertEquals(false, lattice.compact)
    }

    @Test
    fun `the last line is hidden until a combination ends, then says how it went`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)
        val pending = List(plan.size) { NodeOutcome.PENDING }
        overlay.updateSweep(tick(0, pending))
        flush()
        assertEquals(View.GONE, last.visibility)

        val first = listOf(NodeOutcome.SOLVED) + pending.drop(1)
        // As on a device: the combination's own end tick is dropped by the
        // progress flow (the next start follows at once), so only the start
        // of combination 2 arrives, carrying how combination 1 went.
        overlay.updateSweep(tick(1, first, lastSolved = SweepStudyRunner.Solved(0, 30_968, 96.4f)))
        flush()
        assertEquals(View.VISIBLE, last.visibility)
        assertEquals("Last: 96% converged · 30,968 points", last.text.toString())
        assertEquals(activity.getColor(R.color.text_secondary), last.currentTextColor)
    }

    @Test
    fun `a skipped combination is said in red`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)
        val outcomes = listOf(NodeOutcome.SOLVED, NodeOutcome.SKIPPED, NodeOutcome.PENDING, NodeOutcome.PENDING)
        overlay.updateSweep(tick(2, outcomes))
        flush()

        assertEquals("Last: skipped", last.text.toString())
        assertEquals(activity.getColor(R.color.semantic_danger), last.currentTextColor)

        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)
        assertEquals("a new sweep starts without it", View.GONE, last.visibility)
    }

    @Test
    fun `a tick without the solved combination's numbers says only that it solved`() {
        overlay.show("Sweep", "4 analyses", showConvergence = false, sweepPlan = plan)
        val outcomes = listOf(NodeOutcome.SOLVED, NodeOutcome.PENDING, NodeOutcome.PENDING, NodeOutcome.PENDING)
        overlay.updateSweep(tick(1, outcomes))
        flush()

        assertEquals("Last: solved", last.text.toString())
    }
}
