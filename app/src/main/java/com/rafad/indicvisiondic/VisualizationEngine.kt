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
        step: Int
    ): Triple<Bitmap, Float, Float> {

        // 1. Statistical analysis remains the same (it's fast enough)
        val validValues = mutableListOf<Float>()
        for (i in data.indices step 8) {
            if (data[i + 7] != 0f && data[i + 7] < 0.25f) {
                validValues.add(data[i + valIndex])
            }
        }

        if (validValues.isEmpty()) {
            return Triple(Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888), 0f, 0f)
        }

        validValues.sort()
        val minV = validValues[(validValues.size * 0.02).toInt().coerceIn(0, validValues.size - 1)]
        val maxV = validValues[(validValues.size * 0.98).toInt().coerceIn(0, validValues.size - 1)]
        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        // 🚀 2. THE PIXEL BUFFER (Total Image Area)
        // This is where the magic happens. We write to RAM, not a Canvas.
        val pixels = IntArray(imgW * imgH)

        // 3. Fill the pixel array
        val halfStep = step / 2
        for (i in data.indices step 8) {
            val corr = data[i + 7]
            if (corr == 0f || corr > 0.25f) continue

            val centerX = data[i].toInt()
            val centerY = data[i + 1].toInt()
            val value = data[i + valIndex]

            // Get color from LUT (No math needed here!)
            val norm = ((value.coerceIn(minV, maxV) - minV) / range * 255).toInt()
            val color = JET_LUT[norm.coerceIn(0, 255)]

            // 🚀 FAST BLOCK FILL: Instead of drawRect, we fill the IntArray
            // This is the direct equivalent of drawing a solid rectangle
            val yStart = (centerY - halfStep).coerceIn(0, imgH - 1)
            val yEnd = (centerY + halfStep).coerceIn(0, imgH - 1)
            val xStart = (centerX - halfStep).coerceIn(0, imgW - 1)
            val xEnd = (centerX + halfStep).coerceIn(0, imgW - 1)

            for (y in yStart..yEnd) {
                val rowOffset = y * imgW
                for (x in xStart..xEnd) {
                    pixels[rowOffset + x] = color
                }
            }
        }

        // 🚀 4. BLAST TO GPU: One-time memory copy to the Bitmap
        val bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, imgW, 0, 0, imgW, imgH)

        return Triple(bitmap, minV, maxV)
    }
}