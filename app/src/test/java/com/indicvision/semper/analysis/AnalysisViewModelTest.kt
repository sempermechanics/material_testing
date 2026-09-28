package com.indicvision.semper.analysis

import com.indicvision.semper.data.BeamEdgeTaps
import com.indicvision.semper.data.LoadCsvParse
import com.indicvision.semper.data.LoadMapping
import com.indicvision.semper.data.MachineLoadCsv
import com.indicvision.semper.data.MechanicalTestInputs
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.data.TestType
import com.indicvision.semper.data.TypedLoads
import com.indicvision.semper.ui.analysis.AnalysisViewModel
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
 * Wizard state contracts in [AnalysisViewModel].
 *
 * This class is the analysis wizard's brain and the highest-churn file in the
 * app, but it sat in the Kover-excluded `ui` package with no direct coverage.
 * These pin the cheap, load-bearing seams — the compute gate, the working
 * session identity that keeps re-runs on one Home row, and the reset that
 * new inputs depend on — without touching the native solve.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisViewModelTest {

    private lateinit var vm: AnalysisViewModel

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
        vm.defFilePaths = listOf("/tmp/def0.png")
        assertFalse(vm.isReadyToCompute())
    }

    @Test
    fun `ready to compute once a reference and at least one deformed frame exist`() {
        vm.refBytes = ByteArray(8)
        vm.defFilePaths = listOf("/tmp/def0.png")
        assertTrue(vm.isReadyToCompute())
    }

    @Test
    fun `defCount tracks the deformed frame list`() {
        assertEquals(0, vm.defCount)
        vm.defFilePaths = listOf("/tmp/a.png", "/tmp/b.png", "/tmp/c.png")
        assertEquals(3, vm.defCount)
    }

    // ------------------------------------------------- working session identity

    @Test
    fun `a fresh view model would create a new session row`() {
        assertTrue(vm.wouldCreateNewSession())
    }

    @Test
    fun `re-runs over an existing working id do not create another session row`() {
        vm.workingLocalId = "abc123"
        assertFalse(vm.wouldCreateNewSession())
    }

    // -------------------------------------------------------------- reset logic

    @Test
    fun `clearPreviousResults resets every field a new input set must not inherit`() {
        vm.lastBatchDirPath = "/tmp/batch"
        vm.lastRefPath = "/tmp/ref.png"
        vm.lastDefPath = "/tmp/def.png"
        vm.workingLocalId = "abc123"

        vm.clearPreviousResults()

        assertNull(vm.lastBatchDirPath)
        assertNull(vm.lastRefPath)
        assertNull(vm.lastDefPath)
        assertNull(vm.workingLocalId)
    }

    @Test
    fun `clearing results sends the next run back to a new session row`() {
        vm.workingLocalId = "abc123"
        assertFalse(vm.wouldCreateNewSession())

        vm.clearPreviousResults()

        assertTrue(
            "new inputs must start a fresh Home row rather than overwrite the old one",
            vm.wouldCreateNewSession(),
        )
    }

    // ------------------------------------------- RunResult accessor consistency

    @Test
    fun `the lastX accessors read and write through the runResult snapshot`() {
        vm.lastBatchDirPath = "/tmp/batch"
        vm.lastRefPath = "/tmp/ref.png"
        vm.lastDefPath = "/tmp/def.png"
        vm.lastStopCode = 7
        vm.lastPlannedFrames = 12

        val snapshot = vm.runResult.value
        assertEquals("/tmp/batch", snapshot.batchDirPath)
        assertEquals("/tmp/ref.png", snapshot.refPath)
        assertEquals("/tmp/def.png", snapshot.defPath)
        assertEquals(7, snapshot.stopCode)
        assertEquals(12, snapshot.plannedFrames)
    }

    @Test
    fun `each setter leaves the other RunResult fields untouched`() {
        vm.lastPlannedFrames = 12
        vm.lastRefPath = "/tmp/ref.png"

        // A later, unrelated write must not clobber the earlier ones.
        vm.lastStopCode = 3

        assertEquals(12, vm.lastPlannedFrames)
        assertEquals("/tmp/ref.png", vm.lastRefPath)
        assertEquals(3, vm.lastStopCode)
    }

    @Test
    fun `a fresh run result starts empty`() {
        val snapshot = vm.runResult.value
        assertNull(snapshot.batchDirPath)
        assertNull(snapshot.spec)
        assertNull(snapshot.settings)
        assertEquals(0, snapshot.stopCode)
        assertEquals(0, snapshot.plannedFrames)
    }

    // ------------------------------------------------------------ bending load point

    private fun bendingReadyButTaps() {
        vm.testType = TestType.BENDING
        vm.defFilePaths = listOf("/tmp/def0.png", "/tmp/def1.png")
        vm.parsedLoadCsv = (MachineLoadCsv.parse("Load (N)\n10\n20\n") as LoadCsvParse.Ok).csv
        vm.refreshMachineLoads()
        vm.geometry = SpecimenGeometry(spanMm = 935f, widthMm = 150f, thicknessMm = 6.38f)
    }

    @Test
    fun `bending waits for the load point taps once loads and dimensions are in`() {
        bendingReadyButTaps()
        assertTrue(vm.loadPointMissing())
        assertFalse(vm.mechanicalInputsReady())

        vm.geometry = vm.geometry.copy(loadPoint = BeamEdgeTaps(10f, 20f, 10f, 148f))

        assertFalse(vm.loadPointMissing())
        assertTrue(vm.mechanicalInputsReady())
    }

    @Test
    fun `a new reference clears the taps but keeps the dimensions`() {
        bendingReadyButTaps()
        vm.geometry = vm.geometry.copy(loadPoint = BeamEdgeTaps(10f, 20f, 10f, 148f))

        vm.onReferenceReplaced()

        assertEquals(BeamEdgeTaps.NONE, vm.geometry.loadPoint)
        assertEquals(935f, vm.geometry.spanMm)
        assertFalse(vm.mechanicalInputsReady())
    }

    @Test
    fun `tensile never asks for a load point`() {
        assertFalse(vm.loadPointMissing())
    }

    // ------------------------------------------------------ photo load matching

    /** Three photos 1 s, 2 s and 3 s after the reference; a log at 10 Hz from the reference. */
    private fun timedPhotoBatch() {
        vm.defFilePaths = listOf("/tmp/def0.jpg", "/tmp/def1.jpg", "/tmp/def2.jpg")
        vm.refCaptureTimeMs = PHOTO_T0
        vm.defCaptureTimesMs = listOf(PHOTO_T0 + 1_000L, PHOTO_T0 + 2_000L, PHOTO_T0 + 3_000L)
        val log = "Time (s),Load (N)\n" + (0..40).joinToString("\n") { "${it / 10.0},${it * 10}" }
        vm.parsedLoadCsv = (MachineLoadCsv.parse(log) as LoadCsvParse.Ok).csv
    }

    @Test
    fun `photos with capture times take the load logged when each was taken`() {
        timedPhotoBatch()
        vm.refreshMachineLoads()

        val table = vm.machineLoads!!
        assertEquals(LoadMapping.TIME_NEAREST, table.mapping)
        assertEquals(listOf(100f, 200f, 300f), table.loadsN)
    }

    @Test
    fun `the log-start offset shifts photo times as it does video times`() {
        timedPhotoBatch()
        vm.loadLogStartS = 0.5f
        vm.refreshMachineLoads()

        // Row 0 is logged 0.5 s after the reference, so 1 s in is row 5.
        assertEquals(listOf(50f, 150f, 250f), vm.machineLoads!!.loadsN)
    }

    @Test
    fun `a photo without a capture time sends the batch back to row order`() {
        timedPhotoBatch()
        vm.defCaptureTimesMs = listOf(PHOTO_T0 + 1_000L, null, PHOTO_T0 + 3_000L)
        vm.refreshMachineLoads()

        assertEquals(LoadMapping.RESAMPLED, vm.machineLoads!!.mapping)
    }

    @Test
    fun `a reference without a capture time sends the batch back to row order`() {
        timedPhotoBatch()
        vm.refCaptureTimeMs = null
        vm.refreshMachineLoads()

        assertEquals(LoadMapping.RESAMPLED, vm.machineLoads!!.mapping)
    }

    @Test
    fun `video frame times win over any photo times`() {
        timedPhotoBatch()
        vm.defFrameTimesMs = listOf(500L, 1_500L, 2_500L)
        vm.refreshMachineLoads()

        assertEquals(listOf(50f, 150f, 250f), vm.machineLoads!!.loadsN)
    }

    // ------------------------------------------------------- bending typed loads

    /** Bending with three photos, dimensions and taps: everything but the loads. */
    private fun bendingWithoutLoads() {
        vm.testType = TestType.BENDING
        vm.defFilePaths = listOf("/tmp/def0.jpg", "/tmp/def1.jpg", "/tmp/def2.jpg")
        vm.geometry = SpecimenGeometry(
            spanMm = 935f,
            widthMm = 150f,
            thicknessMm = 6.38f,
            loadPoint = BeamEdgeTaps(10f, 20f, 10f, 148f),
        )
        vm.refreshMachineLoads()
    }

    @Test
    fun `typed hanger masses become loads in newtons`() {
        bendingWithoutLoads()
        assertNull(vm.machineLoads)
        assertFalse(vm.mechanicalInputsReady())

        vm.setTypedLoads(listOf(0.5f, 1f, 0f))

        val table = vm.machineLoads!!
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
        assertEquals(vm.machineLoads!!.loadsN, inputs.loadsN)
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
        assertNull(vm.parsedLoadCsv)
        assertEquals("", vm.loadCsvName)
        assertEquals(LoadMapping.TYPED_KG, vm.machineLoads!!.mapping)

        vm.setLoadLog(csv, "run1.csv", "")
        vm.refreshMachineLoads()
        assertTrue(vm.typedLoadsKg.isEmpty())
        assertEquals(LoadMapping.ONE_TO_ONE, vm.machineLoads!!.mapping)
        assertEquals("run1.csv", vm.mechanicalInputs(forSweep = false).loadSource)
    }

    @Test
    fun `clearing typed loads leaves bending waiting for loads`() {
        bendingWithoutLoads()
        vm.setTypedLoads(listOf(0.5f, 1f, 1.5f))

        vm.clearTypedLoads()

        assertNull(vm.machineLoads)
        assertFalse(vm.mechanicalInputsReady())
    }

    @Test
    fun `only bending reads typed loads`() {
        vm.testType = TestType.TENSILE
        vm.defFilePaths = listOf("/tmp/def0.jpg")
        vm.typedLoadsKg = listOf(1f)
        vm.refreshMachineLoads()

        assertNull(vm.machineLoads)
    }

    // ------------------------------------------------------------ plain 2D DIC

    @Test
    fun `plain DIC needs no loads or dimensions to compute`() {
        vm.testType = TestType.DIC_2D
        vm.refBytes = ByteArray(8)
        vm.defFilePaths = listOf("/tmp/def0.png")

        assertFalse(vm.loadPointMissing())
        assertTrue(vm.mechanicalInputsReady())
        assertTrue(vm.isReadyToCompute())
    }

    @Test
    fun `plain DIC records no test, so the session is an untyped DIC session`() {
        vm.testType = TestType.DIC_2D
        // Left over from nothing a plain-DIC wizard shows, but must not leak into the record.
        vm.crossSectionMm2 = 12f

        assertEquals(MechanicalTestInputs.NONE, vm.mechanicalInputs(forSweep = false))
        assertEquals(MechanicalTestInputs.NONE, vm.mechanicalInputs(forSweep = true))
    }

    private companion object {
        /** 2026-09-26 14:30:00 UTC, as [com.indicvision.semper.ui.analysis.PhotoCaptureTime] reads it. */
        const val PHOTO_T0 = 1_790_433_000_000L
    }
}
