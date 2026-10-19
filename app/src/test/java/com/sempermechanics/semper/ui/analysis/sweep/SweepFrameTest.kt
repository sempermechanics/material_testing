package com.sempermechanics.semper.ui.analysis.sweep

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The frame a sweep solves against: the middle until one is picked, a picked
 * one while it exists, and the saved state's `Int` (-1 for the middle) both ways.
 */
class SweepFrameTest {

    @Test
    fun `the middle of the sequence is 0-based n over 2, and 0 with no frames`() {
        assertEquals(2, SweepFrame.Middle.resolve(5))
        assertEquals(1, SweepFrame.Middle.resolve(2))
        assertEquals(0, SweepFrame.Middle.resolve(1))
        assertEquals(0, SweepFrame.Middle.resolve(0))
    }

    @Test
    fun `a picked frame is kept while it exists and the middle stands in once it does not`() {
        assertEquals(4, SweepFrame.At(4).resolve(5))
        assertEquals(1, SweepFrame.At(4).resolve(3))
        assertEquals(0, SweepFrame.At(3).resolve(0))
    }

    @Test
    fun `the saved state keeps -1 for the middle and the index for a picked frame`() {
        assertEquals(-1, SweepFrame.Middle.savedIndex)
        assertEquals(7, SweepFrame.At(7).savedIndex)
        assertEquals(SweepFrame.Middle, SweepFrame.fromSaved(-1))
        assertEquals(SweepFrame.At(0), SweepFrame.fromSaved(0))
        assertEquals(SweepFrame.At(7), SweepFrame.fromSaved(SweepFrame.At(7).savedIndex))
    }
}
