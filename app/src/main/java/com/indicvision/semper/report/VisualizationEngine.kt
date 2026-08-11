// Heatmap rendering / colour-mapping: literal colour stops, grid math and long
// interpolation loops are inherent to the pixel work and read clearest inline,
// so the structural and magic-number rules are suppressed for this whole file.
@file:Suppress(
    "MagicNumber",
    "ComplexCondition",
    "LongMethod",
    "CyclomaticComplexMethod",
    "LongParameterList",
    "NestedBlockDepth",
)

package com.indicvision.semper.report

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.indicvision.semper.DicResult
import kotlin.math.max

/**
 * Turns a full-field result array into heatmap bitmaps: grid interpolation,
 * percentile-based color scaling, and the jet colormap shared by the on-screen
 * viewer and the PDF report.
 */
object VisualizationEngine {

    /** Longest-edge cap for on-screen scrub heatmaps (export paths omit this). */
    const val DISPLAY_MAX_EDGE = 1080

    /**
     * Longest-edge cap for report/upload compositing. The PDF and cloud heatmaps
     * are downscaled to 600 px wide by [ReportBuilder.compressForPdf] anyway, so
     * this is far above the visible output — it exists purely to stop the
     * intermediate full-resolution ARGB_8888 bitmaps from OOMing on large
     * (e.g. 26 MP) references.
     */
    const val REPORT_MAX_EDGE = 1280

    /**
     * Palette slot for "no correlated data here" — transparent on screen, the
     * animation's background colour in a GIF. It costs the colour ramp its top
     * entry (values map to 0..[LAST_COLOR]), which is one 255th of the scale and
     * buys a single render path shared by the viewer, the report and the GIF.
     */
    const val TRANSPARENT_INDEX = 255
    private const val LAST_COLOR = TRANSPARENT_INDEX - 1

    /**
     * One byte per pixel, each an index into [JET_LUT] or [TRANSPARENT_INDEX],
     * with the value range the colours were mapped against.
     */
    class IndexPlane(
        val indices: ByteArray,
        val width: Int,
        val height: Int,
        val min: Float,
        val max: Float,
    )

    /**
     * The jet ramp as a GIF global colour table: [TRANSPARENT_INDEX] takes
     * [background], every other slot is the colour the viewer would draw.
     */
    fun gifPalette(background: Int): IntArray =
        IntArray(JET_LUT.size) { if (it == TRANSPARENT_INDEX) background else JET_LUT[it] }

    // PRECOMPUTED LOOKUP TABLE: Jet Colormap (256 colors). Built on first use so
    // the value-range helpers stay callable without an Android graphics stack.
    private val JET_LUT: IntArray by lazy {
        IntArray(256) { i ->
            val v = i / 255.0f
            val r = (clamp(minOf(4f * v - 1.5f, -4f * v + 4.5f)) * 255).toInt()
            val g = (clamp(minOf(4f * v - 0.5f, -4f * v + 3.5f)) * 255).toInt()
            val b = (clamp(minOf(4f * v + 0.5f, -4f * v + 2.5f)) * 255).toInt()
            Color.rgb(r, g, b)
        }
    }

    private fun clamp(v: Float) = v.coerceIn(0f, 1f)

    /**
     * Robust percentile clamping, aligned with the Max/Min button: sorts and clamps
     * the first [count] entries of [values] in place. `FloatArray.sort` uses the same
     * total order as `List<Float>.sort()`, so the p02/p98 picks are identical to the
     * boxed collector this replaced.
     */
    private fun computeSigmaClampedRange(values: FloatArray, count: Int, valIndex: Int): Pair<Float, Float> {
        if (count == 0) return Pair(0f, 1f)

        values.sort(0, count)

        val p02 = values[(count * 0.02).toInt().coerceIn(0, count - 1)]
        val p98 = values[(count * 0.98).toInt().coerceIn(0, count - 1)]

        return clampSpan(p02, p98, valIndex)
    }

    /** Shared min-span floor for [computeSigmaClampedRange]. */
    private fun clampSpan(p02: Float, p98: Float, valIndex: Int): Pair<Float, Float> {
        var finalMin = p02
        var finalMax = p98

        // 3. Minimum span floor to prevent the colors from glitching on completely flat/zero fields
        val minSpan = if (valIndex > 3) 0.0001f else 0.01f // 0.1mε or 0.01px
        if ((finalMax - finalMin) < minSpan) {
            val mid = (finalMax + finalMin) / 2f
            finalMin = mid - (minSpan / 2f)
            finalMax = mid + (minSpan / 2f)
        }

        return Pair(finalMin, finalMax)
    }

    /**
     * The displayed value range of several fields at once, in one pass over the
     * points — the same percentile-clamped bounds [generateHeatmap] would pick
     * for each. Null for a field with no correlated points.
     *
     * The summary animation needs every field's range across every frame before
     * it can render anything; decoding each frame once and asking for all five
     * ranges together keeps that pre-pass to a single walk of the data.
     */
    fun valueRanges(data: FloatArray, valIndices: IntArray): Map<Int, Pair<Float, Float>?> {
        // One primitive column per field, holding the same values in the same order as
        // the boxed MutableList<Float> collectors this replaced — so the sort and the
        // p02/p98 pick below are bit-identical. Five boxed columns cost ~20 B/value
        // (~100 MB at n=1M); these cost 4 B/value and allocate nothing per point.
        val pointCount = data.size / DicResult.STRIDE
        val columns = Array(valIndices.size) { FloatArray(pointCount) }
        var count = 0
        for (i in data.indices step DicResult.STRIDE) {
            if (!DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) continue
            for (c in valIndices.indices) {
                columns[c][count] = data[i + valIndices[c]]
            }
            count++
        }
        // Every column has the same accepted-point count, so one emptiness test covers all.
        val ranges = LinkedHashMap<Int, Pair<Float, Float>?>(valIndices.size)
        for (c in valIndices.indices) {
            val valIndex = valIndices[c]
            ranges[valIndex] = if (count == 0) null else computeSigmaClampedRange(columns[c], count, valIndex)
        }
        return ranges
    }

