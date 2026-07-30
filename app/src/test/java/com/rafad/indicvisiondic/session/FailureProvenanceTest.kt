package com.rafad.indicvisiondic.session

import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.ui.analysis.AnalysisRunCodes
import com.rafad.indicvisiondic.ui.analysis.EngineFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an analysis remembers about going wrong.
 *
 * The point of storing these on the record rather than passing them around is
 * that the answer to "why is this one short?" has to survive leaving the screen,
 * a restart, and a cloud round-trip. These pin the shape that makes that work.
 */
class FailureProvenanceTest {

    private fun record(
        stopCode: Int = 0,
        frameCount: Int = 10,
        plannedFrameCount: Int = 0,
        skipCodes: List<Int> = emptyList(),
    ) = SessionRecord(
        id = "s1",
        name = "run",
        createdAt = 0L,
        updatedAt = 0L,
        frameCount = frameCount,
        subset = 41,
        step = 5,
        strainWindow = 15,
        imgW = 100,
        imgH = 100,
        roiX = 0,
        roiY = 0,
        roiW = 100,
        roiH = 100,
        refPath = "ref.png",
        refName = "ref.png",
        sessionDir = "/dir",
        stopCode = stopCode,
        plannedFrameCount = plannedFrameCount,
        sweepSkipCodes = skipCodes,
    )

    @Test
    fun `a clean run is not marked as stopped early`() {
        assertFalse(record().stoppedEarly)
    }

    @Test
    fun `a run that stopped carries why, and what it had planned`() {
        val r = record(
            stopCode = AnalysisRunCodes.ERROR_LOW_CONVERGENCE,
            frameCount = 39,
            plannedFrameCount = 50,
        )

        assertTrue(r.stoppedEarly)
        // 39 solved out of 50 planned is the distinction the Home row draws.
        assertEquals(39, r.frameCount)
        assertEquals(50, r.plannedFrameCount)
    }

    @Test
    fun `the stored code resolves to a reason the user can read`() {
        val r = record(stopCode = AnalysisRunCodes.ERROR_LOW_CONVERGENCE)

        // A resource id, not built text: the record outlives any one locale.
        assertTrue(EngineFailure.shortReasonRes(r.stopCode) != 0)
        assertNotEquals(
            "a stopped run must not read as the generic case",
            EngineFailure.shortReasonRes(0),
            EngineFailure.shortReasonRes(r.stopCode),
        )
    }

    @Test
    fun `skip codes stay aligned with the combinations they explain`() {
        val r = record(
            skipCodes = listOf(
                EngineFailure.ENGINE_ERROR_FEATURES,
                EngineFailure.ENGINE_ERROR_ROI,
            ),
        )

        assertEquals(2, r.sweepSkipCodes.size)
        assertNotEquals(
            "decorrelation and an oversized subset must not read the same",
            EngineFailure.shortReasonRes(r.sweepSkipCodes[0]),
            EngineFailure.shortReasonRes(r.sweepSkipCodes[1]),
        )
    }

    @Test
    fun `records written before these fields existed still load`() {
        // Defaults matter: the store deserialises older JSON without them.
        val old = record(stopCode = 0, plannedFrameCount = 0)

        assertFalse(old.stoppedEarly)
        assertTrue(old.sweepSkipCodes.isEmpty())
    }
}
