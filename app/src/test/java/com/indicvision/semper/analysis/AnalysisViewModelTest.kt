package com.indicvision.semper.analysis

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
}
