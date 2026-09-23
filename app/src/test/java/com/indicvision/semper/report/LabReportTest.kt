@file:Suppress("MagicNumber")

package com.indicvision.semper.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class LabReportTest {

    private val area = (PI * 12.54 * 12.54 / 4).toFloat()
    private val model = StressStrain.Model.Axial(areaMm2 = area, axisX = true)

    /**
     * Experiment 2's elastic table (12.54 mm bar, loads in kN, extension over
     * a 25 mm gauge), then two points past the peak so every margin region
     * appears.
     */
    private fun labCurve(): StressStrain.Curve {
        val loadsKn = listOf(
            8.9f, 11.025f, 13.06f, 15.19f, 17.42f, 18.69f,
            20.78f, 22.645f, 24.835f, 26.445f, 28.33f, 30.07f,
        )
        val elastic = loadsKn.mapIndexed { i, kn ->
            val loadN = kn * 1000f
            StressStrain.Point(i, loadN, model.stressMPa(loadN), 0.002f * (i + 1) / 25f * 1000f)
        }
        val tail = listOf(40f to 3f, 34f to 4f, 30f to 5f).mapIndexed { j, (kn, strain) ->
            val loadN = kn * 1000f
            StressStrain.Point(12 + j, loadN, model.stressMPa(loadN), strain)
        }
        return StressStrain.Curve(model, 15, elastic + tail)
    }

    private fun document(): LabReport.Document {
        val curve = labCurve()
        return LabReport.of(curve, ElasticModulus.fit(curve))!!
    }

    @Test
    fun `tensile sections follow the handwritten report in order`() {
        val doc = document()

        assertEquals("Measurement of Tensile Strains and Modulus of Elasticity", doc.title)
        assertEquals(
            listOf("Aim", "Materials required", "Theory", "Observations", "Calculation", "Results", "Conclusions"),
            doc.headings,
        )
        // Observations → table → calculation → graphs → results, as in Tensile.pdf.
        val kinds = doc.blocks.map { it::class.simpleName }
        assertTrue(kinds.indexOf("Figure") < kinds.indexOf("Table"))
        assertTrue(kinds.indexOf("Table") < kinds.indexOf("Calculation"))
        assertTrue(kinds.indexOf("Calculation") < kinds.indexOf("Graph"))
        assertEquals("RuledLines", kinds.last())
    }

    @Test
    fun `values the app cannot know stay as blank fill-in lines`() {
        val fields = document().blocks.filterIsInstance<LabReport.Block.Field>().associate { it.label to it.value }

        assertTrue(fields.containsKey(LabReportText.Tensile.DIAMETER))
        assertNull(fields[LabReportText.Tensile.DIAMETER])
        assertNull(fields[LabReportText.Tensile.FINAL_DIAMETER])
        assertNull(fields[LabReportText.Tensile.FINAL_GAUGE_LENGTH])
        assertEquals("123.51", fields[LabReportText.Tensile.AREA])
    }

    @Test
    fun `observation table rows carry load in kN, stress and a journal-style strain`() {
        val table = document().blocks.filterIsInstance<LabReport.Block.Table>().single()

        assertEquals(listOf("S.No", "Load (kN)", "Extension (px)", "Stress (MPa)", "Strain"), table.headers)
        assertEquals(15, table.rows.size)
        assertEquals(listOf("1", "8.900", "—", "72.06", "8.00×10⁻⁵"), table.rows[0])
        assertEquals("9.60×10⁻⁴", table.rows[11][4])
    }

    @Test
    fun `margin brackets mark elastic, plastic and break point rows`() {
        val table = document().blocks.filterIsInstance<LabReport.Block.Table>().single()

        assertEquals(
            listOf(
                LabReport.Bracket(0, 11, "Elastic"),
                LabReport.Bracket(12, 12, "Plastic"),
                LabReport.Bracket(13, 14, "Break point"),
            ),
            table.brackets,
        )
    }

    @Test
    fun `row one is worked through as in the report`() {
        val calc = document().blocks.filterIsInstance<LabReport.Block.Calculation>().single()

        assertEquals("Stress = L / A = 8.900 × 10³ N / (123.51 × 10⁻⁶ m²) = 72.06 MPa", calc.lines[0])
        assertTrue(calc.lines[1], calc.lines[1].endsWith("= 8.00×10⁻⁵"))
    }

    @Test
    fun `results quote E and peak stress, and both graphs are annotated`() {
        val doc = document()
        val fields = doc.blocks.filterIsInstance<LabReport.Block.Field>().associate { it.label to it.value }

        val modulus = fields[LabReportText.Tensile.RESULT_E]!!
        assertTrue(modulus, modulus.startsWith("194.0 GPa (frames 1–12"))
        assertEquals("323.87 MPa", fields[LabReportText.Tensile.RESULT_PEAK])
        assertEquals(listOf(LabReport.GRAPH_ELASTIC, LabReport.GRAPH_FULL), doc.graphs.map { it.id })
        assertTrue(doc.graphs.all { it.annotation == "E = 194.0 GPa" })
        assertTrue(doc.graphs.all { g -> g.series.count { it.isFit } == 1 })
    }

    @Test
    fun `no fit still reports, without the elastic graph or bracket`() {
        val curve = labCurve()
        val doc = LabReport.of(curve, modulus = null)!!
        val table = doc.blocks.filterIsInstance<LabReport.Block.Table>().single()

        assertEquals(listOf(LabReport.GRAPH_FULL), doc.graphs.map { it.id })
        assertEquals(listOf("Plastic", "Break point"), table.brackets.map { it.label })
        assertTrue(doc.blocks.any { it is LabReport.Block.Field && it.value == LabReportText.Tensile.NO_FIT })
    }

    @Test
    fun `an empty curve has no report`() {
        assertNull(LabReport.of(StressStrain.Curve(model, 0, emptyList()), null))
    }

    @Test
    fun `strains are written the journal way`() {
        assertEquals("8.00×10⁻⁵", LabReportFormat.sci(0.00008f))
        assertEquals("1.00×10⁻³", LabReportFormat.sci(0.0009999f))
        assertEquals("-2.50×10⁻²", LabReportFormat.sci(-0.025f))
        assertEquals("1.20×10¹", LabReportFormat.sci(12f))
        assertEquals("0", LabReportFormat.sci(0f))
    }
}
