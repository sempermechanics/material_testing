@file:Suppress("MagicNumber")

package com.indicvision.semper.results

import com.indicvision.semper.DicResult
import com.indicvision.semper.report.ReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportBuilderTest {

    private fun syntheticField(n: Int): FloatArray {
        val data = FloatArray(n * DicResult.STRIDE)
        for (i in 0 until n) {
            val offset = i * DicResult.STRIDE
            data[offset + DicResult.IDX_X] = i.toFloat()
            data[offset + DicResult.IDX_Y] = 0f
            data[offset + DicResult.IDX_U] = i * 0.1f
            data[offset + DicResult.IDX_V] = 0f
            data[offset + DicResult.IDX_EXX] = i * 0.001f
            data[offset + DicResult.IDX_EYY] = 0f
            data[offset + DicResult.IDX_EXY] = 0f
            data[offset + DicResult.IDX_ZNSSD] = 0.01f
        }
        return data
    }

    @Test
    fun `computeFieldExtrema max and min indices are within percentile bounds`() {
        val n = 100
        val data = syntheticField(n)
        val extrema = ReportBuilder.computeFieldExtrema(data, DicResult.IDX_U, absoluteStrainValues = false)
        assertTrue(extrema.maxIdx >= 0)
        assertTrue(extrema.minIdx >= 0)

        val values = (0 until n).map { data[it * DicResult.STRIDE + DicResult.IDX_U] }.sorted()
        val p02 = values[(values.size * 0.02).toInt().coerceIn(0, values.size - 1)]
        val p98 = values[(values.size * 0.98).toInt().coerceIn(0, values.size - 1)]
        val maxVal = data[extrema.maxIdx + DicResult.IDX_U]
        val minVal = data[extrema.minIdx + DicResult.IDX_U]
        assertTrue(maxVal in p02..p98)
        assertTrue(minVal in p02..p98)
    }

    @Test
    fun `empty or all-rejected field returns FieldExtrema -1 -1`() {
        val empty = ReportBuilder.computeFieldExtrema(FloatArray(0), DicResult.IDX_U)
        assertEquals(ReportBuilder.FieldExtrema(-1, -1), empty)

        val rejected = syntheticField(5)
        for (i in 0 until 5) {
            rejected[i * DicResult.STRIDE + DicResult.IDX_ZNSSD] = -1f
        }
        val extrema = ReportBuilder.computeFieldExtrema(rejected, DicResult.IDX_U)
        assertEquals(ReportBuilder.FieldExtrema(-1, -1), extrema)
    }

    @Test
    fun `formatMetric uses scientific notation for very small values`() {
        val formatted = ReportBuilder.formatMetric(1e-5f)
        assertTrue("expected scientific notation, got $formatted", formatted.contains("e", ignoreCase = true))
    }
}
