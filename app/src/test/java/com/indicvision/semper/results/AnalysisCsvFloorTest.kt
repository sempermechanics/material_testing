package com.indicvision.semper.results

import com.indicvision.semper.data.CaptureNoiseFloor
import com.indicvision.semper.report.AnalysisCsvWriter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The noise-floor columns of the analysis CSV.
 *
 * The distinction these exist to protect is the one between "measured, and it
 * was fine" and "never measured": a reader filtering this file down to a handful
 * of points has to be able to tell those apart from the row alone.
 */
class AnalysisCsvFloorTest {

    @Test
    fun `a measured floor writes its value, its gauge and its verdict`() {
        val columns = AnalysisCsvWriter.floorColumns(
            CaptureNoiseFloor(
                microstrain = 8_700.0,
                vsgPx = 15.0,
                sigmaPx = 0.092,
                frames = 5,
                exceeded = true,
                overridden = true,
            ),
        )
        assertEquals("8700,15,1,", columns)
    }

    @Test
    fun `a floor within limits writes a zero verdict, not a missing one`() {
        val columns = AnalysisCsvWriter.floorColumns(
            CaptureNoiseFloor(
                microstrain = 237.0,
                vsgPx = 15.0,
                sigmaPx = 0.0025,
                frames = 5,
                exceeded = false,
                overridden = false,
            ),
        )
        assertEquals("237,15,0,", columns)
    }

    @Test
    fun `an unmeasured floor writes empty fields, never zeros`() {
        // A zero here would read as a perfect camera to anyone who did not know
        // the column can be absent. Imported frames have no burst behind them.
        assertEquals(",,,", AnalysisCsvWriter.floorColumns(null))
    }

    @Test
    fun `the header keeps its shape whether or not a floor was measured`() {
        // A header that changes between exports breaks any script written
        // against a previous file, which is a worse failure than empty fields.
        assertEquals(
            AnalysisCsvWriter.floorColumns(null).count { it == ',' },
            AnalysisCsvWriter.floorColumns(
                CaptureNoiseFloor(1.0, 15.0, 0.001, 5, exceeded = false, overridden = false),
            ).count { it == ',' },
        )
    }
}
