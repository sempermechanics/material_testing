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
    fun `unknown free RAM drops everything above 2K and keeps the rest in order`() {
        val kept = CameraCapabilities.sustainableCeiling(phoneCatalogue, availRamBytes = 0L)
        assertEquals(
            listOf(Resolution(2048, 1536), Resolution(1920, 1080), Resolution(1280, 720)),
            kept,
        )
    }

    @Test
    fun `judges portrait sizes by their long edge too`() {
        // A 3072x4080 portrait frame is the same frame rotated; the cap has to
        // see it that way or it sails straight through.
        val kept = CameraCapabilities.sustainableCeiling(
            listOf(Resolution(1536, 2048), Resolution(3072, 4080)),
            availRamBytes = 0L,
        )
        assertEquals(listOf(Resolution(1536, 2048)), kept)
    }

    @Test
    fun `never returns an empty picker`() {
        // A camera whose smallest offering is already over the cap: one
        // over-sized choice beats no choice at all.
        val kept = CameraCapabilities.sustainableCeiling(
            listOf(Resolution(4080, 3072), Resolution(3264, 2448)),
            availRamBytes = 0L,
        )
        assertEquals(listOf(Resolution(3264, 2448)), kept)
    }

    @Test
    fun `an already-small catalogue passes through untouched`() {
        val small = listOf(Resolution(1920, 1080), Resolution(1280, 720))
        assertEquals(small, CameraCapabilities.sustainableCeiling(small, availRamBytes = 0L))
    }

    @Test
    fun `the floor is 2K`() {
        assertEquals(2048, CameraCapabilities.CAPTURE_MAX_LONG_EDGE)
        assertTrue(
            CameraCapabilities.sustainableCeiling(phoneCatalogue, availRamBytes = 0L)
                .all { maxOf(it.width, it.height) <= CameraCapabilities.CAPTURE_MAX_LONG_EDGE },
        )
    }

    @Test
    fun `a phone with room to spare is offered the larger sizes too`() {
        // 4080x3072 needs 8 * 4080 * 3072 =~ 100MB of working buffer; a
        // generous free-RAM figure clears the 25% budget comfortably.
        val kept = CameraCapabilities.sustainableCeiling(phoneCatalogue, availRamBytes = 2_000_000_000L)
        assertTrue(Resolution(4080, 3072) in kept)
        // The known-good floor is never dropped, only added to.
        assertTrue(Resolution(2048, 1536) in kept)
    }

    @Test
    fun `a phone with little free RAM stays at the floor`() {
        val kept = CameraCapabilities.sustainableCeiling(phoneCatalogue, availRamBytes = 50_000_000L)
        assertEquals(
            listOf(Resolution(2048, 1536), Resolution(1920, 1080), Resolution(1280, 720)),
            kept,
        )
    }

    @Test
    fun `4x3 sizes pass, 16x9 and square do not`() {
        val mixed = listOf(
            Resolution(4032, 3024), // 4:3
            Resolution(3840, 2160), // 16:9
            Resolution(3024, 3024), // square
        )
        assertEquals(listOf(Resolution(4032, 3024)), CameraCapabilities.preferFourByThree(mixed))
    }

    @Test
    fun `a device with no 4x3 output at all still offers something`() {
        val noFourThree = listOf(Resolution(3840, 2160), Resolution(1920, 1080))
        assertEquals(noFourThree, CameraCapabilities.preferFourByThree(noFourThree))
    }
}
