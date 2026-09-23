package com.indicvision.semper.results

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.report.AnalysisCsvWriter
import com.indicvision.semper.report.StressStrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `#` preamble and point header of the analysis CSV.
 */
class AnalysisCsvPreambleTest {

    private fun translatedGrid(u: Float = 1.5f, v: Float = -2.25f, exx: Float = 0.0001f): FloatArray {
        val data = FloatArray(10 * 10 * DicResult.STRIDE)
        var i = 0
        for (row in 0 until 10) {
            for (col in 0 until 10) {
                data[i + DicResult.IDX_X] = col * 20f
                data[i + DicResult.IDX_Y] = row * 20f
                data[i + DicResult.IDX_U] = u
                data[i + DicResult.IDX_V] = v
                data[i + DicResult.IDX_EXX] = exx
                data[i + DicResult.IDX_EYY] = 0.0001f
                data[i + DicResult.IDX_EXY] = 0.0001f
                data[i + DicResult.IDX_ZNSSD] = 0.05f
                i += DicResult.STRIDE
            }
        }
        return data
    }

    @Test
    fun `import csv opens with metadata field stats and base point header`() {
        val out = File.createTempFile("semper_csv", ".csv")
        out.deleteOnExit()
        val data = translatedGrid(u = 0.1f, v = 0.2f)
        val frames = listOf(
            AnalysisCsvWriter.Frame(
                image = "frame_a.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
            ),
        )
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 10,
            roiY = 20,
            roiW = 300,
            roiH = 200,
        )
        AnalysisCsvWriter.write(out, sweep = false, frames, metadata)
        val text = out.readText()
        assertTrue(text.contains("# semper_csv_version,2\n"))
        assertTrue(!text.contains("# test_type"))
        assertTrue(text.contains("# reference,ref.jpg\n"))
        assertTrue(text.contains("# roi_x,10\n"))
        assertTrue(!text.contains("noise_floor"))
        assertTrue(text.contains("# field_stats\n"))
        assertTrue(text.contains("# frame_a.jpg,41,5,15,U,"))
        assertTrue(
            text.contains(
                "\nimage,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd," +
                    "shift_u_px,shift_v_px,shift_rot_deg,load_N,stress_MPa\n",
            ),
        )
        assertTrue(text.contains("\nframe_a.jpg,0,0,0.1,0.2,"))
    }

    @Test
    fun `every csv carries the motion columns in the point header and rows`() {
        val out = File.createTempFile("semper_csv_rec", ".csv")
        out.deleteOnExit()
        val data = translatedGrid(u = 1.5f, v = -2.25f)
        val frames = listOf(
            AnalysisCsvWriter.Frame(
                image = "frame_a.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
            ),
        )
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 0,
            roiY = 0,
            roiW = 640,
            roiH = 480,
        )
        AnalysisCsvWriter.write(out, sweep = false, frames, metadata)
        val text = out.readText()
        assertTrue(
            text.contains(
                "\nimage,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd," +
                    "shift_u_px,shift_v_px,shift_rot_deg,load_N,stress_MPa\n",
            ),
        )
        assertTrue(text.contains("frame_a.jpg,0,0,1.5,-2.25,"))
        assertTrue(text.contains(",1.5000,-2.2500,"))
        // No load log: the two mechanical cells are present and empty.
        assertTrue(text.lines().any { it.startsWith("frame_a.jpg,") && it.endsWith(",,") })
    }

    @Test
    fun `a typed session writes its test preamble and a load and stress per row`() {
        val out = File.createTempFile("semper_csv_mech", ".csv")
        out.deleteOnExit()
        val data = translatedGrid()
        val frames = listOf(
            AnalysisCsvWriter.Frame(
                image = "frame_a.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
                loadN = -1250f,
            ),
        )
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 0,
            roiY = 0,
            roiW = 640,
            roiH = 480,
            testType = "compression",
            crossSectionMm2 = 12.5f,
            loadAxisX = false,
        )
        AnalysisCsvWriter.write(out, sweep = false, frames, metadata)
        val text = out.readText()
        assertTrue(text.contains("# test_type,compression\n"))
        assertTrue(text.contains("# stress_model,axial\n"))
        assertTrue(text.contains("# cross_section_mm2,12.5000\n"))
        assertTrue(text.contains("# load_axis,y\n"))
        assertTrue(text.contains("# load_unit,N\n"))
        assertTrue(text.lines().any { it.startsWith("frame_a.jpg,") && it.endsWith(",-1250.000,-100.0000") })
    }

    @Test
    fun `mechanical cells are empty without a load and stress-less without an area`() {
        val axial = StressStrain.Model.Axial(12.5f, axisX = true)
        assertEquals(",", AnalysisCsvWriter.mechanicalSuffixColumns(null, axial))
        assertEquals("10.000,", AnalysisCsvWriter.mechanicalSuffixColumns(10f, StressStrain.Model.Axial(0f, true)))
        assertEquals("10.000,0.8000", AnalysisCsvWriter.mechanicalSuffixColumns(10f, axial))
    }

    @Test
    fun `a bending session writes its dimensions and model and a flexural stress per row`() {
        val out = File.createTempFile("semper_csv_bend", ".csv")
        out.deleteOnExit()
        val data = translatedGrid()
        val frames = listOf(
            AnalysisCsvWriter.Frame(
                image = "frame_a.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
                loadN = 200f,
            ),
        )
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 0,
            roiY = 0,
            roiW = 640,
            roiH = 480,
            testType = "bending",
            geometry = SpecimenGeometry(spanMm = 80f, widthMm = 10f, thicknessMm = 4f),
        )
        AnalysisCsvWriter.write(out, sweep = false, frames, metadata)
        val text = out.readText()
        assertTrue(text.contains("# test_type,bending\n"))
        assertTrue(text.contains("# stress_model,flexural\n"))
        assertTrue(text.contains("# span_mm,80.0000\n# width_mm,10.0000\n# thickness_mm,4.0000\n"))
        assertTrue(!text.contains("cross_section_mm2"))
        // 3 · 200 · 80 / (2 · 10 · 16) = 150 MPa
        assertTrue(text.lines().any { it.startsWith("frame_a.jpg,") && it.endsWith(",200.000,150.0000") })
    }

    private fun tensileFile(sweep: Boolean, testType: String): String {
        val out = File.createTempFile("semper_csv_results", ".csv")
        out.deleteOnExit()
        // E = 200 GPa: stress 100 MPa per 0.5 mε.
        val frames = (1..4).map { k ->
            val data = translatedGrid(exx = 0.0005f * k)
            AnalysisCsvWriter.Frame(
                image = "frame_$k.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
                loadN = 1250f * k,
            )
        }
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 0,
            roiY = 0,
            roiW = 640,
            roiH = 480,
            testType = testType,
            crossSectionMm2 = 12.5f,
        )
        AnalysisCsvWriter.write(out, sweep, frames, metadata)
        return out.readText()
    }

    @Test
    fun `a tensile session ends with the modulus trailer after its point rows`() {
        val text = tensileFile(sweep = false, testType = "tensile")

        assertTrue(text.contains("# semper_csv_version,2\n"))
        val trailer = text.substringAfter("# mechanical_results\n", missingDelimiterValue = "").lines()
        val modulus = trailer[0].removePrefix("# elastic_modulus_gpa,").toFloat()
        assertEquals(200f, modulus, 0.01f)
        assertEquals("# elastic_fit_frames,1,4", trailer[1])
        assertTrue(trailer[2], trailer[2].startsWith("# elastic_fit_r2,"))
        assertEquals("", trailer[3])
        assertEquals(4, trailer.size)
        assertTrue(text.indexOf("# mechanical_results") > text.lastIndexOf("frame_4.jpg,"))
    }

    @Test
    fun `sweeps and untyped sessions have no results trailer`() {
        assertTrue(!tensileFile(sweep = true, testType = "tensile").contains("mechanical_results"))
        assertTrue(!tensileFile(sweep = false, testType = "").contains("mechanical_results"))
    }

    /**
     * Every data row has exactly as many fields as the header names.
     *
     * The `contains(…)` assertions above pin the text of the header and of
     * individual values, and would all still pass if the separator between the
     * point columns and the motion suffix were dropped or doubled — the one
     * mistake the suffix assembly can make. This counts instead, on a real
     * file, for both the single and the sweep header (whose prefix carries four
     * extra columns) and for the null-fit path (a frame whose data provider
     * returns an unsolvable field writes no rows, so the fit is exercised by
     * the solved frame beside it).
     */
    private fun assertFieldCountParity(sweep: Boolean) {
        val out = File.createTempFile("semper_csv_parity", ".csv")
        out.deleteOnExit()
        val data = translatedGrid()
        val frames = listOf(
            AnalysisCsvWriter.Frame(
                image = "frame_a.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
            ),
            AnalysisCsvWriter.Frame(
                image = "frame_b.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { null },
            ),
        )
        val metadata = AnalysisCsvWriter.Metadata(
            referenceName = "ref.jpg",
            strainMethod = "VSG",
            imgW = 640,
            imgH = 480,
            roiX = 0,
            roiY = 0,
            roiW = 640,
            roiH = 480,
        )
        AnalysisCsvWriter.write(out, sweep, frames, metadata)

        val lines = out.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val header = lines.first()
        assertTrue(
            "header should end with the mechanical columns: $header",
            header.endsWith(",shift_rot_deg,load_N,stress_MPa"),
        )
        val expected = header.split(',').size
        val rows = lines.drop(1)
        assertTrue("no data rows were written", rows.isNotEmpty())
        rows.forEachIndexed { index, line ->
            assertEquals(
                "row $index has the wrong field count (sweep=$sweep): $line",
                expected,
                line.split(',').size,
            )
        }
    }

    @Test
    fun `every point row has as many fields as the single header`() {
        assertFieldCountParity(sweep = false)
    }

    @Test
    fun `every point row has as many fields as the sweep header`() {
        assertFieldCountParity(sweep = true)
    }
}
