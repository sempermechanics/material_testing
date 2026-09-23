@file:Suppress("MagicNumber")

package com.indicvision.semper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecimenGeometryTest {

    @Test
    fun `seven floats round-trip with the load-point taps`() {
        val geometry = SpecimenGeometry(935f, 150f, 6.38f, BeamEdgeTaps(10f, 20f, 12f, 148f))

        assertEquals(7, geometry.toArray().size)
        assertEquals(geometry, SpecimenGeometry.fromArray(geometry.toArray()))
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
