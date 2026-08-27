package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.GrayPngEncoder
import com.indicvision.semper.ui.capture.LumaAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LumaAccumulatorTest {

    private fun luma(
        bytes: ByteArray,
        w: Int,
        h: Int,
        rowStride: Int = w,
        pixelStride: Int = 1,
    ) = GrayPngEncoder.Luma(bytes, w, h, rowStride, pixelStride)

    @Test
    fun `a single sample averages to itself`() {
        val acc = LumaAccumulator(luma(byteArrayOf(10, 20, 30, 40), 2, 2))
        assertEquals(1, acc.frames)
        assertEquals(listOf(10, 20, 30, 40), acc.average().bytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun `two samples average to their mean`() {
        val acc = LumaAccumulator(luma(byteArrayOf(0, 10, 20, 30), 2, 2))
        acc.add(luma(byteArrayOf(4, 14, 24, 34), 2, 2))
        assertEquals(2, acc.frames)
        assertEquals(listOf(2, 12, 22, 32), acc.average().bytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun `rounds to the nearest integer rather than always down`() {
        // Mean of 10 and 11 is 10.5; half-up rounding should land on 11.
        val acc = LumaAccumulator(luma(byteArrayOf(10), 1, 1))
        acc.add(luma(byteArrayOf(11), 1, 1))
        assertEquals(11, acc.average().bytes[0].toInt() and 0xFF)
    }

    @Test
    fun `respects row and pixel stride instead of assuming a packed buffer`() {
        // A 1x1 image with row stride 3 and pixel stride 2 — padding bytes on
        // both axes, the shape a real Camera2 plane can hand back. Only the
        // first byte is the real sample; everything else must be skipped.
        val padded = byteArrayOf(100, 0, 0, 0, 0, 0)
        val strided = luma(padded, w = 1, h = 1, rowStride = 3, pixelStride = 2)
        val acc = LumaAccumulator(strided)
        assertEquals(100, acc.average().bytes[0].toInt() and 0xFF)
    }

    @Test
    fun `averaged output is a tightly packed buffer regardless of input stride`() {
        val padded = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)
        val strided = luma(padded, w = 2, h = 2, rowStride = 4, pixelStride = 2)
        val out = LumaAccumulator(strided).average()
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        assertEquals(4, out.bytes.size)
        assertEquals(listOf(1, 2, 3, 4), out.bytes.map { it.toInt() and 0xFF })
    }

    @Test
    fun `carries the rotation of the first sample through to the average`() {
        val rotated = GrayPngEncoder.Luma(byteArrayOf(1, 2, 3, 4), 2, 2, rowStride = 2, rotationDegrees = 90)
        val acc = LumaAccumulator(rotated)
        assertEquals(90, acc.average().rotationDegrees)
    }

    @Test
    fun `rejects a sample whose size does not match the first`() {
        val acc = LumaAccumulator(luma(byteArrayOf(1, 2, 3, 4), 2, 2))
        assertThrows(IllegalArgumentException::class.java) {
            acc.add(luma(byteArrayOf(1, 2, 3, 4, 5, 6), 3, 2))
        }
    }
}
