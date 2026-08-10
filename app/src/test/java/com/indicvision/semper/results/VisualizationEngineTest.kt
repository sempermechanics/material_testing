package com.indicvision.semper.results

import com.indicvision.semper.DicResult
import com.indicvision.semper.report.VisualizationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Bitmap is needed for the ARGB half of the comparison, so this runs under
// Robolectric, pinned to 34 like the other Robolectric tests here.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisualizationEngineTest {

    /** A correlated grid: `step`-spaced points whose value rises left to right. */
    private fun rampField(cols: Int, rows: Int, step: Int): FloatArray {
        val out = FloatArray(cols * rows * DicResult.STRIDE)
        var p = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out[p + DicResult.IDX_X] = (c * step).toFloat()
                out[p + DicResult.IDX_Y] = (r * step).toFloat()
                out[p + DicResult.IDX_EXX] = c.toFloat() / cols
                out[p + DicResult.IDX_ZNSSD] = 0.01f
                p += DicResult.STRIDE
            }
        }
        return out
    }

    @Test
    fun `DISPLAY_MAX_EDGE equals 1080`() {
        assertEquals(1080, VisualizationEngine.DISPLAY_MAX_EDGE)
    }

    @Test
    fun `the index plane and the bitmap are the same render`() {
        val step = 4
        val cols = 12
        val rows = 9
        val w = cols * step
        val h = rows * step
        val data = rampField(cols, rows, step)

        val plane = VisualizationEngine.generateHeatmapIndices(data, w, h, DicResult.IDX_EXX, step)
        val (bitmap, min, max) = VisualizationEngine.generateHeatmap(data, w, h, DicResult.IDX_EXX, step)

        assertEquals(plane.width, bitmap.width)
        assertEquals(plane.height, bitmap.height)
        assertEquals(plane.min, min, 0f)
        assertEquals(plane.max, max, 0f)

        val palette = VisualizationEngine.gifPalette(background = 0x000000)
        var covered = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val index = plane.indices[y * plane.width + x].toInt() and 0xFF
                val argb = bitmap.getPixel(x, y)
                if (index == VisualizationEngine.TRANSPARENT_INDEX) {
                    assertEquals("($x,$y) should be transparent", 0, argb)
                } else {
                    covered++
                    assertEquals("($x,$y)", palette[index] and 0xFFFFFF, argb and 0xFFFFFF)
                }
            }
        }
        assertTrue("the ramp should cover most of the frame, covered=$covered", covered > bitmap.width)
    }

    /** The original boxed sigma-clamp, kept as the parity oracle for the de-boxed path. */
    private fun boxedSigmaClamp(data: FloatArray, valIndex: Int): Pair<Float, Float> {
        val valid = mutableListOf<Float>()
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) valid.add(data[i + valIndex])
            i += DicResult.STRIDE
        }
        valid.sort()
        var mn = valid[(valid.size * 0.02).toInt().coerceIn(0, valid.size - 1)]
        var mx = valid[(valid.size * 0.98).toInt().coerceIn(0, valid.size - 1)]
        val minSpan = if (valIndex > 3) 0.0001f else 0.01f
        if ((mx - mn) < minSpan) {
            val mid = (mx + mn) / 2f
            mn = mid - minSpan / 2f
            mx = mid + minSpan / 2f
        }
        return mn to mx
    }

    @Test
    fun `de-boxed sigma-clamp min max is identical to the boxed oracle`() {
        val step = 4
        val data = rampField(cols = 25, rows = 17, step = step)
        for (valIndex in listOf(DicResult.IDX_EXX, DicResult.IDX_X)) {
            val plane = VisualizationEngine.generateHeatmapIndices(
                data,
                25 * step,
                17 * step,
                valIndex,
                step,
            )
            val (mn, mx) = boxedSigmaClamp(data, valIndex)
            assertEquals("min field=$valIndex", mn, plane.min, 0f)
            assertEquals("max field=$valIndex", mx, plane.max, 0f)
        }
    }

    @Test
    fun `a field with no correlated points is entirely the transparent index`() {
        // Every point rejected: the native engine's invalid sentinel.
        val data = FloatArray(4 * DicResult.STRIDE) { -1f }

        val plane = VisualizationEngine.generateHeatmapIndices(data, 8, 8, DicResult.IDX_EXX, 4)

        assertTrue(
            plane.indices.all { (it.toInt() and 0xFF) == VisualizationEngine.TRANSPARENT_INDEX },
        )
        assertEquals(0f, plane.min, 0f)
        assertEquals(0f, plane.max, 0f)
    }

    @Test
    fun `fixed bounds put the ramp ends at the ends of the colour ramp`() {
        val step = 4
        val cols = 16
        val data = rampField(cols, 4, step)

        val plane = VisualizationEngine.generateHeatmapIndices(
            data,
            cols * step,
            4 * step,
            DicResult.IDX_EXX,
            step,
            customMin = 0f,
            customMax = 1f,
        )

        val used = plane.indices
            .map { it.toInt() and 0xFF }
            .filter { it != VisualizationEngine.TRANSPARENT_INDEX }
        assertEquals("cold end", 0, used.min())
        // Values reach (cols-1)/cols of the range, so the hot end is near but not
        // at the top of the ramp — what matters is that it never exceeds it.
        assertTrue("hot end ${used.max()} above the ramp", used.max() <= VisualizationEngine.TRANSPARENT_INDEX - 1)
        assertTrue("hot end ${used.max()} unexpectedly cold", used.max() > 200)
    }
}
