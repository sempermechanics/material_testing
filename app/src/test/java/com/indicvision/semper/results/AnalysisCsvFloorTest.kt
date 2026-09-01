package com.indicvision.semper.results

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.CaptureNoiseFloor
import com.indicvision.semper.report.AnalysisCsvWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capture-floor and recorded-session suffix columns in the analysis CSV.
 */
class AnalysisCsvFloorTest {

    private val floor = CaptureNoiseFloor(
        microstrain = 8_700.0,
        vsgPx = 15.0,
        sigmaPx = 0.092,
        frames = 5,
        exceeded = true,
        overridden = true,
    )

    @Test
    fun `a measured floor writes millistrain in the row suffix`() {
        assertEquals("8.70000,", AnalysisCsvWriter.floorMillistrainColumn(floor))
    }

    @Test
    fun `an unmeasured floor writes no suffix columns`() {
        assertEquals("", AnalysisCsvWriter.floorMillistrainColumn(null))
        assertEquals("", AnalysisCsvWriter.recordedSuffixColumns(null, null))
    }

    @Test
    fun `recorded suffix includes floor and three motion fields when the fit succeeds`() {
        val data = FloatArray(10 * 10 * DicResult.STRIDE)
        var i = 0
        for (row in 0 until 10) {
            for (col in 0 until 10) {
                data[i + DicResult.IDX_X] = col * 20f
                data[i + DicResult.IDX_Y] = row * 20f
                data[i + DicResult.IDX_U] = 1.5f
                data[i + DicResult.IDX_V] = -2.25f
                data[i + DicResult.IDX_ZNSSD] = 0.05f
                i += DicResult.STRIDE
            }
        }
        val fit = com.indicvision.semper.report.RigidBodyFit.fit(data)
        val suffix = AnalysisCsvWriter.recordedSuffixColumns(floor, fit)
        assertTrue(suffix.startsWith("8.70000,"))
        val parts = suffix.trimEnd(',').split(',')
        assertEquals(4, parts.size)
        assertEquals(1.5, parts[1].toDouble(), 1e-4)
        assertEquals(-2.25, parts[2].toDouble(), 1e-4)
    }

    @Test
    fun `import point header ends at znssd`() {
        assertEquals(
            "image,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd",
            AnalysisCsvWriter.pointHeader(sweep = false, recorded = false),
        )
    }

    @Test
    fun `recorded point header adds floor and motion columns`() {
        val header = AnalysisCsvWriter.pointHeader(sweep = false, recorded = true)
        assertTrue(header.endsWith("noise_floor_mε,shift_u_px,shift_v_px,shift_rot_deg"))
        assertTrue(header.startsWith("image,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd,"))
    }
}
