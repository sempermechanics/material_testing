package com.sempermechanics.semper.results

import android.graphics.Bitmap
import com.sempermechanics.semper.report.FieldResult
import com.sempermechanics.semper.report.PdfLayoutEngine
import com.sempermechanics.semper.report.ReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Max / min / mean are computed differently by surface and the labels say
 * which (readiness AE1-017): the viewer and the CSV give the raw extremes and
 * the signed mean; the PDF leaves out the top and bottom 2% and averages
 * |strain|. The numbers stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
class ExtremaLabelTest {

    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the viewer says its extremes are raw and gives the unit on every line`() {
        val located = strings.getValue("viewer_stats_fmt")
        val plain = strings.getValue("viewer_stats_plain_fmt")
        assertTrue(located, located.startsWith("max (raw) ") && "\\nmin (raw) " in located)
        assertTrue(plain, plain.startsWith("max (raw) ") && " min (raw) " in plain)
        assertEquals(located, 3, located.split("%8\$s").size - 1)
        assertEquals(plain, 3, plain.split("%4\$s").size - 1)
    }

    @Test
    fun `the PDF names its percentile extremes and its location unit`() {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val field = FieldResult("Exx", "Exx", "mε", -1f, 1f, 0f, "Mean", 0.5f, 5, 6, 3, 4, bitmap)
        val header = PdfLayoutEngine.FIELD_TABLE_HEADERS
        val rows = PdfLayoutEngine.fieldTableRows(field)
        assertEquals(listOf("Metric", "Value", "Location (x, y) px"), header)
        assertEquals(
            listOf("Maximum (98th percentile)", "Minimum (2nd percentile)", "Mean", "Standard deviation"),
            rows.map { it.first() },
        )
        assertEquals("(3, 4)", rows[0][2])
    }

    @Test
    fun `the PDF mean of a strain says it averages the magnitude`() {
        assertEquals("Mean of |value|", ReportBuilder.meanLabel(isStrain = true))
        assertEquals("Mean", ReportBuilder.meanLabel(isStrain = false))
    }
}
