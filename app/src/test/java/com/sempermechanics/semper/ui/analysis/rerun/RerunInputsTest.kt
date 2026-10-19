package com.sempermechanics.semper.ui.analysis.rerun

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderHelper
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.run.RunInputs
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.snapshotRunInputs
import com.sempermechanics.semper.ui.analysis.sweep.SweepFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import com.sempermechanics.semper.util.Digests
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Home's Re-run (owner decision, 2026-10-15): a saved run keeps its inputs
 * ([keepBatchRerun], [keepSweepRerun]), and a new analysis starts from a copy
 * of them ([RerunLoader], [applyRerun]): the same reference, frames in the
 * same order with their names and clip times, ROI, mask and settings, and for
 * a sweep its plan, in sweep mode. The copy lives in the wizard's own import
 * cache, so nothing the new analysis does (sorting included, TD-211) reaches
 * the saved one.
 */
@RunWith(RobolectricTestRunner::class)
class RerunInputsTest {

    @get:Rule
    val clean = CleanAppState()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val importDir get() = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME)
    private val reference = ByteArray(SIDE * SIDE * 4) { (it % 251).toByte() }
    private val mask = ByteArray(SIDE * SIDE) { if (it % 3 == 0) 1 else 0 }
    private val size = ImageSize(SIDE, SIDE)
    private val drawn = Roi(4, 6, 40, 30)

    @After
    fun tearDown() {
        importDir.deleteRecursively()
    }

    /** The wizard as a video run of three frames left it, frozen as a run freezes it. */
    private fun wizard(frames: List<File>, sweep: Boolean = false) = AnalysisViewModel().apply {
        applyNewReference(reference, "ref.dng", size)
        hasCustomRoi = true
        roi = drawn
        roiMaskBytes = mask
        deformedFrames = frames.mapIndexed { i, f ->
            DeformedFrame(f.absolutePath, "clip_$i.png", date = 100L + i, size = size, timeMs = 500L * i)
        }
        defFromVideo = true
        defVideoName = "tensile_03"
        defOrderMode = FrameOrderMode.PICKER
        defOrderDirection = FrameOrderDirection.DESCENDING
        if (sweep) {
            sweepMode = true
            sweepRanges = SweepRanges(21, 41, 5, 9, subsetSamples = 3, strainWinSamples = 2, stepDenominator = 4)
            subsetOverlap = SweepStudy.overlapForDenominator(4)
            lineCutHorizontal = false
        }
    }

    private val single = RunSpec.of(DicParams(31, 6, SweepStudy.vsgFor(9, 6)), drawn, mask, use6x6 = true, null)

    /**
     * A saved batch run in a new session that reached frames 0 and 1, which it
     * moved into `raw_deformed/`, and stopped before frame 2, still in the
     * import: what [keepBatchRerun] is handed.
     */
    private fun savedBatch(): Triple<File, RunInputs, MutableList<String>> {
        importDir.mkdirs()
        val imported = (0 until 3).map { File(importDir, "000${it}_clip_$it.png").apply { writeText("image $it") } }
        val inputs = wizard(imported).snapshotRunInputs()
        val dir = SessionStore.dirFor(ctx, "saved")
        val raw = SessionLayout(dir).rawDeformedDir.apply { mkdirs() }
        val resolved = imported.map { it.absolutePath }.toMutableList()
        (0..1).forEach { i ->
            val moved = File(raw, "clip_$i.png")
            imported[i].renameTo(moved)
            resolved[i] = moved.absolutePath
        }
        File(dir, "frame_0000.dat").writeText("dat")
        return Triple(dir, inputs, resolved)
    }

    private fun hashes(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to Digests.sha256Hex(it) }

    @Test
    fun `a saved run keeps the frames it stopped before, and the wizard follows them`() {
        val (dir, inputs, resolved) = savedBatch()

        keepBatchRerun(dir, inputs, single, resolved)

        val kept = checkNotNull(RerunInputs.readComplete(dir))
        assertEquals(
            listOf("raw_deformed/clip_0.png", "raw_deformed/clip_1.png", "rerun/frames/clip_2.png"),
            kept.frames.map { it.path },
        )
        assertEquals(File(dir, "rerun/frames/clip_2.png").absolutePath, resolved[2])
        assertFalse("moved out of the import", File(importDir, "0002_clip_2.png").exists())
        assertArrayEquals(reference, SessionLayout(dir).rerunReference.readBytes())
        assertArrayEquals(mask, SessionLayout(dir).rerunMask.readBytes())
    }

    @Test
    fun `Re-run copies the photos and settings into the wizard, never pointing at the saved analysis`() = runBlocking {
        val (dir, inputs, resolved) = savedBatch()
        keepBatchRerun(dir, inputs, single, resolved)
        val before = hashes(dir)

        val loaded = checkNotNull(RerunLoader.load(dir, ctx.cacheDir))
        val vm = AnalysisViewModel().apply { applyRerun(loaded) }

        val root = dir.canonicalPath
        assertTrue("every path is the wizard's own", vm.defFilePaths.none { File(it).canonicalPath.startsWith(root) })
        assertTrue(vm.defFilePaths.all { File(it).parentFile == importDir.absoluteFile })
        assertEquals(listOf("image 0", "image 1", "image 2"), vm.defFilePaths.map { File(it).readText() })
        assertEquals(listOf("clip_0.png", "clip_1.png", "clip_2.png"), vm.defOriginalNames)
        assertEquals(listOf(0L, 500L, 1000L), vm.defFrameTimesMs)
        assertEquals(listOf(100L, 101L, 102L), vm.defFrameDates)
        assertTrue(vm.defFromVideo)
        assertEquals("tensile_03", vm.defVideoName)
        assertEquals(FrameOrderMode.PICKER, vm.defOrderMode)
        assertEquals(FrameOrderDirection.DESCENDING, vm.defOrderDirection)
        assertArrayEquals(reference, vm.refBytes)
        assertEquals("ref.dng", vm.refName)
        assertEquals(size, vm.refSize)
        assertTrue(vm.hasCustomRoi)
        assertEquals(drawn, vm.roi)
        assertArrayEquals(mask, vm.roiMaskBytes)
        assertEquals(RerunInputs.Params(31, 6, SweepStudy.vsgFor(9, 6), use6x6 = true), loaded.inputs.params)
        assertFalse(vm.sweepMode)
        assertEquals("lands on the parameters page", WizardStep.SETTINGS, vm.step)
        assertTrue("the recommendation must not move the saved subset", vm.subsetUserModified)
        assertNull("nothing names the saved analysis: the next run is a new one", vm.working?.id)
        assertEquals("the saved analysis is untouched", before, hashes(dir))
    }

    @Test
    fun `sorting the frames of a re-run renames only the wizard's copies`() = runBlocking {
        val (dir, inputs, resolved) = savedBatch()
        keepBatchRerun(dir, inputs, single, resolved)
        val before = hashes(dir)
        val vm = AnalysisViewModel().apply { applyRerun(checkNotNull(RerunLoader.load(dir, ctx.cacheDir))) }

        val sorted = FrameOrderHelper.reorder(vm.deformedFrames, FrameOrderMode.NAME, FrameOrderDirection.DESCENDING)
        vm.deformedFrames = FrameOrderHelper.reprefixTempFiles(sorted)

        assertEquals(listOf("image 2", "image 1", "image 0"), vm.defFilePaths.map { File(it).readText() })
        assertEquals("the saved analysis's files", before, hashes(dir))
    }

    @Test
    fun `sorting frames that sit in a saved analysis leaves them as they are (TD-211)`() {
        val raw = SessionLayout(SessionStore.dirFor(ctx, "saved")).rawDeformedDir.apply { mkdirs() }
        val frames = listOf("b.png", "a.png").map { name ->
            DeformedFrame(File(raw, name).apply { writeText(name) }.absolutePath, name)
        }

        val out = FrameOrderHelper.reprefixTempFiles(FrameOrderHelper.reorder(frames, FrameOrderMode.NAME))

        assertEquals("the new order holds", listOf("a.png", "b.png"), out.map { it.name })
        assertEquals("but no file is renamed", setOf("a.png", "b.png"), raw.list()?.toSet())
        assertEquals(listOf(File(raw, "a.png").absolutePath, File(raw, "b.png").absolutePath), out.map { it.path })
    }

    @Test
    fun `a sweep's Re-run lands in sweep mode with the same plan`() = runBlocking {
        importDir.mkdirs()
        val imported = (0 until 3).map { File(importDir, "000${it}_clip_$it.png").apply { writeText("image $it") } }
        val wizard = wizard(imported, sweep = true)
        val ranges = wizard.sweepRanges
        val plan = ranges.plan(subsetCeiling = 41)
        val spec = RunSpec.sweep(
            RunSpec.Sweep(plan, plan.map { "$it" }, lineCutHorizontal = false, frameIndex = 1),
            roi = drawn,
            mask = mask,
            use6x6 = false,
            debugDir = null,
        )
        val inputs = wizard.snapshotRunInputs()
        val dir = SessionStore.dirFor(ctx, "sweep")
        // A sweep keeps the one frame it solved against.
        imported[1].copyTo(SessionLayout(dir).rawDeformed("clip_1.png"))

        keepSweepRerun(dir, inputs, spec, "clip_1.png")
        val vm = AnalysisViewModel().apply { applyRerun(checkNotNull(RerunLoader.load(dir, ctx.cacheDir))) }

        assertTrue(vm.sweepMode)
        assertEquals(WizardStep.SWEEP, vm.step)
        assertEquals(ranges, vm.sweepRanges)
        assertEquals(plan, vm.sweepRanges.plan(subsetCeiling = 41))
        assertEquals(wizard.subsetOverlap, vm.subsetOverlap, 0.0)
        assertFalse(vm.lineCutHorizontal)
        assertEquals("its one frame, the one it solved against", listOf("clip_1.png"), vm.defOriginalNames)
        assertEquals(SweepFrame.At(0), vm.sweepFrame)
        assertEquals(listOf("image 1"), vm.defFilePaths.map { File(it).readText() })
        assertArrayEquals(mask, vm.roiMaskBytes)
    }

    @Test
    fun `an analysis without its photos on this phone has nothing to re-run`() {
        val (dir, inputs, resolved) = savedBatch()
        assertNull("saved before Re-run, or restored from the cloud", RerunInputs.readComplete(dir))

        keepBatchRerun(dir, inputs, single, resolved)
        assertNotNull(RerunInputs.readComplete(dir))

        File(dir, "raw_deformed/clip_1.png").delete()
        assertNull("one frame gone is a different analysis", RerunInputs.readComplete(dir))
    }

    @Test
    fun `a run frozen without its setup keeps nothing for a Re-run`() {
        val (dir, inputs, resolved) = savedBatch()
        val bare = RunInputs(inputs.reference, inputs.frames)

        keepBatchRerun(dir, bare, single, resolved)

        assertFalse(SessionLayout(dir).rerunInputs.exists())
    }

    private companion object {
        const val SIDE = 64
    }
}
