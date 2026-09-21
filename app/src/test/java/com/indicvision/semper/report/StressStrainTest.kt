@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import org.junit.Assert.assertEquals
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
    fun `stress is load over area in megapascals and NaN without an area`() {
        assertEquals(100f, StressStrain.stressMPa(1250f, 12.5f), 1e-4f)
        assertEquals(-8f, StressStrain.stressMPa(-100f, 12.5f), 1e-4f)
        assertTrue(StressStrain.stressMPa(100f, 0f).isNaN())
    }

    @Test
    fun `strain is the mean of the load-axis component in millistrain`() {
        val data = field(exx = 0.002f, eyy = -0.0006f)

        assertEquals(2f, StressStrain.strainMilli(data, axisX = true)!!, 1e-3f)
        assertEquals(-0.6f, StressStrain.strainMilli(data, axisX = false)!!, 1e-3f)
        assertNull(StressStrain.strainMilli(field(0.1f, 0.1f, accepted = false), axisX = true))
    }

    @Test
    fun `build skips unreadable frames and keeps the load sign`() {
        val fields = listOf(field(0.001f, 0f), null, field(0.003f, 0f))
        val visited = mutableListOf<Int>()

        val curve = StressStrain.build(
            loadsN = listOf(-100f, -200f, -300f),
            areaMm2 = 10f,
            axisX = true,
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
    fun `plot points start at the origin and are strain then stress`() {
        val curve = StressStrain.build(
            loadsN = listOf(50f),
            areaMm2 = 5f,
            axisX = false,
            frameData = { field(0f, 0.0015f) },
        )

        assertEquals(listOf(0f to 0f, 1.5f to 10f), curve.plotPoints())
        assertTrue(StressStrain.Curve(1f, true, 0, emptyList()).isEmpty)
    }
}
