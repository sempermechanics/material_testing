@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElasticRegionTest {

    private val model = StressStrain.Model.Axial(areaMm2 = 1f, axisX = true)

    private fun curve(points: List<Pair<Float, Float>>): StressStrain.Curve =
        StressStrain.Curve(
            model,
            points.size,
            points.mapIndexed { i, (strain, stress) -> StressStrain.Point(i, stress, stress, strain) },
        )

    /**
     * Shaped like the real steel test: 26 straight frames below 2 mε, then
     * yield, hardening to a peak far out at 150 mε, necking to 336 mε, and a
     * last frame after fracture where the specimen has sprung back.
     */
    private val elastic = (1..26).map { i ->
        val strain = i * 0.073f
        strain to 200f * strain + 5f
    }
    private val plastic = listOf(2.2f to 400f, 2.6f to 405f, 3.5f to 410f, 10f to 450f, 150f to 540f, 336f to 450f)
    private val sprungBack = 1.0f to 2f
    private val steel = curve(elastic + plastic + sprungBack)
    private val steelFit = ElasticModulus.Fit(200f, 5f, firstFrame = 0, lastFrame = 25, points = 26, r2 = 0.999f)

    @Test
    fun `the window is the fitted run plus half its span, not the whole curve`() {
        val plot = ElasticRegion.of(steel, steelFit)!!

        // Fitted run 0..1.898 mε from the origin, so the window reaches 2.847 mε.
        val xs = plot.points.map { it.first }
        assertEquals(0f to 0f, plot.points.first())
        assertEquals(elastic + listOf(2.2f to 400f, 2.6f to 405f), plot.points.drop(1))
        assertTrue(xs.max() < 3f)
    }

    @Test
    fun `frames after the peak stay out, so a sprung-back specimen is not drawn in the elastic region`() {
        val plot = ElasticRegion.of(steel, steelFit)!!

        assertTrue(sprungBack !in plot.points)
    }

    @Test
    fun `the fitted line spans the whole window`() {
        val plot = ElasticRegion.of(steel, steelFit)!!

        assertEquals(2, plot.line.size)
        assertEquals(0f to 5f, plot.line.first())
        assertEquals(2.6f, plot.line.last().first, 0f)
        assertEquals(525f, plot.line.last().second, 1e-3f)
    }

    @Test
    fun `a compressive curve zooms the same way on the negative side`() {
        val compressive = curve((elastic + plastic).map { (strain, stress) -> -strain to -stress })
        val fit = steelFit.copy(interceptMPa = -5f)

        val plot = ElasticRegion.of(compressive, fit)!!

        assertEquals(29, plot.points.size)
        assertTrue(plot.points.all { it.first > -3f })
        assertEquals(-2.6f to fit.stressAt(-2.6f), plot.line.first())
        assertEquals(0f to -5f, plot.line.last())
    }

    @Test
    fun `a fit over frames the curve does not have gives no plot`() {
        assertNull(ElasticRegion.of(steel, steelFit.copy(firstFrame = 40, lastFrame = 45)))
    }

    @Test
    fun `the modulus fit on the steel shape is drawn inside the window`() {
        val fit = ElasticModulus.fit(steel)
        assertNotNull(fit)
        val plot = ElasticRegion.of(steel, fit!!)!!

        val shown = plot.points.drop(1)
        assertTrue(steel.points.filter { fit.covers(it.frame) }.all { (it.strainMilli to it.stressMPa) in shown })
        assertTrue(plot.points.maxOf { it.first } < 10f)
    }
}
