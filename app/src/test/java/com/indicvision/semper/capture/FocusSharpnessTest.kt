package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.FocusSharpness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The focus readout is a comparison, so the two things it must never get wrong
 * are the direction (blur reads lower) and the fairness (a dimmer patch does not
 * read softer just for being dimmer).
 */
class FocusSharpnessTest {

    private companion object {
        const val EDGE = 48
        const val MID = 128
    }

    /** Gray, so [FocusSharpness]'s Rec. 601 weights sum back to exactly [value]. */
    private fun gray(value: Int): Int {
        val v = value.coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    /** A speckle-like field: per-pixel random, all even so halving is exact. */
    private fun speckle(seed: Int = 7): IntArray {
        val random = Random(seed)
        return IntArray(EDGE * EDGE) { gray(MID - 118 + 2 * random.nextInt(0, 119)) }
    }

    private fun boxBlur(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (y in 0 until EDGE) {
            for (x in 0 until EDGE) {
                out[y * EDGE + x] = gray(neighbourhoodMean(pixels, x, y))
            }
        }
        return out
    }

    private fun neighbourhoodMean(pixels: IntArray, x: Int, y: Int): Int {
        val xs = (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(EDGE - 1)
        val ys = (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(EDGE - 1)
        var sum = 0
        for (sy in ys) {
            for (sx in xs) sum += pixels[sy * EDGE + sx] and 0xFF
        }
        return sum / (xs.count() * ys.count())
    }

    @Test
    fun `blur reads lower than the pattern it was made from`() {
        val sharp = speckle()
        val soft = boxBlur(sharp)
        val sharpScore = FocusSharpness.of(sharp, EDGE, EDGE)
        val softScore = FocusSharpness.of(soft, EDGE, EDGE)
        assertTrue("blurred $softScore should be below sharp $sharpScore", softScore < sharpScore)
    }

    @Test
    fun `halving the contrast does not change the reading`() {
        // The property the whole comparison rests on. Both terms of the ratio
        // scale with contrast squared, so a dim part of the specimen must score
        // the same as a bright one at equal sharpness — otherwise the user is
        // told a high-contrast tap is a sharper tap.
        val bright = speckle()
        val dim = IntArray(bright.size) { gray(MID + ((bright[it] and 0xFF) - MID) / 2) }
        val brightScore = FocusSharpness.of(bright, EDGE, EDGE)
        val dimScore = FocusSharpness.of(dim, EDGE, EDGE)
        assertEquals(brightScore, dimScore, brightScore * 0.02)
    }

    @Test
    fun `the ordering survives the two being at different contrasts`() {
        // The case the readout actually meets: the user taps a dim sharp spot
        // after a bright soft one. Sharpness has to win over brightness.
        val source = speckle()
        val sharpDim = IntArray(source.size) { gray(MID + ((source[it] and 0xFF) - MID) / 4) }
        val softBright = boxBlur(source)
        assertTrue(
            FocusSharpness.of(sharpDim, EDGE, EDGE) > FocusSharpness.of(softBright, EDGE, EDGE),
        )
    }

    @Test
    fun `a flat window is no measurement rather than a soft one`() {
        val flat = IntArray(EDGE * EDGE) { gray(MID) }
        assertEquals(0.0, FocusSharpness.of(flat, EDGE, EDGE), 0.0)
    }

    @Test
    fun `a window too small for a central difference reads zero`() {
        assertEquals(0.0, FocusSharpness.of(IntArray(4) { gray(it * 60) }, 2, 2), 0.0)
    }

    @Test
    fun `a short array reads zero rather than running off the end`() {
        assertEquals(0.0, FocusSharpness.of(IntArray(5), EDGE, EDGE), 0.0)
    }
}
