@file:Suppress("MagicNumber")

package com.indicvision.semper.results

import com.indicvision.semper.DicResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DicResultDecodeTest {

    private fun bytesForPoints(n: Int, fill: (FloatArray, Int) -> Unit = { _, _ -> }): ByteArray {
        val floats = FloatArray(n * DicResult.STRIDE)
        for (i in 0 until n) {
            fill(floats, i * DicResult.STRIDE)
        }
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        return buf.array()
    }

    @Test
    fun `valid bytes decode successfully with length bytes size over 4`() {
        val bytes = bytesForPoints(3) { data, offset ->
            data[offset + DicResult.IDX_X] = 1f
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        assertEquals(0, bytes.size % 32)
        val decoded = DicResult.decodeDatBytes(bytes)
        assertNotNull(decoded)
        assertEquals(bytes.size / 4, decoded!!.size)
    }

    @Test
    fun `invalid bytes not multiple of 32 return null`() {
        assertNull(DicResult.decodeDatBytes(ByteArray(31)))
        assertNull(DicResult.decodeDatBytes(ByteArray(33)))
    }

    @Test
    fun `empty bytes return null`() {
        assertNull(DicResult.decodeDatBytes(ByteArray(0)))
    }

    @Test
    fun `fieldStats on a synthetic field returns correct max min mean`() {
        val n = 5
        val data = FloatArray(n * DicResult.STRIDE)
        for (i in 0 until n) {
            val offset = i * DicResult.STRIDE
            data[offset + DicResult.IDX_U] = i * 0.1f
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        val stats = DicResult.fieldStats(data, DicResult.IDX_U)
        assertNotNull(stats)
        assertEquals(0.4f, stats!![0], 1e-5f)
        assertEquals(0.0f, stats[1], 1e-5f)
        assertEquals(0.2f, stats[2], 1e-5f)
    }
}
