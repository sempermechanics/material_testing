package com.indicvision.semper.analysis

import com.indicvision.semper.ui.analysis.SubsetRecommender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Checks the SSSIG subset-size criterion of Pan et al., Opt. Express 16, 7037
 * (2008) as implemented by [SubsetRecommender.subsetSizeForPatch].
 */
class SubsetRecommenderTest {

    private val maxSize = 101
    private val side = maxSize + 2

    /** Speckle-like patch: uniform noise over the full 8-bit range. */
    private fun randomPatch(seed: Int, amplitude: Float): FloatArray {
        val rng = Random(seed)
        return FloatArray(side * side) { rng.nextFloat() * amplitude }
    }

    /** SSSIG in x over the (2*half+1) subset at the patch center. */
    private fun sssigX(patch: FloatArray, half: Int): Double {
        val c = side / 2
        var sum = 0.0
        for (dy in -half..half) {
            for (dx in -half..half) {
                val x = c + dx
                val y = c + dy
                val gx = (patch[y * side + x + 1] - patch[y * side + x - 1]) / 2.0
                sum += gx * gx
            }
        }
        return sum
    }

    @Test
    fun `threshold follows the paper's inversion of the SD-error model`() {
        // 4 / 0.007^2 — the paper rounds this to the 1e5 it uses in section 5.4.
        assertEquals(81632.65, SubsetRecommender.SSSIG_THRESHOLD, 1.0)
        // Round-trip: a subset sitting exactly on the threshold yields the
        // target SD error of Eqs. (18) and (19).
        assertEquals(
            SubsetRecommender.TARGET_SD_ERROR_PX,
            sqrt(SubsetRecommender.NOISE_VARIANCE / SubsetRecommender.SSSIG_THRESHOLD),
            1e-9,
        )
    }

    @Test
    fun `chosen size is the smallest one clearing the threshold`() {
        val patch = randomPatch(seed = 7, amplitude = 255f)
        val size = SubsetRecommender.subsetSizeForPatch(patch, side, minSize = 15, maxSize = maxSize)

        assertTrue("size must be odd", size % 2 == 1)
        assertTrue(size >= 15 && size <= maxSize)
        assertTrue(
            "chosen subset clears the threshold",
            sssigX(patch, size / 2) >= SubsetRecommender.SSSIG_THRESHOLD,
        )
        if (size > 15) {
            assertTrue(
                "one step smaller does not",
                sssigX(patch, size / 2 - 1) < SubsetRecommender.SSSIG_THRESHOLD,
            )
        }
    }

    @Test
    fun `low contrast needs a larger subset than high contrast`() {
        val strong = SubsetRecommender.subsetSizeForPatch(
            randomPatch(seed = 3, amplitude = 255f),
            side,
            15,
            maxSize,
        )
        val weak = SubsetRecommender.subsetSizeForPatch(
            randomPatch(seed = 3, amplitude = 40f),
            side,
            15,
            maxSize,
        )
        assertTrue("weak speckle ($weak) must not beat strong speckle ($strong)", weak > strong)
    }

    @Test
    fun `flat patch is capped at the largest allowed subset`() {
        val flat = FloatArray(side * side) { 128f }
        assertEquals(maxSize, SubsetRecommender.subsetSizeForPatch(flat, side, 15, maxSize))
    }

    @Test
    fun `minimum size is respected even when a tiny subset would qualify`() {
        val patch = randomPatch(seed = 11, amplitude = 255f)
        val size = SubsetRecommender.subsetSizeForPatch(patch, side, minSize = 41, maxSize = maxSize)
        assertTrue(size >= 41)
    }
}
