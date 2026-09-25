@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tensile table's Extension column: ΔL in pixels between two end bands of
 * the analysed region, fixed on the first solved frame.
 */
class ExtensometerTest {

    /**
     * Points every 5 px from 0 to 100 along the axis, three rows across it,
     * stretched evenly by [strain]. [rejectFrom] drops points at and beyond
     * that axis position, as when an end leaves the view.
     */
    private fun field(strain: Float, axisX: Boolean = true, rejectFrom: Float = Float.MAX_VALUE): FloatArray {
        val points = (0..100 step 5).flatMap { along -> (0..2).map { across -> along to across * 10 } }
        val data = FloatArray(points.size * DicResult.STRIDE)
        points.forEachIndexed { k, (along, across) ->
            val i = k * DicResult.STRIDE
            data[i + if (axisX) DicResult.IDX_X else DicResult.IDX_Y] = along.toFloat()
            data[i + if (axisX) DicResult.IDX_Y else DicResult.IDX_X] = across.toFloat()
            data[i + if (axisX) DicResult.IDX_U else DicResult.IDX_V] = strain * along
            data[i + if (axisX) DicResult.IDX_EXX else DicResult.IDX_EYY] = strain
            data[i + DicResult.IDX_ZNSSD] = if (along >= rejectFrom) -1f else 0.05f
        }
        return data
    }

    @Test
    fun `an even stretch gives strain times the distance between the bands`() {
        val gauge = Extensometer.gauge(field(0f), axisX = true)!!

        // Bands 0–10 and 90–100 px: mean positions 5 and 95, so a 90 px gauge.
        assertEquals(90f, gauge.lengthPx, 1e-4f)
        assertEquals(0.09f, gauge.extensionPx(field(0.001f))!!, 1e-5f)
        assertEquals(0.09f / 90f, gauge.extensionPx(field(0.001f))!! / gauge.lengthPx, 1e-7f)
    }

    @Test
    fun `the y axis reads V along y`() {
        val gauge = Extensometer.gauge(field(0f, axisX = false), axisX = false)!!

        assertEquals(0.18f, gauge.extensionPx(field(0.002f, axisX = false))!!, 1e-5f)
    }

    @Test
    fun `a frame whose far end has left the view has no extension`() {
        val gauge = Extensometer.gauge(field(0f), axisX = true)!!

        assertNull(gauge.extensionPx(field(0.05f, rejectFrom = 85f)))
        assertTrue(gauge.extensionPx(field(0.05f, rejectFrom = 101f))!! > 0f)
    }

    @Test
    fun `no accepted points, no gauge`() {
        assertNull(Extensometer.gauge(field(0f, rejectFrom = 0f), axisX = true))
    }

    @Test
    fun `a tensile curve fixes the gauge on its first frame and the report prints px`() {
        val curve = StressStrain.build(
            loadsN = listOf(1000f, 2000f, 3000f),
            model = StressStrain.Model.Axial(areaMm2 = 10f, axisX = true),
            frameData = { listOf(field(0.001f), field(0.002f), field(0.003f, rejectFrom = 50f))[it] },
        )

        assertEquals(90f, curve.gauge!!.lengthPx, 1e-4f)
        assertEquals(listOf(0.09f, 0.18f), curve.points.take(2).map { it.extensionPx!! })
        assertNull(curve.points[2].extensionPx)

        val doc = LabReport.of(curve, null)!!
        val table = doc.blocks.filterIsInstance<LabReport.Block.Table>().single()
        assertEquals(listOf("0.09", "0.18", LabReport.BLANK_CELL), table.rows.map { it[3] })
        val fields = doc.blocks.filterIsInstance<LabReport.Block.Field>().associate { it.label to it.value }
        assertEquals("90 — between the ends of the analysed region", fields["DIC gauge length along x (px)"])
        val calc = doc.blocks.filterIsInstance<LabReport.Block.Calculation>().single()
        assertEquals("Extension ΔL for row 1 = 0.09 px over a DIC gauge of 90 px", calc.lines.last())
    }

    @Test
    fun `bending curves carry no gauge`() {
        val curve = StressStrain.build(
            loadsN = listOf(10f),
            model = StressStrain.Model.Flexural(900f, 100f, 6f, true),
            frameData = { field(0.001f) },
        )

        assertNull(curve.gauge)
        assertNull(curve.points.single().extensionPx)
    }
}
