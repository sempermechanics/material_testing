package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.AveragingPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AveragingPlan] is the derivation behind "averaging N shots per frame" —
 * never offered as a choice, so these tests are the only place the arithmetic
 * is checked at all.
 */
class AveragingPlanTest {

    @Test
    fun `a setup that has not settled never averages`() {
        assertEquals(1, AveragingPlan.framesFor(intervalMs = 10_000L, perFrameMs = 100L, steady = false))
    }

    @Test
    fun `a non-positive interval or per-frame cost falls back to one frame`() {
        assertEquals(1, AveragingPlan.framesFor(intervalMs = 0L, perFrameMs = 100L, steady = true))
        assertEquals(1, AveragingPlan.framesFor(intervalMs = -5L, perFrameMs = 100L, steady = true))
        assertEquals(1, AveragingPlan.framesFor(intervalMs = 10_000L, perFrameMs = 0L, steady = true))
        assertEquals(1, AveragingPlan.framesFor(intervalMs = 10_000L, perFrameMs = -1L, steady = true))
    }

    @Test
    fun `derives how many stills fit in the usable share of the interval`() {
        // 1000ms interval, 70% usable = 700ms; 100ms per still fits 7.
        assertEquals(7, AveragingPlan.framesFor(intervalMs = 1_000L, perFrameMs = 100L, steady = true))
    }

    @Test
    fun `an interval too short for even one extra still still returns one`() {
        assertEquals(1, AveragingPlan.framesFor(intervalMs = 100L, perFrameMs = 200L, steady = true))
    }

    @Test
    fun `never exceeds the maximum frame count regardless of how much room the interval has`() {
        val k = AveragingPlan.framesFor(intervalMs = 1_000_000L, perFrameMs = 1L, steady = true)
        assertEquals(AveragingPlan.MAX_FRAMES, k)
    }

    @Test
    fun `higher k never comes from claiming a cheaper per-frame cost`() {
        // AveragingPlan.framesFor has no ISO or exposure parameter: the only way to
        // get more frames is a longer interval or a genuinely cheaper (measured)
        // per-frame cost. Modelling "shorten the exposure to fit more frames" as a
        // smaller perFrameMs must produce a k the real, locked-exposure cost would
        // not — which is exactly the shortcut this function must never take on its
        // own. It cannot, because callers only ever pass it the measured cost at
        // the locked exposure; this test pins that a smaller (hypothetical, unearned)
        // cost is the only way k would rise, so supplying anything else always
        // gives the honest, lower answer.
        val interval = 2_000L
        val lockedExposureCostMs = 300L
        val hypotheticalShorterExposureCostMs = 100L

        val kAtLockedExposure = AveragingPlan.framesFor(interval, lockedExposureCostMs, steady = true)
        val kAtShorterExposure = AveragingPlan.framesFor(interval, hypotheticalShorterExposureCostMs, steady = true)

        assertTrue(
            "a shorter, unearned per-frame cost must not be needed to reach the real k",
            kAtLockedExposure < kAtShorterExposure,
        )
        // The real answer, from the real (locked) cost, is what a caller gets when
        // it supplies the real cost — never inflated by pretending the exposure
        // could be shorter than it is.
        assertEquals(4, kAtLockedExposure)
    }

    @Test
    fun `noise gain is one for a single frame`() {
        assertEquals(1.0, AveragingPlan.noiseGain(1), 0.0)
        assertEquals(1.0, AveragingPlan.noiseGain(0), 0.0)
    }

    @Test
    fun `noise gain is the square root of the frame count`() {
        assertEquals(2.0, AveragingPlan.noiseGain(4), 1e-9)
        assertEquals(4.0, AveragingPlan.noiseGain(16), 1e-9)
    }
}
