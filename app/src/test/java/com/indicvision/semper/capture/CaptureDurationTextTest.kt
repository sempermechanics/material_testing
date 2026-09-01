package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CaptureDurationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureDurationTextTest {

    @Test
    fun `formats as zero-padded mm ss`() {
        assertEquals("00:01", CaptureDurationText.format(1))
        assertEquals("00:10", CaptureDurationText.format(10))
        assertEquals("01:00", CaptureDurationText.format(60))
        assertEquals("02:30", CaptureDurationText.format(150))
        assertEquals("10:00", CaptureDurationText.format(600))
    }

    @Test
    fun `reads back everything it writes`() {
        for (seconds in CaptureDurationText.MIN_SECONDS..CaptureDurationText.MAX_SECONDS) {
            assertEquals(seconds, CaptureDurationText.parse(CaptureDurationText.format(seconds)))
        }
    }

    @Test
    fun `accepts what someone types on the way to a duration`() {
        // "4" → 4s, "4:" → 4 minutes, "4:5" → 4m05s. None of these should
        // blank the plan while the user is still typing.
        assertEquals(4, CaptureDurationText.parse("4"))
        assertEquals(240, CaptureDurationText.parse("4:"))
        assertEquals(245, CaptureDurationText.parse("4:5"))
        assertEquals(245, CaptureDurationText.parse("04:05"))
    }

    @Test
    fun `typing 10 colon 00 is reachable one keystroke at a time`() {
        // The regression that reformatting-as-you-type would cause: every
        // prefix of "10:00" has to parse to something sane, so the field is
        // never rewritten out from under the caret.
        assertEquals(1, CaptureDurationText.parse("1"))
        assertEquals(10, CaptureDurationText.parse("10"))
        assertEquals(600, CaptureDurationText.parse("10:"))
        assertEquals(600, CaptureDurationText.parse("10:0"))
        assertEquals(600, CaptureDurationText.parse("10:00"))
    }

    @Test
    fun `clamps past ten minutes rather than refusing`() {
        assertEquals(600, CaptureDurationText.parse("99:99"))
        assertEquals(600, CaptureDurationText.parse("20:00"))
        assertEquals(600, CaptureDurationText.clamp(10_000))
    }

    @Test
    fun `clamps a zero or negative duration up to one second`() {
        assertEquals(1, CaptureDurationText.parse("0"))
        assertEquals(1, CaptureDurationText.parse("00:00"))
        assertEquals(1, CaptureDurationText.clamp(0))
        assertEquals(1, CaptureDurationText.clamp(-5))
    }

    @Test
    fun `returns null for text that is not a duration yet`() {
        assertNull(CaptureDurationText.parse(""))
        assertNull(CaptureDurationText.parse("   "))
        assertNull(CaptureDurationText.parse(null))
        assertNull(CaptureDurationText.parse("1:2:3"))
    }

    @Test
    fun `ten minutes is the stated ceiling`() {
        assertEquals(600, CaptureDurationText.MAX_SECONDS)
    }
}
