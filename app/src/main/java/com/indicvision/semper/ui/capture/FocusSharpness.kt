package com.indicvision.semper.ui.capture

/**
 * How much of a patch's contrast sits at fine scale — the number the focus
 * confirm step asks the user to compare between taps.
 *
 * Pure JVM on purpose (an `IntArray` of ARGB, nothing from `android.graphics`),
 * so the arithmetic is unit-testable without a device or a preview.
 *
 * **The metric is mean squared gradient divided by variance**, both over the
 * same window. Two properties earn it that shape:
 *
 * - **The numerator is the quantity DIC actually depends on.** Summed squared
 *   intensity gradient is SSSIG — what the subset recommender solves against and
 *   what the correlation is built on. Defocus lowers it directly, so the readout
 *   means "how well can this be correlated", not merely "how crisp does it look".
 * - **The division is what makes two taps comparable.** Both terms scale with the
 *   square of contrast, so the ratio is unchanged by any affine change in
 *   intensity — a brighter or flatter part of the specimen reads the same. Without
 *   it, tapping a high-contrast region would score higher than a genuinely sharper
 *   tap on a dim one, and the comparison the step exists for would be a lie.
 *
 * The scale is arbitrary and deliberately unnamed: this answers *sharper or
 * softer than the other point*, never *sharp enough*. Nothing here can say the
 * latter, because how much gradient a speckle pattern should have depends on the
 * pattern.
 */
internal object FocusSharpness {

    /** Below this the patch is flat enough that its gradients are noise. */
    private const val MIN_VARIANCE = 1e-3

    /** Central differences need one pixel of margin on every side. */
    private const val MIN_EDGE = 3

    /**
     * Sharpness of the [width] × [height] ARGB window in [pixels], or 0.0 when
     * the window is too small or too flat to judge.
     *
     * Zero rather than an exception or a large number from a near-zero divide:
     * a patch with no contrast is not a soft focus, it is an absent measurement,
     * and the caller shows it as such.
     */
    fun of(pixels: IntArray, width: Int, height: Int): Double {
        if (width < MIN_EDGE || height < MIN_EDGE || pixels.size < width * height) return 0.0

        var sum = 0.0
        var sumSquares = 0.0
        var gradientEnergy = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val here = luma(pixels[row + x])
                // Central differences: symmetric about the pixel, so a gradient
                // is not reported half a pixel away from where it is, and one
                // noisy neighbour moves the result half as much as a forward
                // difference would.
                val gx = luma(pixels[row + x + 1]) - luma(pixels[row + x - 1])
                val gy = luma(pixels[row + width + x]) - luma(pixels[row - width + x])
                gradientEnergy += (gx * gx + gy * gy).toDouble()
                sum += here
                sumSquares += here.toDouble() * here
                count++
            }
        }
        // [MIN_EDGE] guarantees at least one interior pixel, so count is never 0.
        val mean = sum / count
        val variance = sumSquares / count - mean * mean
        return if (variance < MIN_VARIANCE) 0.0 else gradientEnergy / count / variance
    }

    /** Rec. 601 luma in 0..255, integer so the gradients stay exact. */
    private fun luma(argb: Int): Int {
        val r = (argb shr RED_SHIFT) and CHANNEL_MASK
        val g = (argb shr GREEN_SHIFT) and CHANNEL_MASK
        val b = argb and CHANNEL_MASK
        return (R_WEIGHT * r + G_WEIGHT * g + B_WEIGHT * b) shr LUMA_SHIFT
    }

    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val CHANNEL_MASK = 0xFF

    /** Rec. 601 in 1/256ths; the three sum to exactly 256, so gray maps to itself. */
    private const val R_WEIGHT = 77
    private const val G_WEIGHT = 150
    private const val B_WEIGHT = 29
    private const val LUMA_SHIFT = 8
}
