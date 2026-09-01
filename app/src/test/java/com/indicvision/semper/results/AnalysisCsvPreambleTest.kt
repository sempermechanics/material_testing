package com.indicvision.semper.results

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.CaptureNoiseFloor
import com.indicvision.semper.report.AnalysisCsvWriter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The `#` preamble and point header of the analysis CSV.
 */
class AnalysisCsvPreambleTest {

    private fun translatedGrid(u: Float = 1.5f, v: Float = -2.25f): FloatArray {
        val data = FloatArray(10 * 10 * DicResult.STRIDE)
        var i = 0
        for (row in 0 until 10) {
            for (col in 0 until 10) {
                data[i + DicResult.IDX_X] = col * 20f
                data[i + DicResult.IDX_Y] = row * 20f
                data[i + DicResult.IDX_U] = u
                data[i + DicResult.IDX_V] = v
                data[i + DicResult.IDX_EXX] = 0.0001f
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
            captureFloor = null,
        )
        AnalysisCsvWriter.write(out, sweep = false, frames, metadata)
        val text = out.readText()
        assertTrue(text.contains("# semper_csv_version,1\n"))
        assertTrue(text.contains("# reference,ref.jpg\n"))
        assertTrue(text.contains("# roi_x,10\n"))
        assertTrue(text.contains("# noise_floor_mε,\n"))
        assertTrue(text.contains("# field_stats\n"))
        assertTrue(text.contains("# frame_a.jpg,41,5,15,U,"))
        assertTrue(text.contains("\nimage,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd\n"))
        assertTrue(text.contains("\nframe_a.jpg,0,0,0.1,0.2,"))
        assertTrue(!text.lines().any { !it.startsWith("#") && it.contains("noise_floor_mε,shift_u_px") })
    }

    @Test
    fun `recorded csv adds floor and motion columns to the point header and rows`() {
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
            captureFloor = CaptureNoiseFloor(
                microstrain = 237.0,
                vsgPx = 15.0,
                sigmaPx = 0.0025,
                frames = 5,
                exceeded = false,
                overridden = false,
            ),
        )
        AnalysisCsvWriter.write(out, sweep = false, frames, metadata)
        val text = out.readText()
        assertTrue(text.contains("# noise_floor_mε,0.23700\n"))
        assertTrue(
            text.contains(
                "\nimage,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd," +
                    "noise_floor_mε,shift_u_px,shift_v_px,shift_rot_deg\n",
            ),
        )
        assertTrue(text.contains("frame_a.jpg,0,0,1.5,-2.25,"))
        assertTrue(text.contains(",0.23700,1.5000,-2.2500,"))
    }
}
