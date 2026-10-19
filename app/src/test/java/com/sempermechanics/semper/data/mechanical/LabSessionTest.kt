@file:Suppress("MagicNumber")

package com.sempermechanics.semper.data.mechanical

import com.sempermechanics.semper.report.BeamDeflection
import com.sempermechanics.semper.report.StressStrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LabSession]: a session's lab test as one value, with no model for plain DIC. */
class LabSessionTest {

    @Test
    fun `a plain DIC session has no stress model`() {
        assertFalse(LabSession.NONE.isLabTest)
        assertNull(LabSession.NONE.stressModel())
        // Dimensions left over without a type are still no test.
        assertNull(LabSession(crossSectionMm2 = 12.5f).stressModel())
    }

    @Test
    fun `tensile is axial with its area, axis and correction`() {
        val correction = CurveCorrection(stressScale = 1.1f)
        val lab = LabSession("tensile", crossSectionMm2 = 78.54f, loadAxisX = false, curveCorrection = correction)

        assertEquals(StressStrain.Model.Axial(78.54f, axisX = false, correction = correction), lab.stressModel())
    }

    @Test
    fun `bending is flexural with its probe from the taps`() {
        val taps = BeamEdgeTaps(100f, 40f, 100f, 80f)
        val geometry = SpecimenGeometry(spanMm = 80f, widthMm = 10f, thicknessMm = 4f, loadPoint = taps)

        val model = LabSession("bending", geometry = geometry).stressModel() as StressStrain.Model.Flexural

        assertEquals(BeamDeflection.Probe(taps, 4f), model.probe)
        assertEquals(listOf(80f, 10f, 4f), model.dimensions.map { it.second })
    }

    @Test
    fun `a type this build no longer offers is still a lab session, read as axial`() {
        val lab = LabSession("torsion", crossSectionMm2 = 12.5f)

        assertTrue(lab.isLabTest)
        assertEquals(StressStrain.Model.Axial(12.5f, axisX = true), lab.stressModel())
    }

    @Test
    fun `the viewer's and the exports' model reads a plain DIC session as axial`() {
        // The CSV's stress column and the viewer's curve want a model even
        // without a test; Model.of's blank-as-axial reading, in one place.
        val untyped = LabSession(crossSectionMm2 = 12.5f, loadAxisX = false)
        assertEquals(StressStrain.Model.Axial(12.5f, axisX = false), untyped.stressModelOrAxial())
        assertEquals(StressStrain.Model.Axial(0f, axisX = true), LabSession.NONE.stressModelOrAxial())
        val geometry = SpecimenGeometry(spanMm = 80f, widthMm = 10f, thicknessMm = 4f)
        val lab = LabSession("bending", geometry = geometry)
        assertEquals(lab.stressModel(), lab.stressModelOrAxial())
    }
}
