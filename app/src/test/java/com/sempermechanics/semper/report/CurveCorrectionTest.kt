@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.CurveCorrection
import com.indicvision.semper.data.SpecimenGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CurveCorrectionTest {

    /** [n] × [n] accepted points 10 px apart, stretched evenly along x by [exx]. */
    private fun field(exx: Float, n: Int = 4): FloatArray {
        val data = FloatArray(n * n * DicResult.STRIDE)
        var i = 0
        for (row in 0 until n) {
            for (col in 0 until n) {
                val x = col * 10f
                data[i + DicResult.IDX_X] = x
                data[i + DicResult.IDX_Y] = row * 10f
                data[i + DicResult.IDX_U] = exx * x
                data[i + DicResult.IDX_EXX] = exx
                data[i + DicResult.IDX_ZNSSD] = 0.05f
                i += DicResult.STRIDE
            }
        }
        return data
    }

    private fun curve(correction: CurveCorrection) = StressStrain.build(
        loadsN = listOf(100f, 200f, 300f),
        model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true, correction = correction),
        frameData = { field(0.001f * (it + 1)) },
    )

    private val match = CurveCorrection(
        strainScale = 2f,
        strainBiasMilli = -0.5f,
        stressScale = 1.5f,
        stressBiasMPa = 3f,
    )

    @Test
    fun `the correction scales then shifts strain and stress, and removing it gives back the measured value`() {
        assertEquals(1.5f, match.strain(1f), 1e-6f)
        assertEquals(18f, match.stress(10f), 1e-6f)
        assertEquals(1f, match.removeStrain(match.strain(1f)), 1e-6f)
        assertEquals(10f, match.removeStress(match.stress(10f)), 1e-6f)
        assertTrue(CurveCorrection.NONE.isNone)
    }

    @Test
    fun `the axial model applies the correction to every point and to the peak`() {
        val plain = curve(CurveCorrection.NONE)
        val matched = curve(match)

        plain.points.zip(matched.points).forEach { (p, m) ->
            assertEquals(match.strain(p.strainMilli), m.strainMilli, 1e-4f)
            assertEquals(match.stress(p.stressMPa), m.stressMPa, 1e-4f)
            assertEquals(p.loadN, m.loadN, 0f)
        }
        assertEquals(match.stress(30f), matched.peakStress!!.stressMPa, 1e-4f)
    }

    @Test
    fun `recorrect moves a built curve to a new correction without the frames`() {
        val rebuilt = curve(match)
        val moved = StressStrain.recorrect(curve(CurveCorrection.NONE), match)

        assertEquals(rebuilt.model, moved.model)
        rebuilt.points.zip(moved.points).forEach { (r, m) ->
            assertEquals(r.strainMilli, m.strainMilli, 1e-4f)
            assertEquals(r.stressMPa, m.stressMPa, 1e-4f)
        }
        assertEquals(rebuilt.peakStress!!.stressMPa, moved.peakStress!!.stressMPa, 1e-4f)

        val back = StressStrain.recorrect(moved, CurveCorrection.NONE)
        curve(CurveCorrection.NONE).points.zip(back.points).forEach { (p, b) ->
            assertEquals(p.strainMilli, b.strainMilli, 1e-4f)
            assertEquals(p.stressMPa, b.stressMPa, 1e-4f)
        }
    }

    @Test
    fun `recorrect leaves a bending curve alone`() {
        val bending = StressStrain.Curve(
            StressStrain.Model.of("bending", 0f, true, SpecimenGeometry(100f, 10f, 5f)),
            1,
            listOf(StressStrain.Point(0, 10f, 1f, 1f)),
        )
        assertSame(bending, StressStrain.recorrect(bending, match))
    }

    @Test
    fun `only a tensile model carries the correction`() {
        val tensile = StressStrain.Model.of("tensile", 10f, true, SpecimenGeometry.NONE, match)
        assertEquals(match, (tensile as StressStrain.Model.Axial).correction)
        assertEquals(match.stress(10f), tensile.stressMPa(100f), 1e-5f)
        assertTrue(
            StressStrain.Model.of("bending", 10f, true, SpecimenGeometry(100f, 10f, 5f), match) !is
                StressStrain.Model.Axial,
        )
    }

    @Test
    fun `typed values and the intent array are checked`() {
        assertEquals(1f, CurveCorrection.parseScale("  ")!!, 0f)
        assertEquals(1.25f, CurveCorrection.parseScale("1,25")!!, 0f)
        assertNull(CurveCorrection.parseScale("0"))
        assertNull(CurveCorrection.parseScale("-2"))
        assertNull(CurveCorrection.parseScale("abc"))
        assertEquals(0f, CurveCorrection.parseBias("")!!, 0f)
        assertEquals(-0.12f, CurveCorrection.parseBias("-0.12")!!, 0f)
        assertNull(CurveCorrection.parseBias("NaN"))

        assertEquals(match, CurveCorrection.fromArray(match.toArray()))
        assertEquals(CurveCorrection.NONE, CurveCorrection.fromArray(null))
        assertEquals(CurveCorrection.NONE, CurveCorrection.fromArray(floatArrayOf(2f, 1f)))
        assertEquals(CurveCorrection.NONE, CurveCorrection.of(0f, 0f, 1f, 0f))
        assertEquals(CurveCorrection.NONE, CurveCorrection.of(1f, Float.POSITIVE_INFINITY, 1f, 0f))
    }
}
