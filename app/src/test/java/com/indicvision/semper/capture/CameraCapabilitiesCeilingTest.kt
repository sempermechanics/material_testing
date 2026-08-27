package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CameraCapabilities
import com.indicvision.semper.ui.capture.CameraCapabilities.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilitiesCeilingTest {

    private val phoneCatalogue = listOf(
        Resolution(4080, 3072),
        Resolution(3264, 2448),
        Resolution(2592, 1944),
        Resolution(2048, 1536),
        Resolution(1920, 1080),
        Resolution(1280, 720),
    )

    @Test
    fun `drops everything above 2K and keeps the rest in order`() {
        val kept = CameraCapabilities.withinCaptureCeiling(phoneCatalogue)
        assertEquals(
            listOf(Resolution(2048, 1536), Resolution(1920, 1080), Resolution(1280, 720)),
            kept,
        )
    }

    @Test
    fun `judges portrait sizes by their long edge too`() {
        // A 3072x4080 portrait frame is the same frame rotated; the cap has to
        // see it that way or it sails straight through.
        val kept = CameraCapabilities.withinCaptureCeiling(listOf(Resolution(1536, 2048), Resolution(3072, 4080)))
        assertEquals(listOf(Resolution(1536, 2048)), kept)
    }

    @Test
    fun `never returns an empty picker`() {
        // A camera whose smallest offering is already over the cap: one
        // over-sized choice beats no choice at all.
        val kept = CameraCapabilities.withinCaptureCeiling(listOf(Resolution(4080, 3072), Resolution(3264, 2448)))
        assertEquals(listOf(Resolution(3264, 2448)), kept)
    }

    @Test
    fun `an already-small catalogue passes through untouched`() {
        val small = listOf(Resolution(1920, 1080), Resolution(1280, 720))
        assertEquals(small, CameraCapabilities.withinCaptureCeiling(small))
    }

    @Test
    fun `the ceiling is 2K`() {
        assertEquals(2048, CameraCapabilities.CAPTURE_MAX_LONG_EDGE)
        assertTrue(
            CameraCapabilities.withinCaptureCeiling(phoneCatalogue)
                .all { maxOf(it.width, it.height) <= CameraCapabilities.CAPTURE_MAX_LONG_EDGE },
        )
    }
}
