package com.rafad.indicvisiondic

import android.graphics.Bitmap
import android.graphics.Color

object VisualizationEngine {

    // 🚀 PRECOMPUTED LOOKUP TABLE: Jet Colormap (256 colors)
    private val JET_LUT = IntArray(256) { i ->
        val v = i / 255.0f
        val r = (clamp(minOf(4f * v - 1.5f, -4f * v + 4.5f)) * 255).toInt()
        val g = (clamp(minOf(4f * v - 0.5f, -4f * v + 3.5f)) * 255).toInt()
        val b = (clamp(minOf(4f * v + 0.5f, -4f * v + 2.5f)) * 255).toInt()
        Color.rgb(r, g, b)
    }

    private fun clamp(v: Float) = v.coerceIn(0f, 1f)

    fun generateHeatmap(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null, // 🚀 NEW: Optional Custom Bounds
        customMax: Float? = null
    ): Triple<Bitmap, Float, Float> {

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE

        val validValues = mutableListOf<Float>()

        for (i in data.indices step 8) {
            val corr = data[i + 7]
            if (corr != 0f && corr <= 0.15f) { // 🚀 Tighter correlation gate
                val x = data[i].toInt()
                val y = data[i+1].toInt()
                val v = data[i+valIndex]

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

        // 🚀 THE SCALING LOGIC: Use Custom Bounds if provided, else robust auto-scale via Zero-Anchored 2%-98% clipping
        val minV: Float
        val maxV: Float
        if (customMin != null && customMax != null) {
            minV = customMin
            maxV = customMax
        } else {
            validValues.sort()
            var v02 = validValues[(validValues.size * 0.02).toInt().coerceIn(0, validValues.size - 1)]
            var v98 = validValues[(validValues.size * 0.98).toInt().coerceIn(0, validValues.size - 1)]

            // 🚀 ROBUSTNESS FIX: If the range is near-zero (noise floor), 
            // set a minimum meaningful span so the spectrum doesn't explode on noise.
            val minSpan = if (valIndex > 3) 0.0001f else 0.01f // 0.1mε or 0.01px
            if ((v98 - v02) < minSpan) {
                val mid = (v98 + v02) / 2f
                v02 = mid - (minSpan / 2f)
                v98 = mid + (minSpan / 2f)
            }

            minV = v02
            maxV = v98
        }

        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        val pixels = IntArray(imgW * imgH)
        val cols = ((maxX - minX) / step) + 1
        val rows = ((maxY - minY) / step) + 1
        val grid = FloatArray(cols * rows) { Float.NaN }

        for (i in data.indices step 8) {
            val corr = data[i + 7]
            if (corr != 0f && corr <= 0.15f) { // 🚀 Tighter correlation gate
                val x = data[i].toInt()
                val y = data[i+1].toInt()
                val c = (x - minX) / step
                val r = (y - minY) / step
                if (c in 0 until cols && r in 0 until rows) {
                    grid[r * cols + c] = data[i+valIndex]
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

                            // 🚀 Color Mapping naturally clamps to Min/Max bounds!
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