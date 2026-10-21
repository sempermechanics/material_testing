package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.data.mechanical.CaptureStamp
import com.sempermechanics.semper.data.mechanical.TestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The wizard's lab inputs are one value, [WizardInputs.lab], read as
 * [AnalysisViewModel.lab] and changed through the view model's one edit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LabWizardStateTest {

    @Test
    fun `each lab edit changes one field of the lab value`() {
        val vm = AnalysisViewModel()

        vm.editLab { it.copy(testType = TestType.BENDING) }
        vm.editLab { it.copy(crossSectionMm2 = 78.54f) }
        vm.editLab { it.copy(loadLogStartS = 0.5f) }
        vm.editLab { it.copy(loadCsvName = "run1.csv") }

        assertEquals(
            LabWizardState(
                testType = TestType.BENDING,
                crossSectionMm2 = 78.54f,
                loadCsvName = "run1.csv",
                loadLogStartS = 0.5f,
            ),
            vm.lab,
        )
        assertEquals("the lab value is the wizard's inputs' own", vm.inputs.value.lab, vm.lab)
    }

    @Test
    fun `a new lab value resets every lab field at once`() {
        val vm = AnalysisViewModel()
        vm.editLab { it.copy(testType = TestType.TENSILE, refCapture = CaptureStamp(1_000L, subSeconds = true)) }

        vm.edit { it.copy(lab = LabWizardState()) }

        assertEquals(TestType.DIC_2D, vm.lab.testType)
        assertNull(vm.lab.refCapture)
        assertNull(vm.lab.machineLoads)
    }
}
