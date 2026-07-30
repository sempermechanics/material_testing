package com.indicvision.semper.analysis

import com.indicvision.semper.DicResult
import com.indicvision.semper.ui.analysis.SubsetRecommender
import com.indicvision.semper.ui.analysis.VsgStudy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the sweep space and the centre line cut of the virtual strain gauge
 * study (§5.4.5 of the iDICs Good Practices Guide) as implemented by [VsgStudy].
 */
class VsgStudyTest {

    private val exx = DicResult.IDX_EXX

    // ------------------------------------------------------------------
    // VSG relation
    // ------------------------------------------------------------------

    @Test
    fun `vsg follows the strain window relation`() {
        assertEquals((15 - 1) * 5 + 1, VsgStudy.vsgFor(5, 15))
        assertEquals(71, VsgStudy.Point(41, 5, 15).vsg)
    }

    // ------------------------------------------------------------------
    // Step size (a single fixed fraction of the subset)
    // ------------------------------------------------------------------

    @Test
    fun `step size is the subset over the chosen denominator`() {
        assertEquals(20, VsgStudy.stepSizeFor(40, 2)) // subset/2
        assertEquals(13, VsgStudy.stepSizeFor(40, 3)) // subset/3, rounded
        assertEquals(8, VsgStudy.stepSizeFor(40, 5)) // subset/5
    }

    @Test
    fun `step denominator is clamped to the slider range`() {
        assertEquals(VsgStudy.stepSizeFor(41, VsgStudy.STEP_DENOM_MAX), VsgStudy.stepSizeFor(41, 99))
        assertEquals(VsgStudy.stepSizeFor(41, VsgStudy.STEP_DENOM_MIN), VsgStudy.stepSizeFor(41, 1))
    }

    @Test
    fun `step stays inside the engine's step range`() {
        for (subset in SubsetRecommender.MIN_SUBSET..SubsetRecommender.MAX_SUBSET step 2) {
            for (d in VsgStudy.STEP_DENOM_MIN..VsgStudy.STEP_DENOM_MAX) {
                val step = VsgStudy.stepSizeFor(subset, d)
                assertTrue("step $step out of range: $subset ÷ $d", step in VsgStudy.MIN_STEP..VsgStudy.MAX_STEP)
            }
        }
    }

    // ------------------------------------------------------------------
    // Subset sampling
    // ------------------------------------------------------------------

    @Test
    fun `subset sizes are the odd values across the range`() {
        assertEquals(listOf(41, 43, 45, 47, 49), VsgStudy.subsetSizes(41, 49))
    }

    @Test
    fun `an even subset range is snapped onto odd sizes`() {
        assertEquals(listOf(41, 43, 45, 47, 49), VsgStudy.subsetSizes(40, 48))
    }

    @Test
    fun `sampled subsets include both ends and are evenly spread`() {
        // 41..61 odd = 11 values; 3 samples → first, middle, last.
        assertEquals(listOf(41, 51, 61), VsgStudy.sampledSubsets(41, 61, 3))
    }

    @Test
    fun `sampling more than available returns every subset`() {
        assertEquals(VsgStudy.subsetSizes(41, 45), VsgStudy.sampledSubsets(41, 45, 8))
    }

    @Test
    fun `one subset sample is the middle of the range`() {
        assertEquals(listOf(51), VsgStudy.sampledSubsets(41, 61, 1))
    }

    @Test
    fun `subset range is bounded against pathological input`() {
        assertEquals(41 + VsgStudy.MAX_SUBSET_SPAN, VsgStudy.subsetSizes(41, 999_999).last())
    }

    // ------------------------------------------------------------------
    // The sweep grid: tests scale by x (subset) × y (VSG) × z (step)
    // ------------------------------------------------------------------

    /** subset samples × VSG samples × step depth = expected size for a plan. */
    @Suppress("LongParameterList") // mirrors the planner's independent axes
    private fun plan(
        subsetMin: Int = 41,
        subsetMax: Int = 61,
        subsetSamples: Int = 3,
        strainWinMin: Int = VsgStudy.MIN_STRAIN_WINDOW,
        strainWinMax: Int = 51,
        strainWinSamples: Int = 3,
        stepDenominator: Int = 4,
    ) = VsgStudy.plan(
        subsetMin,
        subsetMax,
        subsetSamples,
        strainWinMin,
        strainWinMax,
        strainWinSamples,
        stepDenominator,
    )

    @Test
    fun `plan scales as subset samples times vsg samples`() {
        // A generous VSG ceiling so no VSG ladder is truncated: the full grid.
        val subsets = 3
        val vsg = 3
        val p = plan(subsetSamples = subsets, strainWinSamples = vsg, strainWinMax = VsgStudy.MAX_STRAIN_WINDOW)
        assertEquals(subsets * vsg, p.size)
    }

