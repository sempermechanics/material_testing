package com.rafad.indicvisiondic

import android.graphics.*
import kotlin.math.max
import kotlin.math.min

object VisualizationEngine {

    fun generateHeatmap(
        data: FloatArray,
        imgWidth: Int,
        imgHeight: Int,
        dataIndex: Int,
        step: Int
    ): Triple<Bitmap, Float, Float> {

        // 1. Create a FULL-SIZED transparent canvas
        val bitmap = Bitmap.createBitmap(imgWidth, imgHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = false
        }

        // 2. Find Percentiles for Color Scaling (Drops noise outliers)
        val validValues = mutableListOf<Float>()
        var i = 0
        while (i < data.size) {
            val v = data[i + dataIndex]
            if (v > -900f) validValues.add(v)
            i += 8
        }

        if (validValues.isEmpty()) {
            return Triple(bitmap, 0f, 0f)
        }

        validValues.sort()
        var minVal = validValues[(validValues.size * 0.02).toInt()]
        var maxVal = validValues[(validValues.size * 0.98).toInt()]

        if (maxVal <= minVal) maxVal = minVal + 0.0001f
        val range = maxVal - minVal

        // 3. Draw directly onto the full-size canvas using absolute coordinates!
        val halfStep = step / 2f
        i = 0
        while (i < data.size) {
            val x = data[i]
            val y = data[i + 1]
            val value = data[i + dataIndex]

            if (value > -900f) {
                val normalized = ((value - minVal) / range).coerceIn(0f, 1f)

                // Alpha = 180 (about 70% opacity) so we can see the image underneath
                paint.color = getJetColor(normalized.toDouble(), 180)

                canvas.drawRect(
                    x - halfStep,
                    y - halfStep,
                    x + halfStep,
                    y + halfStep,
                    paint
                )
            }
            i += 8
        }

        return Triple(bitmap, minVal, maxVal)
    }

    private fun getJetColor(v: Double, alpha: Int): Int {
        val r = clamp(min(4.0 * v - 1.5, -4.0 * v + 4.5))
        val g = clamp(min(4.0 * v - 0.5, -4.0 * v + 3.5))
        val b = clamp(min(4.0 * v + 0.5, -4.0 * v + 2.5))
        return Color.argb(alpha, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
    }

    private fun clamp(v: Double): Double = max(0.0, min(1.0, v))
}