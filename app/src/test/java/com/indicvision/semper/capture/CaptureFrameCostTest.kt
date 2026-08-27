package com.indicvision.semper.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.ui.capture.CameraCapabilities
import com.indicvision.semper.ui.capture.CaptureCalibration
import com.indicvision.semper.ui.capture.CaptureFrameCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CaptureFrameCostTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val full = CameraCapabilities.Resolution(4080, 3072)
    private val small = CameraCapabilities.Resolution(1280, 720)

    private fun caps(floors: Map<CameraCapabilities.Resolution, Long>) =
        CameraCapabilities.Info(
            cameraId = "0",
            yuvSizes = listOf(full, small),
            minFrameMs = floors,
            previewSizes = listOf(small),
        )

    @Test
    fun `a sensor slower than the encoder sets the pace`() {
        // A full-sensor read that takes 2s cannot be beaten by a fast encode.
        val cost = CaptureFrameCost.perFrameMs(context, caps(mapOf(full to 2_000L)), full)
        assertEquals(2_000L, cost)
    }

    @Test
    fun `an encoder slower than the sensor sets the pace`() {
        CaptureCalibration.record(context, full.width, full.height, 3_000L)
        val encodeOnly = CaptureCalibration.estimateFrameMs(context, full.width, full.height)

        val cost = CaptureFrameCost.perFrameMs(context, caps(mapOf(full to 40L)), full)

        assertEquals(encodeOnly, cost)
    }

    @Test
    fun `a camera that reports no floor still costs the measured encode`() {
        val cost = CaptureFrameCost.perFrameMs(context, caps(emptyMap()), full)
        assertEquals(CaptureCalibration.estimateFrameMs(context, full.width, full.height), cost)
    }

    @Test
    fun `the floor is per resolution, not per device`() {
        // The point of reading it off the stream configuration map: the same
        // camera reads a binned frame out faster than a full-sensor one.
        val c = caps(mapOf(full to 2_000L, small to 33L))

        val big = CaptureFrameCost.perFrameMs(context, c, full)
        val little = CaptureFrameCost.perFrameMs(context, c, small)

        assertTrue("big=$big little=$little", little < big)
    }

    @Test
    fun `never returns a zero budget to divide by`() {
        val cost = CaptureFrameCost.perFrameMs(context, caps(mapOf(full to 0L)), full)
        assertTrue("cost was $cost", cost >= 1L)
    }
}
