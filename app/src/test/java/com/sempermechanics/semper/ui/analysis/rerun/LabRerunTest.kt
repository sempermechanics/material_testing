package com.sempermechanics.semper.ui.analysis.rerun

import android.content.Intent
import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.LoadMapping
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import com.sempermechanics.semper.ui.analysis.wizard.lab
import com.sempermechanics.semper.ui.analysis.wizard.typedLoadsKg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home's Re-run of a lab test (TD-220): the record's test, dimensions, taps
 * and typed masses reach the wizard, and a test that read a load log lands on
 * step 1 to import it again.
 */
@RunWith(RobolectricTestRunner::class)
class LabRerunTest {

    private val geometry = SpecimenGeometry(80f, 10.5f, 4f, BeamEdgeTaps(100f, 40f, 112f, 90f), 1.05f, -0.12f)

    // What the run saved for masses typed as 0, 0.5 and 1.2345 kg: kg × g, as TypedLoads.toTable does.
    private val typedN = listOf(0f, 0.5f * TypedLoads.G, 1.2345f * TypedLoads.G)

    private fun record(testType: String, loadSource: String, loadsN: List<Float>): SessionRecord = sessionRecord(
        frameCount = 3,
        defNames = listOf("f1.png", "f2.png", "f3.png"),
    ).copy(
        testType = testType,
        crossSectionMm2 = if (testType == "tensile") 19.63f else 0f,
        loadAxisX = false,
        geometry = if (testType == "bending") geometry else SpecimenGeometry.NONE,
        loadsN = loadsN,
        loadSource = loadSource,
    )

    private fun rerunWizard(lab: LabRerun): AnalysisViewModel = AnalysisViewModel().apply {
        lab.applyTo(this)
        // What applyRerun leaves: the copied frames (no masses on them) and the parameters page.
        val copied = listOf("f1.png", "f2.png", "f3.png").map { DeformedFrame("/copy/$it", it) }
        edit { it.copy(deformedFrames = copied, step = WizardStep.SETTINGS) }
        lab.applyLoadsTo(this)
    }

    @Test
    fun `a plain DIC analysis, or a test this build does not offer, re-runs as before`() {
        assertNull(LabRerun.of(record("", "", emptyList())))
        assertNull(LabRerun.of(record("torsion", "", emptyList())))
        assertNull(LabRerun.from(Intent()))
    }

    @Test
    fun `the lab test rides the Intent unchanged`() {
        val lab = LabRerun.of(record("bending", TypedLoads.SOURCE, typedN))!!

        val read = LabRerun.from(lab.putOn(Intent()))

        assertEquals(LabRerun(TestType.BENDING, 0f, false, geometry, typedN), read)
    }

    @Test
    fun `typed bending comes back with its masses and taps, ready to compute`() {
        val vm = rerunWizard(LabRerun.of(record("bending", TypedLoads.SOURCE, typedN))!!)

        assertEquals(TestType.BENDING, vm.lab.testType)
        assertEquals(geometry, vm.lab.geometry)
        assertEquals(listOf(0f, 0.5f, 1.2345f), vm.typedLoadsKg)
        // The loads the run reads are bit-identical to the saved ones.
        assertEquals(typedN, vm.lab.machineLoads?.loadsN)
        assertEquals(LoadMapping.TYPED_KG, vm.lab.machineLoads?.mapping)
        assertEquals(WizardStep.SETTINGS, vm.inputs.value.step)
    }

    @Test
    fun `a test that read a load log asks for it again on step 1`() {
        val vm = rerunWizard(LabRerun.of(record("tensile", "machine.csv", listOf(0f, 500f, 900f)))!!)

        assertEquals(TestType.TENSILE, vm.lab.testType)
        assertEquals(19.63f, vm.lab.crossSectionMm2, 0f)
        assertEquals(false, vm.lab.loadAxisX)
        assertNull(vm.lab.machineLoads)
        assertEquals(WizardStep.IMAGES, vm.inputs.value.step)
    }

    @Test
    fun `typed masses for other frames than the copy are not put on it`() {
        val lab = LabRerun.of(record("bending", TypedLoads.SOURCE, typedN))!!.copy(typedLoadsN = typedN.take(2))

        val vm = rerunWizard(lab)

        assertEquals(emptyList<Float?>(), vm.typedLoadsKg)
        assertEquals(WizardStep.IMAGES, vm.inputs.value.step)
    }

    @Test
    fun `a saved load is given back as the mass that was typed`() {
        // numpy: float32(kg × g) / g reads 0.5, 1.2345 and every 0.01 kg up to 10 kg back after 6-digit rounding.
        assertEquals(0.5f, LabRerun.typedKg(0.5f * TypedLoads.G)!!, 0f)
        assertEquals(1.2345f, LabRerun.typedKg(1.2345f * TypedLoads.G)!!, 0f)
        assertEquals(0f, LabRerun.typedKg(0f)!!, 0f)
        assertNull(LabRerun.typedKg(Float.NaN))
    }
}
