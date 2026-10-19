@file:Suppress("MagicNumber")

package com.sempermechanics.semper.data.mechanical

import com.sempermechanics.semper.report.BeamDeflection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one scale-and-bias both hand-entered corrections are made of, and its typed-number parser. */
class LinearCorrectionTest {

    @Test
    fun `apply and remove are inverses`() {
        val c = LinearCorrection(scale = 1.05f, bias = -0.12f)

        assertEquals(1.05f * 2f - 0.12f, c.apply(2f), 1e-6f)
        assertEquals(2f, c.remove(c.apply(2f)), 1e-6f)
        assertTrue(LinearCorrection.NONE.isNone)
    }

    @Test
    fun `typed numbers take a decimal comma and a blank default`() {
        assertEquals(1.25f, LinearCorrection.parseScale("1,25")!!, 0f)
        assertEquals(1f, LinearCorrection.parseScale("  ")!!, 0f)
        assertNull(LinearCorrection.parseScale("0"))
        assertNull(LinearCorrection.parseScale("-2"))
        assertEquals(-0.5f, LinearCorrection.parseBias("-0,5")!!, 0f)
        assertEquals(0f, LinearCorrection.parseBias("")!!, 0f)
        assertNull(LinearCorrection.parseBias("abc"))
        assertNull(LinearCorrection.parseBias("NaN"))
    }

    @Test
    fun `both corrections parse alike`() {
        listOf("1,5", "", "x", "0", "-1").forEach { text ->
            assertEquals(CurveCorrection.parseScale(text), BeamDeflection.Correction.parseScale(text))
            assertEquals(CurveCorrection.parseBias(text), BeamDeflection.Correction.parseBias(text))
        }
    }
}
