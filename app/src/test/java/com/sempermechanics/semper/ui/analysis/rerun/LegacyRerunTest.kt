package com.sempermechanics.semper.ui.analysis.rerun

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.sweep.SweepFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Re-run of an analysis that kept no Re-run inputs (owner decision,
 * 2026-10-15): one saved before Re-run existed, or restored. Its frames come
 * from `raw_deformed/` in run order with their names and clip times, its
 * settings from its record, its reference from the user's new pick; a drawn
 * region comes back as its rectangle, and the user is told the mask is gone.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyRerunTest {

    @get:Rule
    val clean = CleanAppState()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val importDir get() = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME)
    private val reference = ByteArray(W * H * 4) { (it % 241).toByte() }

    @After
    fun tearDown() {
        importDir.deleteRecursively()
    }

    /** A saved three-frame video run with its frames, results and no `rerun/` folder. */
    private fun legacy(id: String = "old", roi: Roi = Roi(6, 8, 40, 30)): SessionRecord {
        val dir = SessionStore.dirFor(ctx, id)
        val layout = SessionLayout(dir)
        val names = listOf("c_2.png", "c_0.png", "c_1.png")
        names.forEach { layout.rawDeformed(it).apply { parentFile?.mkdirs() }.writeText("image $it") }
        (0 until names.size).forEach { layout.frameDat(it).writeText("dat") }
        val record = sessionRecord(
            id = id,
            subset = 21,
            step = 7,
            strainWindow = SweepStudy.vsgFor(5, 7),
            imgW = W,
            imgH = H,
            roiX = roi.x,
            roiY = roi.y,
            roiW = roi.w,
            roiH = roi.h,
            sessionDir = dir.absolutePath,
            defNames = names,
        ).copy(use6x6 = true, videoName = "tensile_03", frameTimesMs = listOf(0L, 400L, 800L))
        assertTrue(SessionStore.upsert(ctx, record, true))
        return record
    }

    @Test
    fun `a row without kept inputs but with its frames asks for its reference`() {
        val record = legacy()
        assertEquals(RerunSource.NEEDS_REFERENCE, RerunSource.of(record))

        File(record.sessionDir, "raw_deformed/c_0.png").delete()
        assertEquals("a frame freed or missing", RerunSource.MISSING, RerunSource.of(record))
    }

    @Test
    fun `a freed-up row, with no results here, has nothing to re-run`() {
        val record = legacy()
        File(record.sessionDir).listFiles()?.filter { it.extension == "dat" }?.forEach { it.delete() }

        assertEquals(RerunSource.MISSING, RerunSource.of(record))
    }

    @Test
    fun `a frame name that leaves raw_deformed is not followed`() {
        val record = legacy().copy(defNames = listOf("../frame_0000.dat"))

        assertFalse(LegacyRerun.framesOnPhone(record))
    }

    @Test
    fun `the picked reference and copies of the saved frames make the new analysis`() {
        val record = legacy()
        val sessionDir = File(record.sessionDir).canonicalPath

        val loaded = load(record, "again.dng")
        val vm = AnalysisViewModel().apply { applyRerun(loaded) }

        assertArrayEquals(reference, vm.refBytes)
        assertEquals("again.dng", vm.refName)
        assertEquals(ImageSize(W, H), ImageSize(vm.realRefWidth, vm.realRefHeight))
        assertEquals("run order kept", listOf("c_2.png", "c_0.png", "c_1.png"), vm.defOriginalNames)
        assertEquals(listOf(0L, 400L, 800L), vm.deformedFrames.map { it.timeMs })
        assertTrue("copied, not pointed at", vm.defFilePaths.none { File(it).canonicalPath.startsWith(sessionDir) })
        val copied = vm.defFilePaths.map { File(it).readText() }
        assertEquals(listOf("image c_2.png", "image c_0.png", "image c_1.png"), copied)
        assertTrue(vm.defFromVideo)
        assertEquals("tensile_03", vm.defVideoName)
        assertEquals(FrameOrderMode.PICKER, vm.defOrderMode)
        assertTrue(vm.hasCustomRoi)
        assertEquals(Roi(6, 8, 40, 30), vm.roi)
        assertNull("no record kept a mask", vm.roiMaskBytes)
        assertTrue("the user is told", loaded.maskDropped)
        assertEquals(RerunInputs.Params(21, 7, SweepStudy.vsgFor(5, 7), true), loaded.inputs.params)
        assertEquals(WizardStep.SETTINGS, vm.step)
        assertNull("nothing names the saved analysis", vm.working?.id)
        assertEquals("the saved frames stay", 3, File(record.sessionDir, "raw_deformed").listFiles()?.size)
    }

    @Test
    fun `a whole-image region is the full frame again, with nothing to tell`() {
        val record = legacy(roi = Roi.insetFullFrame(ImageSize(W, H), 21))

        val loaded = load(record, "r.png")

        assertFalse(loaded.inputs.hasCustomRoi)
        assertFalse(loaded.maskDropped)
        assertEquals(Roi.full(ImageSize(W, H)), AnalysisViewModel().apply { applyRerun(loaded) }.roi)
    }

    @Test
    fun `a sweep comes back in sweep mode with the plan it solved`() {
        val dir = SessionStore.dirFor(ctx, "sweep")
        val frame = SessionLayout(dir).rawDeformed("def.png").apply { parentFile?.mkdirs() }
        frame.writeText("frame")
        val ranges = SweepRanges(21, 41, 3, 7, subsetSamples = 3, strainWinSamples = 3, stepDenominator = 4)
        val plan = ranges.plan(subsetCeiling = 99)
        val solved = plan.drop(1)
        plan.indices.forEach { SessionLayout(dir).frameDat(it).writeText("dat") }
        val record = sessionRecord(
            id = "sweep",
            subset = solved.first().subset,
            step = solved.first().step,
            strainWindow = solved.first().vsg,
            imgW = W,
            imgH = H,
            sessionDir = dir.absolutePath,
            defNames = solved.map { "def.png" },
        ).copy(
            sweepSubsets = solved.map { it.subset },
            sweepSteps = solved.map { it.step },
            sweepStrainWindows = solved.map { it.vsg },
            sweepLabels = solved.map { "$it" },
            lineCutHorizontal = false,
            sweepSkippedNodes = listOf(plan.first().let { SkippedNode(it.subset, it.step, it.vsg, code = 3) }),
        )
        assertTrue(SessionStore.upsert(ctx, record, true))
        assertEquals(RerunSource.NEEDS_REFERENCE, RerunSource.of(record))

        val loaded = load(record, "r.dng")
        val vm = AnalysisViewModel().apply { applyRerun(loaded) }

        assertTrue(vm.sweepMode)
        assertEquals(WizardStep.SWEEP, vm.step)
        assertEquals(ranges, vm.sweepRanges)
        assertEquals(plan, vm.sweepRanges.plan(subsetCeiling = 99))
        assertFalse(vm.lineCutHorizontal)
        assertEquals(listOf("def.png"), vm.defOriginalNames)
        assertEquals(SweepFrame.At(0), vm.sweepFrame)
        assertEquals(SweepStudy.overlapForDenominator(4), vm.subsetOverlap, 1e-9)
    }

    private fun load(record: SessionRecord, referenceName: String): LoadedRerun =
        checkNotNull(runBlocking { RerunLoader.loadWithReference(record, reference, referenceName, ctx.cacheDir) })

    private companion object {
        const val W = 64
        const val H = 48
    }
}
