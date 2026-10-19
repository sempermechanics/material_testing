package com.sempermechanics.semper.analysis

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.roi.RoiResolveHelper
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.BatchAnalysisOutcome
import com.sempermechanics.semper.ui.analysis.wizard.RunKind
import com.sempermechanics.semper.ui.analysis.wizard.RunSession
import com.sempermechanics.semper.ui.analysis.wizard.WizardInputs
import com.sempermechanics.semper.ui.analysis.wizard.WizardState
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import com.sempermechanics.semper.ui.analysis.wizard.WorkingSession
import com.sempermechanics.semper.ui.analysis.wizard.applyRunOutcome
import com.sempermechanics.semper.ui.analysis.wizard.openRunSession
import com.sempermechanics.semper.ui.analysis.wizard.repointDeformedPathsOnMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.concurrent.thread

/**
 * Wizard state contracts in [AnalysisViewModel].
 *
 * This class is the analysis wizard's brain and the highest-churn file in the
 * app, but it sat in the Kover-excluded `ui` package with no direct coverage.
 * These pin the cheap, load-bearing seams — the compute gate, the working
 * session, which never writes over a saved analysis, and the reset that new
 * inputs depend on — without touching the native solve.
 */
@RunWith(RobolectricTestRunner::class)
class AnalysisViewModelTest {

    @get:Rule
    val clean = CleanAppState()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private lateinit var vm: AnalysisViewModel

    private fun frames(vararg paths: String) = paths.map { DeformedFrame(it, it.substringAfterLast('/')) }

    @Before
    fun setUp() {
        vm = AnalysisViewModel()
    }

    // ------------------------------------------------------------ compute gate

    @Test
    fun `not ready to compute with neither reference nor deformed frames`() {
        assertFalse(vm.isReadyToCompute())
    }

    @Test
    fun `not ready to compute with a reference but no deformed frames`() {
        vm.refBytes = ByteArray(8)
        assertFalse(vm.isReadyToCompute())
    }

    @Test
    fun `not ready to compute with deformed frames but no reference`() {
        vm.deformedFrames = frames("/tmp/def0.png")
        assertFalse(vm.isReadyToCompute())
    }

    @Test
    fun `ready to compute once a reference and at least one deformed frame exist`() {
        vm.refBytes = ByteArray(8)
        vm.deformedFrames = frames("/tmp/def0.png")
        assertTrue(vm.isReadyToCompute())
    }

    @Test
    fun `defCount tracks the deformed frame list`() {
        assertEquals(0, vm.defCount)
        vm.deformedFrames = frames("/tmp/a.png", "/tmp/b.png", "/tmp/c.png")
        assertEquals(3, vm.defCount)
    }

    // ------------------------------------------------- working session identity

    /** What a run in [session] that saved its row, or not, hands the view model in its outcome. */
    private fun ended(session: RunSession, saved: Boolean = false) = BatchAnalysisOutcome(
        engineErrorCode = 0,
        firstFrameValidPoints = 0,
        totalFrames = 0,
        executionTimeMs = 0,
        batchDirPath = session.dir.path,
        record = if (saved) sessionRecord(id = session.id, sessionDir = session.dir.path) else null,
        session = session.working,
    )

    @Test
    fun `a fresh view model's run opens a new session, which the view model takes from its outcome`() {
        val session = openRunSession(ctx, vm.working, RunKind.BATCH)

        assertTrue(session.fresh)
        assertNull("the run itself writes none of the wizard's fields", vm.working?.id)
        vm.applyRunOutcome(ended(session))
        assertEquals(session.id, vm.working?.id)
    }

    @Test
    fun `a run after one that saved nothing writes that folder again`() {
        val first = openRunSession(ctx, vm.working, RunKind.BATCH)
        vm.applyRunOutcome(ended(first))

        val next = openRunSession(ctx, vm.working, RunKind.BATCH)

        assertEquals("no Home row names it: it holds no one's analysis", first.id, next.id)
        assertFalse(next.fresh)
    }

