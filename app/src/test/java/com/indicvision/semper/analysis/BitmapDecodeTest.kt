@file:Suppress("MagicNumber")

package com.indicvision.semper.analysis

import com.indicvision.semper.imaging.BitmapDecode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapDecodeTest {

    @Test
    fun `returns 1 for image smaller than request`() {
        assertEquals(1, BitmapDecode.calculateInSampleSize(100, 80, 500, 500))
    }

    @Test
    fun `returns power-of-two sample for large image`() {
        val sample = BitmapDecode.calculateInSampleSize(4000, 3000, 500, 500)
        assertTrue("expected sample ≥ 4, got $sample", sample >= 4)
        assertEquals(0, sample and (sample - 1))
    }

    @Test
    fun `returns 1 for zero or negative dimensions`() {
        assertEquals(1, BitmapDecode.calculateInSampleSize(0, 100, 500, 500))
        assertEquals(1, BitmapDecode.calculateInSampleSize(100, -1, 500, 500))
        assertEquals(1, BitmapDecode.calculateInSampleSize(-10, -10, 500, 500))
    }

    @Test
    fun `PREVIEW_MAX_EDGE equals 1000`() {
        assertEquals(1000, BitmapDecode.PREVIEW_MAX_EDGE)
    }
}
