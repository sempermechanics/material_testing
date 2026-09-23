package com.indicvision.semper.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameExtractorTest {

    @Test
    fun `formatClock formats minutes and seconds correctly`() {
        assertEquals("0:00", VideoKeyframeHelper.formatClock(0L))
        assertEquals("0:05", VideoKeyframeHelper.formatClock(5_000L))
        assertEquals("1:05", VideoKeyframeHelper.formatClock(65_000L))
        assertEquals("10:30", VideoKeyframeHelper.formatClock(630_000L))
    }

    @Test
    fun `selectEvenly preserves first and last elements and distributes evenly`() {
        val timestamps = listOf(100L, 200L, 300L, 400L, 500L)
        val selected = VideoKeyframeHelper.selectEvenly(timestamps, targetCount = 3)

        assertEquals(3, selected.size)
        assertEquals(100L, selected.first())
        assertEquals(500L, selected.last())
        assertEquals(listOf(100L, 300L, 500L), selected)
    }

    @Test
    fun `selectEvenly returns original list when count is equal or greater`() {
        val timestamps = listOf(100L, 200L, 300L)
        val selected = VideoKeyframeHelper.selectEvenly(timestamps, targetCount = 5)
        assertEquals(timestamps, selected)
    }

    @Test
    fun `uniformTimestampsUs generates evenly spaced timestamps clamped by maxFrames`() {
        val timestamps = VideoKeyframeHelper.uniformTimestampsUs(
            startMs = 0L,
            endMs = 2000L,
            fpsExtract = 2.0,
            maxFrames = 10,
        )
        // 0s, 0.5s, 1.0s, 1.5s, 2.0s -> 5 frames
        assertEquals(5, timestamps.size)
        assertEquals(0L, timestamps[0])
        assertEquals(2_000_000L, timestamps.last())
    }

    @Test
    fun `resolveExtractionPlan chooses true keyframes when 2 or more are available`() {
        val keyframes = listOf(0L, 1_000_000L, 2_000_000L)
        val plan = VideoKeyframeHelper.resolveExtractionPlan(
            keyframeTimestampsUs = keyframes,
            startMs = 0L,
            endMs = 2000L,
            fpsExtract = 10.0,
            maxFrames = 10,
            forceUniform = false,
        )
        assertTrue("Should be keyframe plan", plan.isKeyframePlan)
        assertEquals(keyframes, plan.timestampsUs)
    }

    @Test
    fun `resolveExtractionPlan falls back to uniform sampling when keyframes are sparse`() {
        val sparseKeyframes = listOf(500_000L) // Only 1 keyframe
        val plan = VideoKeyframeHelper.resolveExtractionPlan(
            keyframeTimestampsUs = sparseKeyframes,
            startMs = 0L,
            endMs = 2000L,
            fpsExtract = 2.0,
            maxFrames = 10,
            forceUniform = false,
        )
        assertFalse("Should fall back to uniform plan", plan.isKeyframePlan)
        assertTrue("Should generate at least 2 frames", plan.timestampsUs.size >= 2)
    }

    @Test
    fun `resolveExtractionPlan respects forceUniform flag`() {
        val keyframes = listOf(0L, 1_000_000L, 2_000_000L)
        val plan = VideoKeyframeHelper.resolveExtractionPlan(
            keyframeTimestampsUs = keyframes,
            startMs = 0L,
            endMs = 2000L,
            fpsExtract = 2.0,
            maxFrames = 10,
            forceUniform = true,
        )
        assertFalse("Should be forced to uniform plan", plan.isKeyframePlan)
    }

    @Test
    fun `resolveExtractionPlan downsamples keyframes when exceeding maxFrames`() {
        val keyframes = (0..20).map { it * 100_000L } // 21 keyframes
        val plan = VideoKeyframeHelper.resolveExtractionPlan(
            keyframeTimestampsUs = keyframes,
            startMs = 0L,
            endMs = 2000L,
            fpsExtract = 10.0,
            maxFrames = 5,
            forceUniform = false,
        )
        assertTrue(plan.isKeyframePlan)
        assertEquals(5, plan.timestampsUs.size)
        assertEquals(keyframes.first(), plan.timestampsUs.first())
        assertEquals(keyframes.last(), plan.timestampsUs.last())
    }
}
