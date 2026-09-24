@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.SpecimenGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StressStrainTest {

    /** A field of [n] accepted points with uniform Exx / Eyy (in strain, not mε). */
    private fun field(exx: Float, eyy: Float, n: Int = 4, accepted: Boolean = true): FloatArray {
        val data = FloatArray(n * DicResult.STRIDE)
        var i = 0
        repeat(n) {
            data[i + DicResult.IDX_EXX] = exx
            data[i + DicResult.IDX_EYY] = eyy
            data[i + DicResult.IDX_ZNSSD] = if (accepted) 0.05f else -1f
            i += DicResult.STRIDE
        }
        return data
    }

    @Test
    fun `axial stress is load over area in megapascals and NaN without an area`() {
        val model = StressStrain.Model.Axial(areaMm2 = 12.5f, axisX = true)

        assertEquals(100f, model.stressMPa(1250f), 1e-4f)
        assertEquals(-8f, model.stressMPa(-100f), 1e-4f)
        assertTrue(StressStrain.Model.Axial(areaMm2 = 0f, axisX = true).stressMPa(100f).isNaN())
    }

    @Test
    fun `axial strain is the mean of the load-axis component in millistrain`() {
        val data = field(exx = 0.002f, eyy = -0.0006f)

        assertEquals(2f, StressStrain.Model.Axial(1f, axisX = true).strainMilli(data)!!, 1e-3f)
        assertEquals(-0.6f, StressStrain.Model.Axial(1f, axisX = false).strainMilli(data)!!, 1e-3f)
        assertNull(StressStrain.Model.Axial(1f, axisX = true).strainMilli(field(0.1f, 0.1f, accepted = false)))
    }

    @Test
    fun `build skips unreadable frames and keeps the load sign`() {
        val fields = listOf(field(0.001f, 0f), null, field(0.003f, 0f))
        val visited = mutableListOf<Int>()

        val curve = StressStrain.build(
            loadsN = listOf(-100f, -200f, -300f),
            model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true),
            frameData = { fields[it] },
            onProgress = { visited += it },
        )

        assertEquals(3, curve.frameCount)
        assertEquals(listOf(0, 2), curve.points.map { it.frame })
        assertEquals(-10f, curve.points[0].stressMPa, 1e-4f)
        assertEquals(1f, curve.points[0].strainMilli, 1e-3f)
        assertEquals(-30f, curve.points[1].stressMPa, 1e-4f)
        assertEquals(listOf(1, 2, 3), visited)
        assertNull(curve.at(1))
        assertEquals(2, curve.peak!!.frame)
    }

    @Test
    fun `a frame with no matched load is left off the curve and its field is not read`() {
        val read = mutableListOf<Int>()

        val curve = StressStrain.build(
            loadsN = listOf(10f, Float.NaN, 30f),
            model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true),
            frameData = {
                read += it
                field(0.001f, 0f)
            },
        )

        assertEquals(listOf(0, 2), curve.points.map { it.frame })
        assertEquals(listOf(0, 2), read)
    }

    @Test
    fun `plot points start at the origin and are strain then stress`() {
        val curve = StressStrain.build(
            loadsN = listOf(50f),
            model = StressStrain.Model.Axial(areaMm2 = 5f, axisX = false),
            frameData = { field(0f, 0.0015f) },
        )

        assertEquals(listOf(0f to 0f, 1.5f to 10f), curve.plotPoints())
        assertTrue(StressStrain.Curve(StressStrain.Model.Axial(1f, true), 0, emptyList()).isEmpty)
    }

    @Test
    fun `flexural stress is three-point bending at the outer fibre`() {
        // σ = 3 P L / (2 b h²): 3 · 200 · 80 / (2 · 10 · 4²) = 48000 / 320 = 150 MPa
        val model = StressStrain.Model.Flexural(spanMm = 80f, widthMm = 10f, thicknessMm = 4f, axisX = true)

        assertTrue(model.isComplete)
        assertEquals(150f, model.stressMPa(200f), 1e-3f)
        assertEquals(-150f, model.stressMPa(-200f), 1e-3f)
        assertEquals(2f, model.strainMilli(field(exx = 0.002f, eyy = -0.0006f))!!, 1e-3f)
        assertEquals(listOf(80f, 10f, 4f), model.dimensions.map { it.second })
    }

    @Test
    fun `a model with a missing dimension is incomplete and gives NaN, never a stress`() {
        assertFalse(StressStrain.Model.Axial(0f, true).isComplete)
        assertTrue(StressStrain.Model.Axial(0f, true).stressMPa(10f).isNaN())
        assertFalse(StressStrain.Model.Flexural(80f, 0f, 4f, true).isComplete)
        assertTrue(StressStrain.Model.Flexural(80f, 0f, 4f, true).stressMPa(10f).isNaN())
    }

    @Test
    fun `the model follows the stored test type and falls back to axial`() {
        val geometry = SpecimenGeometry(spanMm = 80f, widthMm = 10f, thicknessMm = 4f)

        assertEquals(
            StressStrain.Model.Flexural(80f, 10f, 4f, axisX = false),
            StressStrain.Model.of("bending", 12.5f, loadAxisX = false, geometry),
        )
        assertEquals(StressStrain.Model.Axial(12.5f, true), StressStrain.Model.of("tensile", 12.5f, true, geometry))
        assertEquals(StressStrain.Model.Axial(12.5f, true), StressStrain.Model.of("", 12.5f, true, geometry))
        // A session stored as a test this build no longer offers is not mistaken
        // for another test: it reads as no type, which is axial.
        assertEquals(StressStrain.Model.Axial(12.5f, true), StressStrain.Model.of("torsion", 12.5f, true, geometry))
        assertEquals("axial", StressStrain.Model.of("", 0f, true, SpecimenGeometry.NONE).wireName)
        assertEquals("flexural", StressStrain.Model.of("bending", 0f, true, geometry).wireName)
    }
}