    @Test
    fun `every combination uses the one chosen step size`() {
        // A single subset so the fixed step is unambiguous; every point shares it.
        val p = plan(
            subsetMin = 41,
            subsetMax = 41,
            subsetSamples = 1,
            strainWinMax = VsgStudy.MAX_STRAIN_WINDOW,
            stepDenominator = 3,
        )
        assertEquals(setOf(VsgStudy.stepSizeFor(41, 3)), p.map { it.step }.toSet())
    }

    @Test
    fun `plan covers exactly the sampled subsets`() {
        val p = plan(subsetSamples = 3, subsetMin = 41, subsetMax = 61)
        assertEquals(VsgStudy.sampledSubsets(41, 61, 3), p.map { it.subset }.distinct().sorted())
    }

    @Test
    fun `one sample per axis yields a single analysis`() {
        val p = plan(
            subsetSamples = 1,
            strainWinSamples = 1,
            stepDenominator = 2,
            strainWinMax = VsgStudy.MAX_STRAIN_WINDOW,
        )
        assertEquals(1, p.size)
    }

    @Test
    fun `the strain window axis honours both ends, like the subset axis`() {
        val p = plan(strainWinMin = 15, strainWinMax = 25, strainWinSamples = 8)

        val windows = p.map { it.strainWindow }.distinct().sorted()
        assertEquals("nothing below the floor", 15, windows.first())
        assertTrue("nothing above the ceiling: $windows", windows.last() <= 25)
        assertTrue("the range should not collapse to one window", windows.size > 1)
    }

    @Test
    fun `a one-window range sweeps only that window`() {
        val p = plan(strainWinMin = 21, strainWinMax = 21, strainWinSamples = 5)

        assertEquals(setOf(21), p.map { it.strainWindow }.toSet())
    }

    @Test
    fun `an inverted window range does not vanish`() {
        // The UI clamps, but the planner must not produce an empty sweep if a
        // min ever arrives above its max.
        val p = plan(strainWinMin = 31, strainWinMax = 11, strainWinSamples = 3)

        assertTrue("expected at least one combination", p.isNotEmpty())
        p.forEach { assertTrue(it.strainWindow % 2 == 1) }
    }

    @Test
    fun `window bounds are snapped odd and clamped to what the engine takes`() {
        val p = plan(strainWinMin = 4, strainWinMax = 999, strainWinSamples = 8)

        val windows = p.map { it.strainWindow }
        assertTrue(windows.all { it % 2 == 1 })
        assertTrue(windows.all { it in VsgStudy.MIN_STRAIN_WINDOW..VsgStudy.MAX_STRAIN_WINDOW })
    }

    @Test
    fun `plan is ordered by subset then vsg`() {
        val keys = plan().map { it.subset to it.vsg }
        assertEquals(keys.sortedWith(compareBy({ it.first }, { it.second })), keys)
    }

    @Test
    fun `every combination is a valid engine point`() {
        plan(strainWinMax = VsgStudy.MAX_STRAIN_WINDOW).forEach {
            assertTrue(it.subset % 2 == 1)
            assertTrue(it.strainWindow % 2 == 1)
            assertTrue(it.strainWindow in VsgStudy.MIN_STRAIN_WINDOW..VsgStudy.MAX_STRAIN_WINDOW)
            assertTrue(it.step in VsgStudy.MIN_STEP..VsgStudy.MAX_STEP)
        }
    }

    @Test
    fun `plan has no duplicate combinations`() {
        val p = plan(strainWinMax = VsgStudy.MAX_STRAIN_WINDOW)
        assertEquals(p.size, p.distinct().size)
    }

    @Test
    fun `windowForVsg inverts vsgFor`() {
        // A VSG that is exactly reachable maps back to its own window.
        val step = 8
        val window = 15
        val vsg = VsgStudy.vsgFor(step, window) // (15-1)*8+1 = 113
        assertEquals(window, VsgStudy.windowForVsg(step, vsg))
    }

    @Test
    fun `plan never throws across the whole control space`() {
        // Guards the sampling maths against negative/zero counts and empty
        // ranges for every combination of inputs the UI can produce. The whole
        // grid is built flat, then walked once, so nothing is deeply nested.
        val samples = (VsgStudy.MIN_SAMPLES..VsgStudy.MAX_SAMPLES).toList()
        val depths = (VsgStudy.STEP_DENOM_MIN..VsgStudy.STEP_DENOM_MAX).toList()
        val cases = (SubsetRecommender.MIN_SUBSET..SubsetRecommender.MAX_SUBSET step 8)
            .flatMap { min -> listOf(0, 8, 40, 60).map { min to (min + it) } }
            .flatMap { range -> samples.map { range to it } }
            .flatMap { (range, x) -> samples.map { Triple(range, x, it) } }
            .flatMap { (range, x, y) -> depths.map { listOf(3, 15, 51).map { sw -> Case6(range, x, y, it, sw) } } }
            .flatten()

        cases.forEach { c ->
            val p = VsgStudy.plan(
                c.range.first,
                c.range.second,
                c.x,
                VsgStudy.MIN_STRAIN_WINDOW,
                c.strainWinMax,
                c.y,
                c.depth,
            )
            assertEquals("duplicates for $c", p.size, p.distinct().size)
        }
    }