    @Test
    fun `a saved analysis is never written over by the next run`() {
        val first = openRunSession(ctx, vm.working, RunKind.BATCH)
        vm.applyRunOutcome(ended(first))
        assertTrue(SessionStore.upsert(ctx, sessionRecord(id = first.id, sessionDir = first.dir.path), true))

        val next = openRunSession(ctx, vm.working, RunKind.BATCH)

        assertNotEquals(first.id, next.id)
        assertTrue(next.fresh)
    }

    @Test
    fun `a saved run closes its session, so the next run is a new one`() {
        val first = openRunSession(ctx, vm.working, RunKind.BATCH)
        vm.applyRunOutcome(ended(first))

        vm.applyRunOutcome(ended(first, saved = true))

        assertNull(vm.working)
    }

    @Test
    fun `a run that kept nothing or never started leaves the working session as it was`() {
        val first = openRunSession(ctx, vm.working, RunKind.BATCH)
        vm.applyRunOutcome(ended(first))

        vm.applyRunOutcome(ended(first).copy(engineErrorCode = RunStop.Cancelled.wireCode, session = null))

        assertEquals(first.id, vm.working?.id)
    }

    @Test
    fun `a run of the other kind opens its own session`() {
        val single = openRunSession(ctx, vm.working, RunKind.BATCH)
        vm.applyRunOutcome(ended(single))

        val sweep = openRunSession(ctx, vm.working, RunKind.SWEEP)
        vm.applyRunOutcome(ended(sweep))

        assertNotEquals("a sweep must not write a single run's folder", single.id, sweep.id)
        assertEquals(RunKind.SWEEP, vm.working?.kind)
    }

    // -------------------------------------------------------------- reset logic

    @Test
    fun `clearPreviousResults starts the next run in a new session`() {
        vm.working = WorkingSession("abc123", RunKind.BATCH)

        vm.clearPreviousResults()

        assertNull(vm.working?.id)
    }

    // ------------------------------------------------------ sweep and ROI limits

    @Test
    fun `the settings' reset puts the sweep's step depth and overlap back`() {
        vm.stepDenominator = 7
        vm.subsetOverlap = 0.1

        vm.resetSweepDepth()

        assertEquals(SweepStudy.DEFAULT_STEP_DENOM, vm.stepDenominator)
        assertEquals(SweepStudy.overlapForDenominator(SweepStudy.DEFAULT_STEP_DENOM), vm.subsetOverlap, 0.0)
    }

    @Test
    fun `the largest subset is the drawn region's, or the whole image's`() {
        vm.applyNewReference(ByteArray(8), "ref.png", ImageSize(640, 480))
        val whole = RoiResolveHelper.maxSubsetForRoi(false, 0, 0, 640, 480, 640, 480)
        assertEquals(whole, vm.maxSubsetForRoi())

        vm.hasCustomRoi = true
        vm.roi = Roi(100, 100, 60, 40)
        assertEquals(RoiResolveHelper.maxSubsetForRoi(true, 100, 100, 60, 40, 640, 480), vm.maxSubsetForRoi())
    }

    // ------------------------------------------------------------ new reference

    /** A wizard that has run once on a 640 x 480 reference with a drawn ROI and mask. */
    private fun ranWithCustomRoi() = vm.apply {
        applyNewReference(ByteArray(8), "first.png", ImageSize(640, 480))
        hasCustomRoi = true
        roiX = 400
        roiY = 300
        roiW = 200
        roiH = 150
        roiMaskBytes = ByteArray(640 * 480)
        working = WorkingSession("abc123", RunKind.BATCH)
    }

    @Test
    fun `a new reference of another size drops the drawn ROI and mask`() {
        ranWithCustomRoi()

        vm.applyNewReference(ByteArray(8), "second.png", ImageSize(320, 240))

        assertFalse(vm.hasCustomRoi)
        assertEquals(listOf(0, 0, 320, 240), listOf(vm.roiX, vm.roiY, vm.roiW, vm.roiH))
        assertNull("a 640 x 480 mask on a 320 x 240 image", vm.roiMaskBytes)
        assertEquals(320, vm.realRefWidth)
        assertEquals("second.png", vm.refName)
    }

