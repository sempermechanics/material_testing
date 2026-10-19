package com.sempermechanics.semper.results

import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.AnalysisCsvWriter
import com.sempermechanics.semper.report.StressStrain
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

    /** [translatedGrid] with u growing by [exx] per px of x: an even stretch along x. */
    private fun stretchedGrid(exx: Float): FloatArray {
        val data = translatedGrid(exx = exx)
        var i = 0
        while (i < data.size) {
            data[i + DicResult.IDX_U] += exx * data[i + DicResult.IDX_X]
            i += DicResult.STRIDE
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
        assertTrue(text.contains("# semper_csv_version,1\n"))
        assertTrue(!text.contains("# test_type"))
        assertTrue(text.contains("# reference,ref.jpg\n"))
        assertTrue(text.contains("# roi_x,10\n"))
        assertTrue(!text.contains("noise_floor"))
        assertTrue(text.contains("# field_stats\n"))
        assertTrue(text.contains("# frame_a.jpg,41,5,15,U,"))
        assertTrue(
            text.contains(
                "\nimage,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd," +
                    "shift_u_px,shift_v_px,shift_rot_deg\n",
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
                    "shift_u_px,shift_v_px,shift_rot_deg\n",
            ),
        )
        assertTrue(text.contains("frame_a.jpg,0,0,1.5,-2.25,"))
        assertTrue(text.contains(",1.5000,-2.2500,"))
        // A plain DIC session: Semper's columns, no load or stress cells.
        assertTrue(text.lines().none { it.contains("stress_MPa") })
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

    /** [planned]: the 0-based planned frames on disk, in order; a gap is a frame the run did not save. */
    private fun tensileFile(sweep: Boolean, testType: String, planned: List<Int> = listOf(0, 1, 2, 3)): String {
        val out = File.createTempFile("semper_csv_results", ".csv")
        out.deleteOnExit()
        // E = 200 GPa: stress 100 MPa per 0.5 mε, stretched along x so the
        // extensometer's ΔL / L₀ reads it too.
        val frames = planned.map { p ->
            val k = p + 1
            val data = stretchedGrid(exx = 0.0005f * k)
            AnalysisCsvWriter.Frame(
                image = "frame_$k.jpg",
                subset = 41,
                step = 5,
                strainWindow = 15,
                data = { data },
                loadN = 1250f * k,
                plannedIndex = p,
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
        // A straight line to the end never meets the offset line: no Rp0.2.
        assertEquals("# yield_rp02_mpa,", trailer[3])
        assertEquals("# peak_stress_mpa,400.0000,4,true", trailer[4])
        assertEquals("", trailer[5])
        assertEquals(6, trailer.size)
        assertTrue(text.indexOf("# mechanical_results") > text.lastIndexOf("frame_4.jpg,"))
    }

    @Test
    fun `the trailer numbers planned frames, so a skipped frame does not shift them`() {
        // Planned frame 2 (the third) was not saved: the fit runs from frame 1
        // to frame 5, not 1 to 4.
        val text = tensileFile(sweep = false, testType = "tensile", planned = listOf(0, 1, 3, 4))

        val trailer = text.substringAfter("# mechanical_results\n", missingDelimiterValue = "").lines()
        assertEquals(200f, trailer[0].removePrefix("# elastic_modulus_gpa,").toFloat(), 0.01f)
        assertEquals("# elastic_fit_frames,1,5", trailer[1])
    }

    @Test
    fun `the tensile trailer quotes Rp0_2 and the peak stress, as the Results do`() {
        // E = 200 GPa to 250 MPa (1.25 mε), then 0.1 GPa of hardening: the
        // 0.2 % offset line 200 (ε − 2) meets 250 + 0.1 (ε − 1.25) at
        // ε = 3.2510 mε, σ = 250.20 MPa. The peak is the last frame, 250.775 MPa.
        val elastic = (1..5).map { k -> 0.25f * k to 50f * k }
        val plastic = listOf(2.5f, 4f, 6f, 9f).map { e -> e to 250f + 0.1f * (e - 1.25f) }
        val out = File.createTempFile("semper_csv_yield", ".csv")
        out.deleteOnExit()
        val frames = (elastic + plastic).mapIndexed { i, (strainMilli, stressMPa) ->
            val data = stretchedGrid(exx = strainMilli / 1000f)
            AnalysisCsvWriter.Frame("frame_${i + 1}.jpg", 41, 5, 15, { data }, loadN = stressMPa * 12.5f)
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
            testType = "tensile",
            crossSectionMm2 = 12.5f,
        )
        AnalysisCsvWriter.write(out, false, frames, metadata)

        val trailer = out.readText().substringAfter("# mechanical_results\n").lines()
        assertEquals("# elastic_fit_frames,1,5", trailer[1])
        assertEquals(250.0f, trailer[3].removePrefix("# yield_rp02_mpa,").toFloat(), 0.5f)
        val peak = trailer[4].removePrefix("# peak_stress_mpa,").split(',')
        assertEquals(250.775f, peak[0].toFloat(), 1e-3f)
        assertEquals(listOf("9", "true"), peak.drop(1))
    }

    /** A bending file: 100 px over 5 mm, 0.05 mm/px; each frame drops 10·k px = 0.5·k mm under 100·k N. */
    private fun bendingFile(taps: BeamEdgeTaps): String {
        val out = File.createTempFile("semper_csv_bending", ".csv")
        out.deleteOnExit()
        val frames = (1..3).map { k ->
            val data = translatedGrid(u = 0f, v = 10f * k)
            AnalysisCsvWriter.Frame("frame_$k.jpg", 41, 5, 15, { data }, loadN = 100f * k)
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
            testType = "bending",
            geometry = SpecimenGeometry(spanMm = 200f, widthMm = 20f, thicknessMm = 5f, loadPoint = taps),
        )
        AnalysisCsvWriter.write(out, false, frames, metadata)
        return out.readText()
    }

    @Test
    fun `a bending session with taps writes its scale and the lab's table and E values`() {
        val text = bendingFile(BeamEdgeTaps(topX = 100f, topY = 50f, bottomX = 100f, bottomY = 150f))

        assertTrue(text.contains("# load_point_top_px,100.00,50.00\n"))
        assertTrue(text.contains("# load_point_bottom_px,100.00,150.00\n"))
        assertTrue(text.contains("# mm_per_px,0.050000\n"))
        val trailer = text.substringAfter("# mechanical_results\n", missingDelimiterValue = "").lines()
        assertEquals("# bending_step,frame,load_N,deflection_mm,flexural_stress_MPa,e_GPa", trailer[0])
        // I = 20 · 5³ / 12; E = W L³ / (48 δ I) = 160 GPa at every step.
        assertEquals("# bending_step,1,100.0000,0.50000,60.0000,160.0000", trailer[1])
        assertEquals("# e_mean_gpa,160.0000", trailer[4])
        assertEquals("# load_deflection_slope_N_per_mm,200.0000", trailer[5])
        assertEquals("# e_slope_gpa,160.0000", trailer[7])
    }

    @Test
    fun `a bending session tapped bottom edge first writes the same table and E values`() {
        val inOrder = bendingFile(BeamEdgeTaps(topX = 100f, topY = 50f, bottomX = 100f, bottomY = 150f))
        val bottomFirst = bendingFile(BeamEdgeTaps(topX = 100f, topY = 150f, bottomX = 100f, bottomY = 50f))

        // The taps are written as tapped; the results do not depend on it.
        assertTrue(bottomFirst.contains("# load_point_top_px,100.00,150.00\n"))
        assertTrue(bottomFirst.contains("# bending_step,1,100.0000,0.50000,60.0000,160.0000\n"))
        assertEquals(
            inOrder.substringAfter("# mechanical_results\n"),
            bottomFirst.substringAfter("# mechanical_results\n"),
        )
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
    private fun assertFieldCountParity(sweep: Boolean, testType: String = "") {
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
            testType = testType,
        )
        AnalysisCsvWriter.write(out, sweep, frames, metadata)

        val lines = out.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        val header = lines.first()
        // A lab test adds its load and stress columns; a plain session ends at the motion ones.
        val end = if (testType.isEmpty()) ",shift_rot_deg" else ",shift_rot_deg,load_N,stress_MPa"
        assertTrue("header should end with $end: $header", header.endsWith(end))
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

    @Test
    fun `a tensile session's point rows carry the load and stress columns`() {
        assertFieldCountParity(sweep = false, testType = "tensile")
        assertFieldCountParity(sweep = true, testType = "tensile")
    }
}
