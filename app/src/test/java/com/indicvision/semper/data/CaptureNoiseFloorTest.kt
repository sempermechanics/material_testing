package com.indicvision.semper.data

import com.indicvision.semper.ui.analysis.NoiseFloorStats
import com.indicvision.semper.ui.capture.toCaptureNoiseFloor
import com.indicvision.semper.ui.capture.NoiseFloorGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor has to survive two hops it does not control — an Intent between two
 * activities, and the session index on disk — and arrive intact months later.
 * These cover both, plus the one thing it must never do: claim a floor it does
 * not have.
 */
class CaptureNoiseFloorTest {

    private fun floor(
        microstrain: Double = 8_700.0,
        exceeded: Boolean = true,
        overridden: Boolean = false,
    ) = CaptureNoiseFloor(
        microstrain = microstrain,
        vsgPx = 15.0,
        sigmaPx = 0.092,
        frames = 5,
        exceeded = exceeded,
        overridden = overridden,
    )

    @Test
    fun `a floor survives the trip through the hand-off intact`() {
        val original = floor(overridden = true)
        assertEquals(original, CaptureNoiseFloor.decode(original.encode()))
    }

    @Test
    fun `an unreadable payload comes back as no floor, not as an exception`() {
        // A hand-off that lost its floor is a session with no floor recorded --
        // the same state every imported analysis is in. Throwing here would
        // trade a missing note for a lost run.
        assertNull(CaptureNoiseFloor.decode("{not json"))
        assertNull(CaptureNoiseFloor.decode(""))
        assertNull(CaptureNoiseFloor.decode(null))
    }

    @Test
    fun `a floor within limits warns about nothing`() {
        assertNull(floor(microstrain = 240.0, exceeded = false).warning())
    }

    @Test
    fun `a floor over the limit says which strains are not measurement`() {
        val warning = floor().warning()
        assertNotNull(warning)
        assertTrue(warning!!.contains("8.7 mε"))
        assertTrue(warning.contains("noise"))
    }

    @Test
    fun `a run recorded past the floor says so, so an export cannot hide it`() {
        val warning = floor(overridden = true).warning()
        assertTrue(warning!!.startsWith("Recorded past"))
    }

    @Test
    fun `gate result maps into persisted floor metadata`() {
        val sample = NoiseFloorStats.PairSample(
            sigmaU = 0.7,
            sigmaV = 0.7,
            meanU = 0.0,
            meanV = 0.0,
            noiseVariance = 4.0,
            meanIntensity = 128.0,
        )
        val verdict = NoiseFloorStats.evaluate(List(4) { sample }, vsgPx = 100.0)
        val result = NoiseFloorGate.Result(
            verdict = verdict,
            vsgPx = 100.0,
            framesCaptured = verdict.frameCount,
            firstFrameMs = 120L,
        )
        val floor = result.toCaptureNoiseFloor(overridden = true)
        assertEquals(verdict.floorMicrostrain, floor.microstrain, 0.0)
        assertEquals(100.0, floor.vsgPx, 0.0)
        assertEquals(verdict.frameCount, floor.frames)
        assertEquals(verdict.floorExceeded, floor.exceeded)
        assertTrue(floor.overridden)
    }

    @Test
    fun `the detail line carries the gauge the floor was measured at`() {
        // A floor without its gauge length is not a floor, so the two are never
        // formatted apart.
        val detail = floor().detail()
        assertTrue(detail.contains("8.7 mε"))
        assertTrue(detail.contains("15 px gauge"))
        assertTrue(detail.contains("5 frames"))
    }
}
