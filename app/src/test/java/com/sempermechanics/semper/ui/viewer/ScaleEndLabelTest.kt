package com.sempermechanics.semper.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The colour scale's end labels on screen carry three significant figures
 * (readiness AE1V-021): "≥ 71.4 px", not "≥ 71.42400 px".
 */
class ScaleEndLabelTest {

    @Test
    fun `an end label keeps three significant figures`() {
        assertEquals("71.4", ScaleEndLabel.format(71.424f))
        assertEquals("-71.4", ScaleEndLabel.format(-71.424f))
        assertEquals("-2.13", ScaleEndLabel.format(-2.13f))
        assertEquals("156", ScaleEndLabel.format(156.2f))
        assertEquals("0.00123", ScaleEndLabel.format(0.0012345f))
    }

    @Test
    fun `very large and very small ends switch to scientific form`() {
        assertEquals("1.23e+04", ScaleEndLabel.format(12345f))
        assertEquals("4.56e-05", ScaleEndLabel.format(0.0000456f))
    }

    @Test
    fun `zero never reads as minus zero`() {
        assertEquals("0.00", ScaleEndLabel.format(0f))
        assertEquals("0.00", ScaleEndLabel.format(-0f))
    }
}
