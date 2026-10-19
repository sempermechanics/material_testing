@file:Suppress("MagicNumber")

package com.sempermechanics.semper.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TensileResults]: E, Rp0.2, the peak and the fitted line, worked out once for every surface. */
class TensileResultsTest {

    private val axial = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true)

    /** (strain mε, stress MPa) per frame, from frame 0. */
    private fun curve(points: List<Pair<Float, Float>>, model: StressStrain.Model = axial) =
        StressStrain.Curve(
            model,
            points.size,
            points.mapIndexed { i, (strain, stress) -> StressStrain.Point(i, stress * 10f, stress, strain) },
        )

    /** E = 200 GPa to 250 MPa, then 0.1 GPa of hardening. */
    private val bilinear = (1..5).map { k -> 0.25f * k to 50f * k } +
        listOf(2.5f, 4f, 6f, 9f).map { e -> e to 250f + 0.1f * (e - 1.25f) }

    @Test
    fun `a bilinear steel gives E, Rp0_2, the peak and the line across the fitted frames`() {
        val results = TensileResults.of(curve(bilinear))!!

        val modulus = results.modulus!!
        assertEquals(200f, modulus.modulusGPa, 1e-3f)
        assertEquals(0, modulus.firstFrame)
        assertEquals(4, modulus.lastFrame)
        // 200 (ε − 2) = 250 + 0.1 (ε − 1.25): ε = 649.875 / 199.9 = 3.2510 mε, σ = 250.20 MPa.
        assertEquals(250.20f, results.yieldPoint!!.stressMPa, 0.01f)
        assertEquals(3.2510f, results.yieldPoint!!.strainMilli, 1e-3f)
        assertEquals(8, results.peak!!.frame)
        assertTrue(results.peak!!.onCurve)
        val line = results.fitLine!!
        assertEquals(0.25f, line.first().first, 1e-6f)
        assertEquals(50f, line.first().second, 1e-3f)
        assertEquals(1.25f, line.last().first, 1e-6f)
        assertEquals(250f, line.last().second, 1e-3f)
    }

    @Test
    fun `without a fit there is no yield and no line, but the peak stands`() {
        val results = TensileResults.of(curve(bilinear), modulus = null)

        assertNull(results.yieldPoint)
        assertNull(results.fitLine)
        assertEquals(8, results.peak!!.frame)
    }

    @Test
    fun `bending has no tensile results`() {
        val flexural = StressStrain.Model.Flexural(100f, 10f, 2f, axisX = true)

        assertNull(TensileResults.of(curve(bilinear, flexural)))
    }
}
