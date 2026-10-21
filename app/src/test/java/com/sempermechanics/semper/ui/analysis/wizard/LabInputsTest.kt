package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.CaptureStamp
import com.sempermechanics.semper.data.mechanical.LoadCsvParse
import com.sempermechanics.semper.data.mechanical.LoadMapWarning
import com.sempermechanics.semper.data.mechanical.LoadMapping
import com.sempermechanics.semper.data.mechanical.MachineLoadCsv
import com.sempermechanics.semper.data.mechanical.MachineLoadMapper
import com.sempermechanics.semper.data.mechanical.MechanicalTestInputs
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.load.PhotoCaptureTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lab test's wizard inputs ([WizardInputs.lab], LabInputs.kt): the load
 * log or typed loads matched to the frames, the gate they put on Next, and
 * what a run records about the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabInputsTest {

    private lateinit var vm: AnalysisViewModel

    @Before
    fun setUp() {
        vm = AnalysisViewModel()
    }

    /** Deformed frames at [paths], each named as its file. */
    private fun framesAt(vararg paths: String): List<DeformedFrame> =
        paths.map { DeformedFrame(path = it, name = it.substringAfterLast('/')) }

    // ------------------------------------------------------------ bending load point

    private fun bendingReadyButTaps() {
        vm.editLab { it.copy(testType = TestType.BENDING) }
        vm.edit { it.copy(deformedFrames = framesAt("/tmp/def0.png", "/tmp/def1.png")) }
        val csv = (MachineLoadCsv.parse("Load (N)\n10\n20\n") as LoadCsvParse.Ok).csv
        vm.editLab { it.copy(parsedLoadCsv = csv) }
        vm.refreshMachineLoads()
        vm.editLab { it.copy(geometry = SpecimenGeometry(spanMm = 935f, widthMm = 150f, thicknessMm = 6.38f)) }
    }

    @Test
    fun `bending waits for the load point taps once loads and dimensions are in`() {
        bendingReadyButTaps()
        assertTrue(vm.loadPointMissing())
        assertFalse(vm.mechanicalInputsReady())

        vm.editLab { it.copy(geometry = it.geometry.copy(loadPoint = BeamEdgeTaps(10f, 20f, 10f, 148f))) }

        assertFalse(vm.loadPointMissing())
        assertTrue(vm.mechanicalInputsReady())
    }

    @Test
    fun `a new reference clears the taps but keeps the dimensions`() {
        bendingReadyButTaps()
        vm.editLab { it.copy(geometry = it.geometry.copy(loadPoint = BeamEdgeTaps(10f, 20f, 10f, 148f))) }

        vm.onReferenceReplaced()

        assertEquals(BeamEdgeTaps.NONE, vm.lab.geometry.loadPoint)
        assertEquals(935f, vm.lab.geometry.spanMm)
        assertFalse(vm.mechanicalInputsReady())
    }

    @Test
    fun `tensile never asks for a load point`() {
        assertFalse(vm.loadPointMissing())
    }

    // ------------------------------------------------------ photo load matching

    /** Capture stamps [afterT0Ms] after [PHOTO_T0], all of one precision; null is a photo without one. */
    private fun stamps(vararg afterT0Ms: Long?, subSeconds: Boolean?): List<CaptureStamp?> =
        afterT0Ms.map { ms -> ms?.let { CaptureStamp(PHOTO_T0 + it, subSeconds) } }

    /**
     * Three photos 1 s, 2 s and 3 s after the reference, from a camera that
     * writes no sub-seconds; a log at 10 Hz from the reference.
     */
    private fun timedPhotoBatch() {
        vm.edit { it.copy(deformedFrames = framesAt("/tmp/def0.jpg", "/tmp/def1.jpg", "/tmp/def2.jpg")) }
        vm.editLab { it.copy(refCapture = CaptureStamp(PHOTO_T0, subSeconds = false)) }
        vm.edit { it.withDefCaptureStamps(stamps(1_000L, 2_000L, 3_000L, subSeconds = false)) }
        val log = "Time (s),Load (N)\n" + (0..40).joinToString("\n") { "${it / 10.0},${it * 10}" }
        vm.editLab { it.copy(parsedLoadCsv = (MachineLoadCsv.parse(log) as LoadCsvParse.Ok).csv) }
    }

    @Test
    fun `photos with capture times take the load logged when each was taken`() {
        timedPhotoBatch()
        vm.refreshMachineLoads()

        val table = vm.lab.machineLoads!!
        assertEquals(LoadMapping.TIME_NEAREST, table.mapping)
        assertEquals(listOf(100f, 200f, 300f), table.loadsN)
        // No time here carries sub-seconds: matched within 1 s, and the chip says so.
        assertTrue(LoadMapWarning.TIME_COARSE in table.warnings)
    }

    @Test
    fun `photos with sub-second capture times are matched within 100 ms`() {
        timedPhotoBatch()
        vm.editLab { it.copy(refCapture = CaptureStamp(PHOTO_T0, subSeconds = true)) }
        vm.edit { it.withDefCaptureStamps(stamps(1_020L, 2_000L, 3_000L, subSeconds = true)) }
        vm.refreshMachineLoads()

        val table = vm.lab.machineLoads!!
        assertEquals(listOf(100f, 200f, 300f), table.loadsN)
        assertFalse(LoadMapWarning.TIME_COARSE in table.warnings)
    }

    @Test
    fun `a whole-second reference makes sub-second photos coarse`() {
        // The reference from another camera, or an edited copy, lost its
        // sub-seconds: every photo's time after it is good only to ±1 s.
        timedPhotoBatch()
        vm.edit { it.withDefCaptureStamps(stamps(1_020L, 2_250L, 3_130L, subSeconds = true)) }
        vm.refreshMachineLoads()

        val table = vm.lab.machineLoads!!
        assertTrue(LoadMapWarning.TIME_COARSE in table.warnings)
        assertEquals(MachineLoadMapper.COARSE_MATCH_TOLERANCE_MS, table.matchToleranceMs, 0.0)
    }

    @Test
    fun `times from a draft saved before the precision flag are judged by their values`() {
        timedPhotoBatch()
        vm.editLab { it.copy(refCapture = CaptureStamp(PHOTO_T0, subSeconds = null)) }
        vm.edit { it.withDefCaptureStamps(stamps(1_000L, 2_000L, 3_000L, subSeconds = null)) }
        vm.refreshMachineLoads()
        assertTrue(LoadMapWarning.TIME_COARSE in vm.lab.machineLoads!!.warnings)

        vm.edit { it.withDefCaptureStamps(stamps(1_020L, 2_000L, 3_000L, subSeconds = null)) }
        vm.refreshMachineLoads()
        assertFalse(LoadMapWarning.TIME_COARSE in vm.lab.machineLoads!!.warnings)
    }

    @Test
    fun `the log-start offset shifts photo times as it does video times`() {
        timedPhotoBatch()
        vm.editLab { it.copy(loadLogStartS = 0.5f) }
        vm.refreshMachineLoads()

        // Row 0 is logged 0.5 s after the reference, so 1 s in is row 5.
        assertEquals(listOf(50f, 150f, 250f), vm.lab.machineLoads!!.loadsN)
    }

    @Test
    fun `a photo without a capture time sends the batch back to row order`() {
        timedPhotoBatch()
        vm.edit { it.withDefCaptureStamps(stamps(1_000L, null, 3_000L, subSeconds = false)) }
        vm.refreshMachineLoads()

        assertEquals(LoadMapping.RESAMPLED, vm.lab.machineLoads!!.mapping)
    }

    @Test
    fun `a reference without a capture time sends the batch back to row order`() {
        timedPhotoBatch()
        vm.editLab { it.copy(refCapture = null) }
        vm.refreshMachineLoads()

        assertEquals(LoadMapping.RESAMPLED, vm.lab.machineLoads!!.mapping)
    }

    @Test
    fun `video frame times win over any photo times`() {
        timedPhotoBatch()
        vm.edit { it.withDefTimesAfterRefMs(listOf(500L, 1_500L, 2_500L)) }
        vm.refreshMachineLoads()

        assertEquals(listOf(50f, 150f, 250f), vm.lab.machineLoads!!.loadsN)
    }

    @Test
    fun `a video frame is timed from the reference's clip time, not the clip's start`() {
        timedPhotoBatch()
        // Sampled from 4 s into the clip: the reference at 4.0 s, frames 4.5 s on.
        vm.editLab { it.copy(refVideoTimeMs = 4_000L) }
        vm.edit { inputs ->
            val frames = inputs.deformedFrames.mapIndexed { i, f -> f.copy(timeMs = 4_500L + i * 1_000L) }
            inputs.copy(deformedFrames = frames)
        }
        vm.refreshMachineLoads()

        assertEquals(listOf(500L, 1_500L, 2_500L), vm.defTimesAfterRefMs)
        assertEquals(listOf(50f, 150f, 250f), vm.lab.machineLoads!!.loadsN)
    }

    @Test
    fun `clearing the lab inputs forgets the video's clip time`() {
        vm.editLab { it.copy(refVideoTimeMs = 4_000L) }
        vm.clearLabInputs()

        assertNull(vm.lab.refVideoTimeMs)
    }

    // ------------------------------------------------------- bending typed loads

    /** Bending with three photos, dimensions and taps: everything but the loads. */
    private fun bendingWithoutLoads() {
        vm.editLab { it.copy(testType = TestType.BENDING) }
        vm.edit { it.copy(deformedFrames = framesAt("/tmp/def0.jpg", "/tmp/def1.jpg", "/tmp/def2.jpg")) }
        val geometry = SpecimenGeometry(
            spanMm = 935f,
            widthMm = 150f,
            thicknessMm = 6.38f,
            loadPoint = BeamEdgeTaps(10f, 20f, 10f, 148f),
        )
        vm.editLab { it.copy(geometry = geometry) }
        vm.refreshMachineLoads()
    }

    @Test
    fun `typed hanger masses become loads in newtons`() {
        bendingWithoutLoads()
        assertNull(vm.lab.machineLoads)
        assertFalse(vm.mechanicalInputsReady())

        vm.setTypedLoads(listOf(0.5f, 1f, 0f))

        val table = vm.lab.machineLoads!!
        assertEquals(LoadMapping.TYPED_KG, table.mapping)
        assertEquals(TypedLoads.G, table.loadsN[1], 1e-5f)
        assertEquals(0f, table.loadsN[2], 0f)
        assertEquals(0, vm.typedLoadsMissing())
        assertTrue(vm.mechanicalInputsReady())
    }

    @Test
    fun `an empty box holds the wizard until the student types a number, 0 for no weight`() {
        bendingWithoutLoads()
        vm.setTypedLoads(listOf(0.5f, null, 1.5f))

        assertEquals(1, vm.typedLoadsMissing())
        assertFalse(vm.mechanicalInputsReady())

        vm.setTypedLoads(listOf(0.5f, 0f, 1.5f))

        assertEquals(0, vm.typedLoadsMissing())
        assertTrue(vm.mechanicalInputsReady())
    }

    @Test
    fun `a CSV never counts as missing typed loads`() {
        bendingWithoutLoads()
        // Two rows for three photos: resampled, every frame gets a load, none is "missing".
        val csv = (MachineLoadCsv.parse("Load (N)\n10\n20\n") as LoadCsvParse.Ok).csv
        vm.setLoadLog(csv, "short.csv", "")
        vm.refreshMachineLoads()

        assertEquals(0, vm.typedLoadsMissing())
    }

    @Test
    fun `typed loads are recorded with the run`() {
        bendingWithoutLoads()
        vm.setTypedLoads(listOf(0.5f, 1f, 1.5f))

        val inputs = vm.mechanicalInputs(forSweep = false)
        assertEquals(vm.lab.machineLoads!!.loadsN, inputs.loadsN)
        assertEquals(LoadMapping.TYPED_KG.name, inputs.loadMapping)
        assertEquals(TypedLoads.SOURCE, inputs.loadSource)
        // A sweep records no per-frame loads, typed or logged.
        assertTrue(vm.mechanicalInputs(forSweep = true).loadsN.isEmpty())
    }

    @Test
    fun `typing loads replaces an imported log, and importing one replaces typed loads`() {
        bendingWithoutLoads()
        val csv = (MachineLoadCsv.parse("Load (N)\n10\n20\n30\n") as LoadCsvParse.Ok).csv
        vm.setLoadLog(csv, "run1.csv", "")

        vm.setTypedLoads(listOf(0.5f, 1f, 1.5f))
        assertNull(vm.lab.parsedLoadCsv)
        assertEquals("", vm.lab.loadCsvName)
        assertEquals(LoadMapping.TYPED_KG, vm.lab.machineLoads!!.mapping)

        vm.setLoadLog(csv, "run1.csv", "")
        vm.refreshMachineLoads()
        assertTrue(vm.typedLoadsKg.isEmpty())
        assertEquals(LoadMapping.ONE_TO_ONE, vm.lab.machineLoads!!.mapping)
        assertEquals("run1.csv", vm.mechanicalInputs(forSweep = false).loadSource)
    }

    @Test
    fun `clearing typed loads leaves bending waiting for loads`() {
        bendingWithoutLoads()
        vm.setTypedLoads(listOf(0.5f, 1f, 1.5f))

        vm.clearTypedLoads()

        assertNull(vm.lab.machineLoads)
        assertFalse(vm.mechanicalInputsReady())
    }

    @Test
    fun `only bending reads typed loads`() {
        vm.editLab { it.copy(testType = TestType.TENSILE) }
        vm.edit { it.copy(deformedFrames = framesAt("/tmp/def0.jpg")) }
        vm.edit { it.withTypedLoadsKg(listOf(1f)) }
        vm.refreshMachineLoads()

        assertNull(vm.lab.machineLoads)
    }

    // ------------------------------------------------------------ plain 2D DIC

    @Test
    fun `plain DIC needs no loads or dimensions to compute`() {
        vm.editLab { it.copy(testType = TestType.DIC_2D) }
        vm.edit { it.copy(refBytes = ByteArray(8)) }
        vm.edit { it.copy(deformedFrames = framesAt("/tmp/def0.png")) }

        assertFalse(vm.loadPointMissing())
        assertTrue(vm.mechanicalInputsReady())
        assertTrue(vm.isReadyToCompute())
    }

    @Test
    fun `plain DIC records no test, so the session is an untyped DIC session`() {
        vm.editLab { it.copy(testType = TestType.DIC_2D) }
        // Left over from nothing a plain-DIC wizard shows, but must not leak into the record.
        vm.editLab { it.copy(crossSectionMm2 = 12f) }

        assertEquals(MechanicalTestInputs.NONE, vm.mechanicalInputs(forSweep = false))
        assertEquals(MechanicalTestInputs.NONE, vm.mechanicalInputs(forSweep = true))
    }

    private companion object {
        /** 2026-09-26 14:30:00 UTC, as [PhotoCaptureTime] reads it. */
        const val PHOTO_T0 = 1_790_433_000_000L
    }
}
