package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CaptureOrientation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOrientationTest {

    private companion object {
        const val EPS = 1e-4f

        /** What almost every phone's back camera reports. */
        const val TYPICAL_SENSOR = 90
    }

    @Test
    fun `a portrait phone rotates the sensor buffer by the sensor's own mounting`() {
        assertEquals(90, CaptureOrientation.uprightRotation(TYPICAL_SENSOR, displayRotationDegrees = 0))
    }

    @Test
    fun `a display rotation cancels part of the sensor's`() {
        // Turning the device so the display is a quarter turn from natural
        // brings the sensor rows back in line with the viewer.
        assertEquals(0, CaptureOrientation.uprightRotation(TYPICAL_SENSOR, displayRotationDegrees = 90))
        assertEquals(270, CaptureOrientation.uprightRotation(TYPICAL_SENSOR, displayRotationDegrees = 180))
        assertEquals(180, CaptureOrientation.uprightRotation(TYPICAL_SENSOR, displayRotationDegrees = 270))
    }

    @Test
    fun `a naturally-landscape tablet forced to portrait is not left sideways`() {
        // Sensor mounted with the natural landscape orientation, and the
        // portrait lock showing up as a display rotation. Assuming zero here
        // is what leaves tablets sideways.
        assertEquals(270, CaptureOrientation.uprightRotation(0, displayRotationDegrees = 90))
    }

    @Test
    fun `a front sensor turns the other way`() {
        assertEquals(180, CaptureOrientation.uprightRotation(90, 90, frontFacing = true))
        assertEquals(90, CaptureOrientation.uprightRotation(90, 0, frontFacing = true))
    }

    @Test
    fun `the result is always a quarter turn in range`() {
        for (sensor in intArrayOf(0, 90, 180, 270)) {
            for (display in intArrayOf(0, 90, 180, 270)) {
                val rotation = CaptureOrientation.uprightRotation(sensor, display)
                assertTrue("$sensor/$display gave $rotation", rotation in intArrayOf(0, 90, 180, 270).toList())
            }
        }
    }

    @Test
    fun `surface rotation constants become degrees`() {
        assertEquals(0, CaptureOrientation.degreesForSurfaceRotation(0))
        assertEquals(90, CaptureOrientation.degreesForSurfaceRotation(1))
        assertEquals(180, CaptureOrientation.degreesForSurfaceRotation(2))
        assertEquals(270, CaptureOrientation.degreesForSurfaceRotation(3))
    }

    @Test
    fun `a nonsense angle is rounded to a quarter turn instead of trusted`() {
        assertEquals(90, CaptureOrientation.quarterTurn(89))
        assertEquals(0, CaptureOrientation.quarterTurn(350))
        assertEquals(0, CaptureOrientation.quarterTurn(360))
        assertEquals(270, CaptureOrientation.quarterTurn(-90))
        assertEquals(90, CaptureOrientation.quarterTurn(450))
    }

    @Test
    fun `only the quarter turns swap width and height`() {
        assertTrue(CaptureOrientation.swapsAxes(90))
        assertTrue(CaptureOrientation.swapsAxes(270))
        assertFalse(CaptureOrientation.swapsAxes(0))
        assertFalse(CaptureOrientation.swapsAxes(180))
    }

    @Test
    fun `the preview fits inside the view rather than filling it`() {
        // 4:3 buffer turned upright inside a tall portrait view: it has to be
        // width-limited, with bars top and bottom, so nothing is hidden.
        val scale = CaptureOrientation.previewFitScale(
            viewW = 1080,
            viewH = 2400,
            bufW = 1280,
            bufH = 960,
            rotationDegrees = 90,
        )
        assertEquals(1080f / 960f, scale, EPS)
        assertTrue("content must not overflow the view", 1280 * scale <= 2400f)
    }

    @Test
    fun `an unrotated buffer fits by whichever axis binds`() {
        assertEquals(
            0.5f,
            CaptureOrientation.previewFitScale(640, 1000, 1280, 960, rotationDegrees = 0),
            EPS,
        )
        assertEquals(
            0.5f,
            CaptureOrientation.previewFitScale(2000, 480, 1280, 960, rotationDegrees = 0),
            EPS,
        )
    }

    @Test
    fun `a view or buffer with no size does not divide by zero`() {
        assertEquals(1f, CaptureOrientation.previewFitScale(0, 100, 100, 100, 90), EPS)
        assertEquals(1f, CaptureOrientation.previewFitScale(100, 100, 100, 0, 90), EPS)
    }
}
