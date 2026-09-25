package com.indicvision.semper.analysis

import com.indicvision.semper.data.BeamEdgeTaps
import com.indicvision.semper.data.LoadCsvParse
import com.indicvision.semper.data.MachineLoadCsv
import com.indicvision.semper.data.MechanicalTestInputs
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.data.TestType
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
}