    /**
     * @param maxLongEdge when set and smaller than the image's longest edge, the
     *   bitmap is generated at display scale (viewer scrub). Pass null / omit for
     *   full-resolution PDF and share export.
     */
    fun generateHeatmap(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null, // Optional Custom Bounds
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): Triple<Bitmap, Float, Float> {
        val plane = generateHeatmapIndices(data, imgW, imgH, valIndex, step, customMin, customMax, maxLongEdge)
        val bitmap = createBitmap(plane.width, plane.height, Bitmap.Config.ARGB_8888)
        // Expand one row at a time into a reused buffer instead of materialising a
        // full-image IntArray next to the bitmap: peak goes from 8 bytes/px to
        // 4 bytes/px + one row. Same pixels, written in the same order.
        val row = IntArray(plane.width)
        for (y in 0 until plane.height) {
            val rowStart = y * plane.width
            for (x in 0 until plane.width) {
                val index = plane.indices[rowStart + x].toInt() and 0xFF
                row[x] = if (index == TRANSPARENT_INDEX) 0 else JET_LUT[index]
            }
            bitmap.setPixels(row, 0, plane.width, 0, y, plane.width, 1)
        }
        return Triple(bitmap, plane.min, plane.max)
    }

    /**
     * The same render as [generateHeatmap], stopping one step earlier: one byte
     * per pixel, holding an index into [JET_LUT] — or [TRANSPARENT_INDEX] where
     * no correlated data covers the pixel.
     *
     * The GIF summary animation consumes this directly, so its colours are the
     * viewer's colours by construction rather than by quantisation. Both callers
     * share this one implementation of the interpolation.
     */
    fun generateHeatmapIndices(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): IndexPlane {
        val longest = max(imgW, imgH).coerceAtLeast(1)
        val scale = if (maxLongEdge != null && longest > maxLongEdge) {
            maxLongEdge.toFloat() / longest
        } else {
            1f
        }
        val outW = (imgW * scale).toInt().coerceAtLeast(1)
        val outH = (imgH * scale).toInt().coerceAtLeast(1)

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        // Primitive collector (same values, same order as the previous List<Float>) so
        // the sort + percentile pick in computeSigmaClampedRange is bit-identical.
        val validValues = FloatArray(data.size / DicResult.STRIDE)
        var validCount = 0

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr)) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val v = data[i + valIndex]

                validValues[validCount++] = v
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (validCount == 0) {
            return IndexPlane(ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }, outW, outH, 0f, 0f)
        }

        // THE SCALING LOGIC: Use Custom Bounds if provided, else use Mean ± 3σ Statistical Clamping
        val minV: Float
        val maxV: Float
        if (customMin != null && customMax != null) {
            minV = customMin
            maxV = customMax
        } else {
            val bounds = computeSigmaClampedRange(validValues, validCount, valIndex)
            minV = bounds.first
            maxV = bounds.second
        }

        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        val plane = ByteArray(outW * outH) { TRANSPARENT_INDEX.toByte() }
        val cols = ((maxX - minX) / step) + 1
        val rows = ((maxY - minY) / step) + 1
        val grid = FloatArray(cols * rows) { Float.NaN }

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr)) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val c = (x - minX) / step
                val r = (y - minY) / step
                if (c in 0 until cols && r in 0 until rows) {
                    grid[r * cols + c] = data[i + valIndex]
                }
            }
        }

        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val v00 = grid[r * cols + c]
                val v10 = grid[r * cols + (c + 1)]
                val v01 = grid[(r + 1) * cols + c]
                val v11 = grid[(r + 1) * cols + (c + 1)]

                if (!v00.isNaN() && !v10.isNaN() && !v01.isNaN() && !v11.isNaN()) {
                    val x0 = minX + c * step
                    val y0 = minY + r * step
                    val x1 = x0 + step
                    val y1 = y0 + step

                    val ox0 = (x0 * scale).toInt().coerceIn(0, outW)
                    val oy0 = (y0 * scale).toInt().coerceIn(0, outH)
                    val ox1 = (x1 * scale).toInt().coerceIn(0, outW)
                    val oy1 = (y1 * scale).toInt().coerceIn(0, outH)
                    val dw = (ox1 - ox0).coerceAtLeast(1)
                    val dh = (oy1 - oy0).coerceAtLeast(1)

                    for (oy in oy0 until oy1) {
                        val wy = (oy - oy0).toFloat() / dh
                        val rowOffset = oy * outW
                        val leftEdgeV = v00 + wy * (v01 - v00)
                        val rightEdgeV = v10 + wy * (v11 - v10)

                        for (ox in ox0 until ox1) {
                            val wx = (ox - ox0).toFloat() / dw
                            val v = leftEdgeV + wx * (rightEdgeV - leftEdgeV)
                            val norm = ((v.coerceIn(minV, maxV) - minV) / range * LAST_COLOR).toInt()
                            plane[rowOffset + ox] = norm.coerceIn(0, LAST_COLOR).toByte()
                        }
                    }
                }
            }
        }

        return IndexPlane(plane, outW, outH, minV, maxV)
    }
}