    @Test
    fun `a new reference of the same size keeps the drawn ROI and mask`() {
        ranWithCustomRoi()
        val mask = vm.roiMaskBytes

        vm.applyNewReference(ByteArray(8), "second.png", ImageSize(640, 480))

        assertTrue(vm.hasCustomRoi)
        assertEquals(listOf(400, 300, 200, 150), listOf(vm.roiX, vm.roiY, vm.roiW, vm.roiH))
        assertSame(mask, vm.roiMaskBytes)
    }

    @Test
    fun `a new reference starts a new session`() {
        ranWithCustomRoi()

        vm.applyNewReference(ByteArray(8), "second.png", ImageSize(640, 480))

        assertNull("a new reference wrote into the previous one's folder", vm.working?.id)
    }

    // ------------------------------------------------------- run on the native thread

    @Test
    fun `a run moving the frames repoints them on the main thread, not its own`() {
        vm.deformedFrames = frames("/cache/0000_a.png", "/cache/0001_b.png").map { it.copy(size = ImageSize(4, 3)) }

        thread { vm.repointDeformedPathsOnMain(listOf("/session/a.png", "/session/b.png")) }.join()
        assertEquals(
            "the native thread wrote the wizard's paths itself",
            listOf("/cache/0000_a.png", "/cache/0001_b.png"),
            vm.defFilePaths,
        )

        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("/session/a.png", "/session/b.png"), vm.defFilePaths)
        assertEquals(mapOf("/session/a.png" to (4 to 3), "/session/b.png" to (4 to 3)), vm.defFrameSizes)
    }

    // ------------------------------------------------------------ saved state

    @Test
    fun `the Activity's factory hands it the saved state a process death restores`() {
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        ViewModelProvider(first.get())[AnalysisViewModel::class.java].step = WizardStep.SETTINGS
        val state = Bundle()
        first.saveInstanceState(state).pause().stop().destroy()

        val second = Robolectric.buildActivity(ComponentActivity::class.java).setup(state)
        assertEquals(WizardStep.SETTINGS, ViewModelProvider(second.get())[AnalysisViewModel::class.java].step)
    }

    @Test
    fun `the inputs are one value that every property reads and writes`() {
        val before = vm.inputs.value

        vm.refName = "ref.png"
        vm.roi = Roi(1, 2, 30, 40)
        vm.sweepMode = true
        vm.deformedFrames = frames("/a/0.png", "/a/1.png")

        val after = vm.inputs.value
        assertEquals(AnalysisViewModel.NO_REFERENCE_NAME, before.refName)
        assertEquals("ref.png", after.refName)
        assertEquals(Roi(1, 2, 30, 40), after.roi)
        assertTrue(after.sweepMode)
        assertEquals(frames("/a/0.png", "/a/1.png"), after.deformedFrames)
        assertEquals(WizardInputs(), before.copy(refBytes = null, roiMaskBytes = null))
    }

    @Test
    fun `an edit is seen through the properties, the frame lists included`() {
        vm.deformedFrames = frames("/a/0.png")
        assertEquals(listOf("/a/0.png"), vm.defFilePaths)

        vm.edit { it.copy(deformedFrames = frames("/b/0.png", "/b/1.png"), step = WizardStep.SETTINGS, refName = "r") }

        assertEquals(listOf("/b/0.png", "/b/1.png"), vm.defFilePaths)
        assertEquals(listOf("0.png", "1.png"), vm.defOriginalNames)
        assertEquals(WizardStep.SETTINGS, vm.step)
        assertEquals(2, vm.wizardStep)
        assertEquals("r", vm.refName)
    }

    @Test
    fun `the saved state is the inputs' value, the same Bundle either way`() {
        vm.refName = "ref.png"
        vm.hasCustomRoi = true
        vm.roi = Roi(1, 2, 30, 40)
        vm.working = WorkingSession("abc", RunKind.SWEEP)

        val fromViewModel = WizardState.save(vm, "")
        val fromInputs = WizardState.save(vm.inputs.value, "")
        val restored = WizardState.restoreScalars(WizardInputs(), fromInputs)

        assertEquals(fromViewModel.keySet(), fromInputs.keySet())
        assertEquals("ref.png", restored.refName)
        assertEquals(Roi(1, 2, 30, 40), restored.roi)
        assertTrue(restored.hasCustomRoi)
        assertEquals(WorkingSession("abc", RunKind.SWEEP), restored.working)
    }
}
