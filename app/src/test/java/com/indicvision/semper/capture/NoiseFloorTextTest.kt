package com.indicvision.semper.capture

import com.indicvision.semper.data.NoiseFloorText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The floor label is the one technical limit the user cannot be shielded from,
 * so the unit it is quoted in has to make its magnitude obvious rather than
 * hide it behind a big number of small units.
 */
class NoiseFloorTextTest {

    @Test
    fun `a small floor reads in microstrain`() {
        assertEquals("42 µε", NoiseFloorText.floorLabel(42.4))
    }

    @Test
    fun `a floor past a millistrain switches unit rather than growing digits`() {
        // "2400 µε" is technically right and reads as a smaller number than it
        // is; the whole point of the label is that its size lands immediately.
        assertEquals("2.4 mε", NoiseFloorText.floorLabel(2_400.0))
    }

    @Test
    fun `the unit switches exactly at one millistrain`() {
        assertEquals("999 µε", NoiseFloorText.floorLabel(999.4))
        assertEquals("1.0 mε", NoiseFloorText.floorLabel(1_000.0))
    }

    @Test
    fun `a floor that could not be measured says so instead of showing zero`() {
        // A floor of "0 µε" would read as a perfect setup, which is the exact
        // opposite of what an unmeasurable burst means.
        assertEquals("—", NoiseFloorText.floorLabel(Double.NaN))
        assertEquals("—", NoiseFloorText.floorLabel(Double.POSITIVE_INFINITY))
        assertEquals("—", NoiseFloorText.floorLabel(-1.0))
    }
}
