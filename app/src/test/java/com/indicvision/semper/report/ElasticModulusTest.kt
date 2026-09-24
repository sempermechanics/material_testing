@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class ElasticModulusTest {

    private val model = StressStrain.Model.Axial(areaMm2 = 1f, axisX = true)

    private fun curve(points: List<Pair<Float, Float>>): StressStrain.Curve =
        StressStrain.Curve(
            model,
            points.size,
            points.mapIndexed { i, (strain, stress) -> StressStrain.Point(i, stress, stress, strain) },
        )

    /**
     * Experiment 2's elastic table: a 12.54 mm mild-steel bar, loads in kN,
     * extension over a 25 mm extensometer gauge in 0.002 mm steps.
     */
    private fun labCurve(): StressStrain.Curve {
        val loadsKn = listOf(8.9, 11.025, 13.06, 15.19, 17.42, 18.69, 20.78, 22.645, 24.835, 26.445, 28.33, 30.07)
        val area = PI * 12.54 * 12.54 / 4
        return curve(
            loadsKn.mapIndexed { i, kn ->
                val strainMilli = 0.002 * (i + 1) / 25.0 * 1000.0
                strainMilli.toFloat() to (kn * 1000.0 / area).toFloat()
            },
        )
    }

    @Test
    fun `lab tensile table gives the modulus over all twelve rows`() {
        val fit = ElasticModulus.fit(labCurve())!!

        assertEquals(194.03f, fit.modulusGPa, 0.05f)
        assertEquals(0.99887f, fit.r2, 1e-4f)
        assertEquals(0, fit.firstFrame)
        assertEquals(11, fit.lastFrame)
        assertEquals(12, fit.points)
    }

    @Test
    fun `forcing the origin on preloaded data bends the line, which is why it is off by default`() {
        // The gauge was zeroed under an 8.9 kN preload, so the origin is off the line.
        val points = labCurve().points
        val xs = doubleArrayOf(0.0) + points.map { it.strainMilli.toDouble() }
        val ys = doubleArrayOf(0.0) + points.map { it.stressMPa.toDouble() }
        val throughOrigin = LinearFit.fit(xs, ys)!!

        assertEquals(218.46, throughOrigin.slope, 0.05)
        assertTrue(throughOrigin.r2 < ElasticModulus.MIN_R2)
        assertNull(ElasticModulus.fit(labCurve(), includeOrigin = true))
    }

    @Test
    fun `the run stops at the knee`() {
        // E = 200 GPa for five points, then yielding.
        val elastic = (1..5).map { i -> i * 0.5f to i * 100f }
        val plastic = listOf(3.5f to 560f, 5f to 580f, 8f to 600f)

        val fit = ElasticModulus.fit(curve(elastic + plastic))!!

        assertEquals(200f, fit.modulusGPa, 1e-3f)
        assertEquals(4, fit.lastFrame)
        assertTrue(fit.covers(2))
    }

    @Test
    fun `points after the peak are never candidates`() {
        val rising = (1..4).map { i -> i * 1f to i * 100f }
        val falling = listOf(5f to 300f, 6f to 200f, 7f to 100f)

        val fit = ElasticModulus.fit(curve(rising + falling), minR2 = 0.0)!!

        assertEquals(3, fit.lastFrame)
        assertEquals(100f, fit.modulusGPa, 1e-3f)
    }

    @Test
    fun `too few points or no strain spread gives no fit`() {
        assertNull(ElasticModulus.fit(curve(listOf(1f to 100f, 2f to 200f))))
        assertNull(ElasticModulus.fit(curve(listOf(1f to 100f, 1f to 200f, 1f to 300f))))
        assertNull(ElasticModulus.fit(StressStrain.Curve(model, 0, emptyList())))
    }

    @Test
    fun `a test logged negative still reads a positive modulus`() {
        val fit = ElasticModulus.fit(curve((1..4).map { i -> -i * 0.5f to -i * 100f }))

        assertNotNull(fit)
        assertEquals(200f, fit!!.modulusGPa, 1e-3f)
        assertEquals(-300f, fit.stressAt(-1.5f), 1e-3f)
    }

    @Test
    fun `linear fit recovers an exact line and refuses a vertical one`() {
        val line = LinearFit.fit(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(5.0, 7.0, 9.0))!!

        assertEquals(2.0, line.slope, 1e-12)
        assertEquals(3.0, line.intercept, 1e-12)
        assertEquals(1.0, line.r2, 1e-12)
        assertEquals(11.0, line.at(4.0), 1e-12)
        assertNull(LinearFit.fit(doubleArrayOf(2.0, 2.0), doubleArrayOf(1.0, 3.0)))
        assertNull(LinearFit.fit(doubleArrayOf(1.0), doubleArrayOf(1.0)))
        assertEquals(1.0, LinearFit.fit(doubleArrayOf(1.0, 2.0), doubleArrayOf(4.0, 4.0))!!.r2, 0.0)
    }
}
