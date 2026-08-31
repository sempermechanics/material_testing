package com.indicvision.semper.capture

import android.graphics.RectF
import com.indicvision.semper.ui.capture.NoiseFloorGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The ROI has to reach the burst frame pointing the same way the user drew it.
 *
 * Nothing downstream can catch it if it does not: a quarter-turned rect lands
 * on real pixels, so the floor comes back a plausible number measured on a part
 * of the scene nobody chose.
 */
@RunWith(RobolectricTestRunner::class)
class NoiseFloorRoiTest {

    private fun assertRect(expected: RectF, actual: RectF) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
    }

    @Test
    fun `a portrait test shot and a portrait frame need no turn`() {
        val roi = RectF(0.1f, 0.2f, 0.3f, 0.5f)
        assertSame(
            roi,
            NoiseFloorGate.uprightRoi(roi, sourceLandscape = false, 1440, 1920, 90),
        )
    }

    @Test
    fun `a landscape test shot and a landscape frame need no turn`() {
        val roi = RectF(0.1f, 0.2f, 0.3f, 0.5f)
        assertSame(
            roi,
            NoiseFloorGate.uprightRoi(roi, sourceLandscape = true, 1920, 1440, 90),
        )
    }

    @Test
    fun `a sensor-native test shot is turned by the same amount the frames were`() {
        // The corner nearest the top-left of a landscape source lands nearest
        // the top-right after a quarter turn clockwise.
        val roi = RectF(0.1f, 0.2f, 0.3f, 0.5f)
        assertRect(
            RectF(0.5f, 0.1f, 0.8f, 0.3f),
            NoiseFloorGate.uprightRoi(roi, sourceLandscape = true, 1440, 1920, 90),
        )
    }

    @Test
    fun `the opposite mismatch turns the opposite way`() {
        val roi = RectF(0.1f, 0.2f, 0.3f, 0.5f)
        assertRect(
            RectF(0.2f, 0.7f, 0.5f, 0.9f),
            NoiseFloorGate.uprightRoi(roi, sourceLandscape = false, 1920, 1440, 270),
        )
    }

    @Test
    fun `four quarter turns come back to where they started`() {
        val roi = RectF(0.1f, 0.2f, 0.3f, 0.5f)
        var turned = roi
        repeat(4) { turned = NoiseFloorGate.rotateNorm(turned, 90) }
        assertRect(roi, turned)
    }

    @Test
    fun `a half turn is its own inverse`() {
        val roi = RectF(0.1f, 0.2f, 0.3f, 0.5f)
        assertRect(roi, NoiseFloorGate.rotateNorm(NoiseFloorGate.rotateNorm(roi, 180), 180))
    }

    @Test
    fun `no turn leaves the rect alone`() {
        // The values, not the identity: rotateNorm goes through
        // CaptureOrientation.rotatePoint now, so a zero turn maps both corners
        // and rebuilds the rect rather than handing the argument back. Nothing
        // downstream aliases the result, and pinning the object would only
        // forbid that sharing.
        val roi = RectF(0.1f, 0.2f, 0.3f, 0.5f)
        assertRect(roi, NoiseFloorGate.rotateNorm(roi, 0))
    }

    @Test
    fun `a turned rect stays inside the unit square and keeps its area`() {
        // Flush against the bottom edge before the turn, flush against the
        // left edge after it: the turn must not push the rect out of frame.
        val roi = RectF(0.0f, 0.35f, 0.25f, 1.0f)
        val turned = NoiseFloorGate.rotateNorm(roi, 90)
        assertRect(RectF(0.0f, 0.0f, 0.65f, 0.25f), turned)
        assertEquals(
            roi.width() * roi.height(),
            turned.width() * turned.height(),
            TOLERANCE,
        )
    }

    private companion object {
        const val TOLERANCE = 1e-5f
    }
}
