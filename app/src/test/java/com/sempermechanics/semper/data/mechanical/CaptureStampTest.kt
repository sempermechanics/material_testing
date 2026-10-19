@file:Suppress("MagicNumber")

package com.sempermechanics.semper.data.mechanical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A photo batch's capture times and how precise they are: the precision is
 * whether each photo's EXIF carried `SubSecTimeOriginal`, not whether its
 * time happens to land on a whole second.
 */
class CaptureStampTest {

    private val t0 = 1_790_000_000_000L

    private fun fine(ms: Long) = CaptureStamp(ms, subSeconds = true)

    private fun whole(ms: Long) = CaptureStamp(ms, subSeconds = false)

    private fun unknown(ms: Long) = CaptureStamp(ms, subSeconds = null)

    @Test
    fun `frame times are relative to the reference, in order`() {
        assertEquals(
            listOf(1_500L, 3_000L, -200L),
            CaptureStamp.relativeTimesMs(fine(t0), listOf(fine(t0 + 1_500L), fine(t0 + 3_000L), fine(t0 - 200L))),
        )
    }

    @Test
    fun `no relative times unless the reference and every frame have one`() {
        assertTrue(CaptureStamp.relativeTimesMs(fine(t0), listOf(fine(t0 + 1_000L), null)).isEmpty())
        assertTrue(CaptureStamp.relativeTimesMs(null, listOf(fine(t0 + 1_000L))).isEmpty())
        assertTrue(CaptureStamp.relativeTimesMs(fine(t0), emptyList()).isEmpty())
    }

    @Test
    fun `a whole-second reference makes sub-second frames coarse`() {
        // The reference from a camera that writes no sub-seconds: every frame's
        // time after it is good only to ±1 s, though the frames carry .250.
        assertTrue(CaptureStamp.coarse(whole(t0), listOf(fine(t0 + 1_250L), fine(t0 + 2_250L))))
    }

    @Test
    fun `one frame without sub-seconds makes the batch coarse`() {
        assertTrue(CaptureStamp.coarse(fine(t0 + 120L), listOf(fine(t0 + 1_250L), whole(t0 + 2_000L))))
    }

    @Test
    fun `times with sub-seconds are fine even when they land on a whole second`() {
        // A camera that writes "000": read, so precise, not a whole-second guess.
        assertFalse(CaptureStamp.coarse(fine(t0), listOf(fine(t0 + 1_000L), fine(t0 + 2_000L))))
    }

    @Test
    fun `times saved before the flag keep the whole-second reading`() {
        assertTrue(CaptureStamp.coarse(unknown(t0), listOf(unknown(t0 + 1_000L), unknown(t0 + 2_000L))))
        assertFalse(CaptureStamp.coarse(unknown(t0), listOf(unknown(t0 + 1_000L), unknown(t0 + 2_250L))))
        assertFalse(CaptureStamp.coarse(unknown(t0 + 120L), listOf(unknown(t0 + 1_000L))))
        // A time read without sub-seconds is coarse whatever the others are.
        assertTrue(CaptureStamp.coarse(unknown(t0 + 120L), listOf(whole(t0 + 1_000L))))
    }

    @Test
    fun `no batch to time is never coarse`() {
        assertFalse(CaptureStamp.coarse(null, listOf(whole(t0 + 1_000L))))
        assertFalse(CaptureStamp.coarse(whole(t0), listOf(whole(t0 + 1_000L), null)))
        assertFalse(CaptureStamp.coarse(whole(t0), emptyList()))
    }
}
