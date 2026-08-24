package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CapturePlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapturePlannerTest {

    @Test
    fun `prefers stills when interval covers jpeg stall`() {
        val d = CapturePlanner.chooseMode(
            requestedFps = 2,
            maxDeviceFps = 30,
            jpegStallMs = 120,
            canTakeStills = true,
            canRecordVideo = true,
            stallMarginMs = 80,
        )
        assertEquals(CapturePlanner.Mode.STILLS, d.mode)
        assertEquals(2, d.fps)
        assertEquals("stills_interval_ok", d.reason)
    }

    @Test
    fun `prefers video when still interval is too short`() {
        val d = CapturePlanner.chooseMode(
            requestedFps = 30,
            maxDeviceFps = 60,
            jpegStallMs = 120,
            canTakeStills = true,
            canRecordVideo = true,
            stallMarginMs = 80,
        )
        assertEquals(CapturePlanner.Mode.VIDEO, d.mode)
        assertEquals(30, d.fps)
        assertEquals("interval_too_short_for_stills", d.reason)
    }

    @Test
    fun `clamps stills when video is unavailable`() {
        val d = CapturePlanner.chooseMode(
            requestedFps = 30,
            maxDeviceFps = 60,
            jpegStallMs = 200,
            canTakeStills = true,
            canRecordVideo = false,
            stallMarginMs = 80,
        )
        assertEquals(CapturePlanner.Mode.STILLS, d.mode)
        assertTrue(d.fps <= 3)
        assertEquals("no_video_clamp_stills", d.reason)
    }

    @Test
    fun `clamps requested fps to device max`() {
        val d = CapturePlanner.chooseMode(
            requestedFps = 120,
            maxDeviceFps = 30,
            jpegStallMs = 50,
            canTakeStills = true,
            canRecordVideo = true,
            stallMarginMs = 0,
        )
        assertEquals(30, d.fps)
    }
}