    private data class Case6(val range: Pair<Int, Int>, val x: Int, val y: Int, val depth: Int, val strainWinMax: Int)

    // ------------------------------------------------------------------
    // Field measurements
    // ------------------------------------------------------------------

    /**
     * A `.dat` field on a [size] x [size] grid of pitch [step] anchored at the
     * origin, with strain from [strainAt] (raw units) and every point solved.
     */
    private fun field(size: Int, step: Int, strainAt: (Float, Float) -> Float): FloatArray {
        val out = FloatArray(size * size * DicResult.STRIDE)
        var i = 0
        for (row in 0 until size) {
            for (col in 0 until size) {
                val x = (col * step).toFloat()
                val y = (row * step).toFloat()
                out[i + DicResult.IDX_X] = x
                out[i + DicResult.IDX_Y] = y
                out[i + DicResult.IDX_EXX] = strainAt(x, y)
                out[i + DicResult.IDX_ZNSSD] = 0.01f
                i += DicResult.STRIDE
            }
        }
        return out
    }

    @Test
    fun `field peak reports the largest magnitude in millistrain`() {
        val data = field(9, 5) { x, _ -> if (x == 20f) -0.004f else 0.001f }
        assertEquals(4f, VsgStudy.fieldPeak(data, exx), 1e-4f)
    }

    @Test
    fun `field peak ignores points the engine rejected`() {
        val data = field(5, 5) { _, _ -> 0.001f }
        // Plant a huge strain on a point flagged invalid by the engine.
        data[DicResult.IDX_EXX] = 0.5f
        data[DicResult.IDX_ZNSSD] = -1f
        assertEquals(1f, VsgStudy.fieldPeak(data, exx), 1e-4f)
    }

    // ------------------------------------------------------------------
    // Centre line cut
    // ------------------------------------------------------------------

    @Test
    fun `centre line sits at the middle of the roi on the chosen axis`() {
        val horizontal = VsgStudy.centreLine(100, 200, 40, 60, horizontal = true)
        assertTrue(horizontal.horizontal)
        assertEquals(230f, horizontal.position, 1e-3f)

        val vertical = VsgStudy.centreLine(100, 200, 40, 60, horizontal = false)
        assertTrue(!vertical.horizontal)
        assertEquals(120f, vertical.position, 1e-3f)
    }

    @Test
    fun `profile walks one grid row in order`() {
        val step = 5
        val size = 7
        val data = field(size, step) { x, _ -> x / 10000f }
        val line = VsgStudy.StudyLine(horizontal = true, position = 10f)
        val profile = VsgStudy.profileAlong(data, exx, line, step / 2f)

        assertEquals(size, profile.size)
        assertEquals(profile.map { it.first }.sorted(), profile.map { it.first })
        assertEquals(0f, profile.first().first, 1e-3f)
        assertEquals(((size - 1) * step).toFloat(), profile.last().first, 1e-3f)
    }

    @Test
    fun `a vertical cut walks a column`() {
        val step = 5
        val size = 7
        val data = field(size, step) { _, y -> y / 10000f }
        val line = VsgStudy.StudyLine(horizontal = false, position = 15f)
        val profile = VsgStudy.profileAlong(data, exx, line, step / 2f)

        assertEquals(size, profile.size)
        // Position runs along y, and strain rises with it.
        assertEquals(0f, profile.first().first, 1e-3f)
        assertTrue(profile.last().second > profile.first().second)
    }

    @Test
    fun `line peak reads the peak on its own row only`() {
        val step = 5
        // Row y=10 holds a modest peak; row y=25 holds a much larger one that
        // the cut must not see.
        val data = field(9, step) { _, y ->
            when (y) {
                10f -> 0.002f
                25f -> 0.009f
                else -> 0.0005f
            }
        }
        val line = VsgStudy.StudyLine(horizontal = true, position = 10f)
        assertEquals(2f, VsgStudy.linePeak(data, exx, line, step / 2f), 1e-3f)
    }

    @Test
    fun `line peak falls back to the field peak when the cut misses the grid`() {
        val data = field(5, 5) { _, _ -> 0.003f }
        val offGrid = VsgStudy.StudyLine(horizontal = true, position = 999f)
        assertEquals(3f, VsgStudy.linePeak(data, exx, offGrid, 2f), 1e-3f)
    }

    @Test
    fun `the cut lands on the same physical line whatever the step size`() {
        // What makes the sweep comparable: a centre cut of the same ROI picks
        // out the same y for every combination, however the grid is spaced.
        val line = VsgStudy.centreLine(0, 0, 40, 40, horizontal = true)
        listOf(4, 5, 10).forEach { step ->
            val data = field(9, step) { _, y -> if (y == 20f) 0.005f else 0.001f }
            val profile = VsgStudy.profileAlong(data, exx, line, step / 2f)
            assertTrue("step $step found no points on the cut", profile.isNotEmpty())
            assertEquals("step $step missed the band", 5f, profile.maxOf { it.second }, 1e-3f)
        }
    }
}
