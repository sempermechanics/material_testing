package com.sempermechanics.semper.analysis

import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.snapshotRunInputs
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TD-171: a run's images are frozen when it is launched, so what the wizard
 * does to its fields afterwards (a new pick, a reorder, a cleared reference)
 * never reaches the run solving on the native thread.
 */
@RunWith(RobolectricTestRunner::class)
class RunInputsTest {

    private val vm = AnalysisViewModel()
    private val ref = byteArrayOf(1, 2, 3)

    private fun clip(vararg names: String) =
        names.mapIndexed { i, name -> DeformedFrame("/frames/$name", "picked_$name", timeMs = i * 100L) }

    @Test
    fun `the snapshot keeps the reference and frames the wizard held at launch`() {
        vm.edit {
            it.copy(
                refBytes = ref,
                refSize = ImageSize(640, 480),
                refName = "ref.png",
                deformedFrames = clip("a.png", "b.png"),
                defVideoName = "tensile_03",
            )
        }

        val inputs = vm.snapshotRunInputs()

        assertSame(ref, inputs.reference.bytes)
        assertEquals(ImageSize(640, 480), inputs.reference.size)
        assertEquals("ref.png", inputs.reference.name)
        assertEquals(listOf("/frames/a.png", "/frames/b.png"), inputs.frames.paths)
        assertEquals(listOf("picked_a.png", "picked_b.png"), inputs.frames.originalNames)
        assertEquals(listOf(0L, 100L), inputs.frames.timesMs)
        assertEquals("tensile_03", inputs.frames.videoName)
    }

    @Test
    fun `editing the wizard after launch does not change the run's inputs`() {
        vm.edit {
            it.copy(
                refBytes = ref,
                refSize = ImageSize(640, 480),
                refName = "ref.png",
                deformedFrames = clip("a.png", "b.png", "c.png"),
                defVideoName = "tensile_03",
            )
        }
        val inputs = vm.snapshotRunInputs()

        // What the wizard may do on Main while the run solves.
        vm.edit {
            it.copy(
                refBytes = null,
                refSize = ImageSize(10, 10),
                refName = "other.png",
                deformedFrames = listOf(DeformedFrame("/frames/late.png", "late.png")),
                defVideoName = null,
            )
        }

        assertSame(ref, inputs.reference.bytes)
        assertEquals(ImageSize(640, 480), inputs.reference.size)
        assertEquals("ref.png", inputs.reference.name)
        assertEquals(listOf("/frames/a.png", "/frames/b.png", "/frames/c.png"), inputs.frames.paths)
        assertEquals(listOf("picked_a.png", "picked_b.png", "picked_c.png"), inputs.frames.originalNames)
        assertEquals(listOf(0L, 100L, 200L), inputs.frames.timesMs)
        assertEquals("tensile_03", inputs.frames.videoName)
    }

    @Test
    fun `a run's frames follow neither a repoint nor a reorder after launch`() {
        vm.edit { it.copy(deformedFrames = clip("a.png", "b.png")) }
        val inputs = vm.snapshotRunInputs()

        vm.edit { cur ->
            cur.copy(deformedFrames = cur.deformedFrames.reversed().map { it.copy(path = "/session/raw/${it.name}") })
        }

        assertEquals(listOf("/frames/a.png", "/frames/b.png"), inputs.frames.paths)
        assertEquals(listOf("picked_a.png", "picked_b.png"), inputs.frames.originalNames)
    }

    @Test
    fun `picked images carry no clip`() {
        vm.edit { it.copy(deformedFrames = listOf(DeformedFrame("/frames/a.png", "a.png"))) }
        val inputs = vm.snapshotRunInputs()
        assertNull(inputs.frames.timesMs)
        assertNull(inputs.frames.videoName)
        assertNull(inputs.reference.bytes)
    }
}
