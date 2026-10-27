package com.sempermechanics.semper.ui.analysis.sweep

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.ui.analysis.run.RunOverlayText
import com.sempermechanics.semper.ui.analysis.run.SemperEngine
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner.NodeOutcome
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisCancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a sweep's progress ticks carry, from the runner's own loop with a
 * stand-in engine: each solved combination's points and convergence, on its
 * end tick and on every tick after it. The flow to the screen keeps only the
 * newest tick, and a combination's end is followed at once by the next one's
 * start, so the start tick is the one that usually arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SweepStudyRunnerProgressTest {

    @get:Rule
    val dir = TemporaryFolder()

    private val native = SemperEngine.solver
    private val cancelToEngine = AnalysisCancel.toEngine

    /** A JVM test has no `semper_core`: the cancel flag stays on this side (TD-188). */
    @Before
    fun noNativeCancel() {
        AnalysisCancel.toEngine = {}
        SweepStudyRunner.cancelRequested = false
    }

    @After
    fun restore() {
        SemperEngine.solver = native
        AnalysisCancel.toEngine = cancelToEngine
    }

    private val plan = listOf(
        SweepStudy.Point(subset = 21, step = 7, window = 5),
        SweepStudy.Point(subset = 21, step = 7, window = 9),
        SweepStudy.Point(subset = 31, step = 10, window = 5),
    )

    /** Combination 1 solves (300 points, 96.4%), 2 is skipped, 3 solves (250 points, 88%). */
    private fun runSweep(): List<SweepStudyRunner.Progress> {
        val results = ArrayDeque(listOf(300 to 96.4f, -3 to 0f, 250 to 88f))
        SemperEngine.solver = SemperEngine.FullFieldSolver { _, _, _, _, _, _, _, _, _, _, _, _, _, metrics ->
            val (points, convergence) = results.removeFirst()
            metrics[EngineStats.SLOT_CONVERGENCE] = convergence
            points
        }
        SweepStudyRunner.cancelRequested = false
        val ticks = mutableListOf<SweepStudyRunner.Progress>()
        val params = SweepStudyRunner.Params(
            plan = plan,
            defFramePath = "",
            roiX = 0,
            roiY = 0,
            roiW = 140,
            roiH = 140,
            maskData = ByteArray(0),
            use6x6 = false,
            debugDir = null,
            outputDir = dir.newFolder("sweep"),
        )
        SweepStudyRunner.solvePlan(byteArrayOf(1), byteArrayOf(2), params) { ticks += it }
        return ticks
    }

    @Test
    fun `a solved combination's end tick carries its points and convergence`() {
        val ends = runSweep().filter { it.outcomes[it.runIndex] == NodeOutcome.SOLVED }

        assertEquals(listOf(300, 250), ends.map { it.pointsSolved })
        assertEquals(listOf(96.4f, 88f), ends.map { it.convergencePercent })
    }

    @Test
    fun `every tick after a combination solves carries it, through a skipped one`() {
        val ticks = runSweep()
        // start 1, end 1, start 2, end 2 (skipped), start 3, end 3
        assertEquals(6, ticks.size)
        assertNull(ticks[0].lastSolved)
        val first = SweepStudyRunner.Solved(runIndex = 0, pointsSolved = 300, convergencePercent = 96.4f)
        assertEquals(listOf(first, first, first, first), ticks.subList(1, 5).map { it.lastSolved })
        assertEquals(SweepStudyRunner.Solved(2, 250, 88f), ticks[5].lastSolved)
    }

    @Test
    fun `the start ticks alone, as the screen usually gets them, say how the last one went`() {
        val res = ApplicationProvider.getApplicationContext<Application>().resources
        val starts = runSweep().filter { it.outcomes[it.runIndex] == NodeOutcome.PENDING }

        assertNull(RunOverlayText.sweepLast(res, starts[0]))
        assertEquals("Last: 96% converged · 300 points", RunOverlayText.sweepLast(res, starts[1])?.text)
        assertEquals("Last: skipped", RunOverlayText.sweepLast(res, starts[2])?.text)
    }
}
