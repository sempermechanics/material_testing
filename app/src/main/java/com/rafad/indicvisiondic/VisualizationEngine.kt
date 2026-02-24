package com.rafad.indicvisiondic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

object VisualizationEngine {

    fun generateHeatmap(
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int
    ): Triple<Bitmap, Float, Float> {

        // 1. Extract valid data points
        val validValues = mutableListOf<Float>()
        for (i in data.indices step 8) {
            val corr = data[i + 7]
            // Only consider points that actually tracked (corr != 0)
            // and have a reasonable correlation score (lower is better in ZNCC)
            if (corr != 0f && corr < 0.25f) {
                validValues.add(data[i + valIndex])
            }
        }

        if (validValues.isEmpty()) {
            return Triple(Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888), 0f, 0f)
        }

        // 2. STATISTICAL OUTLIER REJECTION (The Secret to beautiful heatmaps)
        validValues.sort()

        // Take the 2nd percentile and 98th percentile to ignore extreme noise spikes
        val minIdx = (validValues.size * 0.02).toInt().coerceIn(0, validValues.size - 1)
        val maxIdx = (validValues.size * 0.98).toInt().coerceIn(0, validValues.size - 1)

        val minV = validValues[minIdx]
        val maxV = validValues[maxIdx]
        val range = if (maxV - minV == 0f) 0.0001f else maxV - minV

        // 3. Draw the Heatmap
        val bitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { style = Paint.Style.FILL }

        // Expand the drawn rectangles slightly to eliminate grid lines between steps
        val drawStep = step.toFloat() * 1.2f

        for (i in data.indices step 8) {
            val corr = data[i + 7]
            if (corr == 0f || corr > 0.25f) continue // Skip bad tracking points

            val x = data[i]
            val y = data[i + 1]
            val value = data[i + valIndex]

            // Clamp the value to our statistical bounds
            val clampedValue = value.coerceIn(minV, maxV)

            // Normalize between 0.0 and 1.0
            val norm = (clampedValue - minV) / range

            paint.color = getJetColor(norm)
            canvas.drawRect(
                x - drawStep / 2,
                y - drawStep / 2,
                x + drawStep / 2,
                y + drawStep / 2,
                paint
            )
        }

        return Triple(bitmap, minV, maxV)
    }

    // Classic Engineering "Jet" Colormap (Blue -> Cyan -> Green -> Yellow -> Red)
    private fun getJetColor(v: Float): Int {
        val vClamped = v.coerceIn(0f, 1f)
        var r = 1.0f
        var g = 1.0f
        var b = 1.0f

        if (vClamped < 0.25f) {
            r = 0.0f
            g = 4.0f * vClamped
        } else if (vClamped < 0.5f) {
            r = 0.0f
            b = 1.0f + 4.0f * (0.25f - vClamped)
        } else if (vClamped < 0.75f) {
            r = 4.0f * (vClamped - 0.5f)
            b = 0.0f
        } else {
            g = 1.0f + 4.0f * (0.75f - vClamped)
            b = 0.0f
        }

        return Color.rgb(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255)
        )
    }
}