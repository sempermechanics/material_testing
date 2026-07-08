package com.rafad.indicvisiondic.report
import android.graphics.Bitmap
import android.graphics.Color
import com.rafad.indicvisiondic.DicResult

/**
 * Turns a full-field result array into heatmap bitmaps: grid interpolation,
 * percentile-based color scaling, and the jet colormap shared by the on-screen
 * viewer and the PDF report.
 */
object VisualizationEngine {

    // PRECOMPUTED LOOKUP TABLE: Jet Colormap (256 colors)
    private val JET_LUT = IntArray(256) { i ->
        val v = i / 255.0f
        val r = (clamp(minOf(4f * v - 1.5f, -4f * v + 4.5f)) * 255).toInt()
        val g = (clamp(minOf(4f * v - 0.5f, -4f * v + 3.5f)) * 255).toInt()
        val b = (clamp(minOf(4f * v + 0.5f, -4f * v + 2.5f)) * 255).toInt()
        Color.rgb(r, g, b)
    }

    private fun clamp(v: Float) = v.coerceIn(0f, 1f)

    // Robust Percentile Clamping (Aligns perfectly with the Max/Min Button)
    private fun computeSigmaClampedRange(values: MutableList<Float>, valIndex: Int): Pair<Float, Float> {
        if (values.isEmpty()) return Pair(0f, 1f)

        // 1. Sort the array to find the true data distribution
        values.sort()

        // 2. Extract the exact 2% and 98% bounds used by your Max/Min UI Button!
        // This ignores wild single-pixel outliers that stretch the color scale.
        val p02 = values[(values.size * 0.02).toInt().coerceIn(0, values.size - 1)]
        val p98 = values[(values.size * 0.98).toInt().coerceIn(0, values.size - 1)]

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

    fun generateHeatmap(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null, // Optional Custom Bounds
        customMax: Float? = null,
    ): Triple<Bitmap, Float, Float> {
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        val validValues = mutableListOf<Float>()

        for (i in data.indices step DicResult.STRIDE) {
            val corr = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isAcceptedPoint(corr)) {
                val x = data[i].toInt()
                val y = data[i + 1].toInt()
                val v = data[i + valIndex]

                validValues.add(v)
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        if (validValues.isEmpty()) {
            return Triple(Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888), 0f, 0f)
        }

        // THE SCALING LOGIC: Use Custom Bounds if provided, else use Mean ± 3σ Statistical Clamping
        val minV: Float
        val maxV: Float
        if (customMin != null && customMax != null) {
            minV = customMin
            maxV = customMax
        } else {
            val bounds = computeSigmaClampedRange(validValues, valIndex)
            minV = bounds.first
            maxV = bounds.second
        }

        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        val pixels = IntArray(imgW * imgH)
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

        val weights = FloatArray(step) { it / step.toFloat() }

        for (r in 0 until rows - 1) {
            for (c in 0 until cols - 1) {
                val v00 = grid[r * cols + c]
                val v10 = grid[r * cols + (c + 1)]
                val v01 = grid[(r + 1) * cols + c]
                val v11 = grid[(r + 1) * cols + (c + 1)]

                if (!v00.isNaN() && !v10.isNaN() && !v01.isNaN() && !v11.isNaN()) {
                    val pxStart = minX + c * step
                    val pyStart = minY + r * step

                    for (py in 0 until step) {
                        val wy = weights[py]
                        val absY = pyStart + py
                        if (absY < 0 || absY >= imgH) continue
                        val rowOffset = absY * imgW

                        val leftEdgeV = v00 + wy * (v01 - v00)
                        val rightEdgeV = v10 + wy * (v11 - v10)

                        for (px in 0 until step) {
                            val absX = pxStart + px
                            if (absX < 0 || absX >= imgW) continue
                            val wx = weights[px]

                            val v = leftEdgeV + wx * (rightEdgeV - leftEdgeV)

                            // Color Mapping naturally clamps to Min/Max bounds!
                            val norm = ((v.coerceIn(minV, maxV) - minV) / range * 255).toInt()
                            val color = JET_LUT[norm.coerceIn(0, 255)]

                            pixels[rowOffset + absX] = color
                        }
                    }
                }
            }
        }

        val bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, imgW, 0, 0, imgW, imgH)

        return Triple(bitmap, minV, maxV)
    }
}
