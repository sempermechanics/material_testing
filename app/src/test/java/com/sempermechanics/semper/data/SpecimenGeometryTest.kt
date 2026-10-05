@file:Suppress("MagicNumber")

package com.indicvision.semper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecimenGeometryTest {

    @Test
    fun `nine floats round-trip with the taps and the deflection correction`() {
        val geometry = SpecimenGeometry(935f, 150f, 6.38f, BeamEdgeTaps(10f, 20f, 12f, 148f), 1.05f, -0.12f)

        assertEquals(9, geometry.toArray().size)
        assertEquals(geometry, SpecimenGeometry.fromArray(geometry.toArray()))
        assertEquals(1.05f, geometry.deflectionCorrection.scale)
    }

    @Test
    fun `seven floats from an older Intent keep the taps and have no correction`() {
        val taps = BeamEdgeTaps(10f, 20f, 12f, 148f)
        val geometry = SpecimenGeometry.fromArray(floatArrayOf(935f, 150f, 6.38f, 10f, 20f, 12f, 148f))

        assertEquals(SpecimenGeometry(935f, 150f, 6.38f, taps), geometry)
        assertTrue(geometry.deflectionCorrection.isNone)
    }

    @Test
    fun `a scale that is not positive is no correction`() {
        assertTrue(SpecimenGeometry(deflectionScale = 0f, deflectionBiasMm = 0.2f).deflectionCorrection.isNone)
        assertTrue(SpecimenGeometry(deflectionScale = -1f).deflectionCorrection.isNone)
    }

    @Test
    fun `three floats from an older Intent keep the dimensions and have no taps`() {
        val geometry = SpecimenGeometry.fromArray(floatArrayOf(935f, 150f, 6.38f))

        assertEquals(SpecimenGeometry(935f, 150f, 6.38f), geometry)
        assertEquals(BeamEdgeTaps.NONE, geometry.loadPoint)
    }

    @Test
    fun `a missing or short array is none`() {
        assertTrue(SpecimenGeometry.fromArray(null).isNone)
        assertTrue(SpecimenGeometry.fromArray(floatArrayOf(1f, 2f)).isNone)
    }
}
