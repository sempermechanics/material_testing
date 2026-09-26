@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import com.indicvision.semper.data.BeamEdgeTaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bending lab report against Experiment 5 (Bending.pdf): L = 935 mm,
 * b = 150 mm, t = 6.38 mm, hanger kg × 9.81, the dial readings standing in
 * for the DIC deflection.
 */
class LabReportBendingTest {

    private val labKg = floatArrayOf(4.28f, 5.24f, 6.92f, 8.68f, 10.06f, 11.92f)
    private val labMm = floatArrayOf(1.14f, 1.46f, 2.02f, 2.63f, 3.25f, 3.88f)

    private fun document(): LabReport.Document {
        // 0.05 mm/px: 6.38 mm over 127.6 px.
        val taps = BeamEdgeTaps(0f, 0f, 0f, 127.6f)
        val model = StressStrain.Model.Flexural(935f, 150f, 6.38f, true, BeamDeflection.Probe(taps, 6.38f))
        val points = labKg.indices.map { i ->
            val w = labKg[i] * 9.81f
            StressStrain.Point(i, w, model.stressMPa(w), 0f, labMm[i])
        }
        return LabReport.of(StressStrain.Curve(model, points.size, points), null)!!
    }

    @Test
    fun `bending sections follow the handwritten report in order`() {
        val doc = document()

        assertEquals(LabReportText.Bending.TITLE, doc.title)
        assertEquals(
            listOf(
                "Aim",
                "Experimental setup",
                "Theory",
                "Experimental procedure",
                "Observations",
                "Calculation",
                "Results",
            ),
            doc.headings,
        )
        // Figure → observations → calculation → table → results → graph, as in Bending.pdf.
        val kinds = doc.blocks.map { it::class.simpleName }
        assertTrue(kinds.indexOf("Figure") < kinds.indexOf("Calculation"))
        assertTrue(kinds.indexOf("Calculation") < kinds.indexOf("Table"))
        assertEquals("Graph", kinds.last())
    }

    @Test
    fun `the observation table has the report's columns and one row per step`() {
        val table = document().blocks.filterIsInstance<LabReport.Block.Table>().single()

        assertEquals(LabReportText.Bending.TABLE_HEADERS, table.headers)
        assertEquals(6, table.rows.size)
        assertEquals(listOf("1", "1", "41.99", "1.140", "9.645", "193.21"), table.rows[0])
    }

    @Test
    fun `table rows name the viewer's frames when frames are dropped or held`() {
        // The reference (no load) is dropped, and one load is held over frames 4–6,
        // so Sr. No and the viewer's frame number part ways.
        val taps = BeamEdgeTaps(0f, 0f, 0f, 127.6f)
        val model = StressStrain.Model.Flexural(935f, 150f, 6.38f, true, BeamDeflection.Probe(taps, 6.38f))
        val loads = listOf(0f, 40f, 50f, 60f, 60f, 60f, 70f)
        val points = loads.mapIndexed { i, w -> StressStrain.Point(i, w, model.stressMPa(w), 0f, w / 40f) }
        val doc = LabReport.of(StressStrain.Curve(model, points.size, points), null)!!
        val table = doc.blocks.filterIsInstance<LabReport.Block.Table>().single()

        assertEquals(listOf("1", "2", "3", "4"), table.rows.map { it[0] })
        assertEquals(listOf("2", "3", "4–6", "7"), table.rows.map { it[1] })
    }

    @Test
    fun `row one is worked through in SI units`() {
        val calc = document().blocks.filterIsInstance<LabReport.Block.Calculation>().single()

        assertEquals("σb = M · y / I", calc.lines[0])
        assertEquals("M = W·L / 4 = 41.99 N × 0.935 m / 4 = 9.81 N·m", calc.lines[1])
        assertEquals("σb = 9.645 MPa", calc.lines[4])
        assertTrue(calc.lines[5], calc.lines[5].endsWith("E = 193.21 GPa"))
    }

    @Test
    fun `results give both E values and the graph carries the slope`() {
        val doc = document()
        val fields = doc.blocks.filterIsInstance<LabReport.Block.Field>().associate { it.label to it.value }

        assertEquals("173.6 GPa", fields[LabReportText.Bending.RESULT_MEAN])
        assertEquals("142.0 GPa", fields[LabReportText.Bending.RESULT_GRAPH])
        assertEquals("0.0500", fields[LabReportText.Bending.SCALE])
        val graph = doc.blocks.filterIsInstance<LabReport.Block.Graph>().single()
        assertEquals("Slope = 27.06 N/mm · E = 142.0 GPa", graph.annotation)
        assertEquals(2, graph.series.size)
        // The fit runs over the lab's readings, 1.14 to 3.88 mm, not from δ = 0.
        val fit = graph.series.single { it.isFit }.points
        assertEquals(1.14f, fit.first().first, 1e-4f)
        assertEquals(3.88f, fit.last().first, 1e-4f)
    }
}
