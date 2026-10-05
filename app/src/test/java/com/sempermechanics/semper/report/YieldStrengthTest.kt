@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YieldStrengthTest {

    private val axial = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true)

    /** (strain mε, stress MPa) per frame, from frame 0. */
    private fun curve(points: List<Pair<Float, Float>>, model: StressStrain.Model = axial) =
        StressStrain.Curve(
            model,
            points.size,
            points.mapIndexed { i, (strain, stress) -> StressStrain.Point(i, stress * 10f, stress, strain) },
        )

    /** E = 200 GPa to 400 MPa (2 mε), then 1 GPa of hardening: a bilinear steel. */
    private val bilinear = listOf(
        0.5f to 100f,
        1f to 200f,
        1.5f to 300f,
        2f to 400f,
        3f to 401f,
        5f to 403f,
        7f to 405f,
    )

    @Test
    fun `Rp0_2 is where the curve meets the E line moved 2 me`() {
        val c = curve(bilinear)
        val fit = ElasticModulus.fit(c)!!

        val y = YieldStrength.offset(c, fit)!!

        // 200 (e − 2) = 400 + (e − 2), so e = 2 + 400 / 199.
        assertEquals(200f, fit.modulusGPa, 1e-3f)
        assertEquals(2f + 400f / 199f, y.strainMilli, 1e-3f)
        assertEquals(400f + 400f / 199f, y.stressMPa, 1e-2f)
        assertEquals(4, y.frameBefore)
        assertEquals(5, y.frameAfter)
    }

    @Test
    fun `a test logged negative yields on the negative side`() {
        val c = curve(bilinear.map { (e, s) -> -e to -s })

        val y = YieldStrength.offset(c, ElasticModulus.fit(c)!!)!!

        assertEquals(-(2f + 400f / 199f), y.strainMilli, 1e-3f)
        assertEquals(-(400f + 400f / 199f), y.stressMPa, 1e-2f)
    }

    @Test
    fun `a curve that ends or peaks before the offset line has no yield`() {
        val short = curve(bilinear.take(5))
        // Peaks at 3 mε and falls away: the crossing after the peak does not count.
        val brittle = curve(bilinear.take(5) + listOf(5f to 100f, 7f to 50f))

        assertNull(YieldStrength.offset(short, ElasticModulus.fit(short)!!))
        assertNull(YieldStrength.offset(brittle, ElasticModulus.fit(brittle)!!))
    }

    @Test
    fun `bending and a negative E have no yield`() {
        val flexural = StressStrain.Model.Flexural(100f, 10f, 2f, axisX = true)
        val bending = curve(bilinear, model = flexural)
        val c = curve(bilinear)
        val negative = ElasticModulus.fit(c)!!.copy(modulusGPa = -200f)

        assertNull(YieldStrength.offset(bending, ElasticModulus.fit(bending)!!))
        assertNull(YieldStrength.offset(c, negative))
    }
}
