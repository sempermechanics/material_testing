package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.data.mechanical.TestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The wizard's lab inputs are one value, [AnalysisViewModel.lab], behind its eleven properties. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabWizardStateTest {

    @Test
    fun `each lab property reads and writes one field of the lab value`() {
        val vm = AnalysisViewModel()

        vm.testType = TestType.BENDING
        vm.crossSectionMm2 = 78.54f
        vm.loadLogStartS = 0.5f
        vm.loadCsvName = "run1.csv"

        assertEquals(
            LabWizardState(
                testType = TestType.BENDING,
                crossSectionMm2 = 78.54f,
                loadCsvName = "run1.csv",
                loadLogStartS = 0.5f,
            ),
            vm.lab,
        )
    }

    @Test
    fun `a new lab value resets every lab property at once`() {
        val vm = AnalysisViewModel()
        vm.testType = TestType.TENSILE
        vm.refCaptureTimeMs = 1_000L

        vm.lab = LabWizardState()

        assertEquals(TestType.DIC_2D, vm.testType)
        assertNull(vm.refCaptureTimeMs)
        assertNull(vm.machineLoads)
    }
}
