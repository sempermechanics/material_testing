package com.sempermechanics.semper.imaging.video

import com.sempermechanics.semper.imaging.video.RetrieverFrameClock.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The retriever rung's frame times (TD-194), from a sample walk fed by hand. */
class RetrieverFrameClockTest {

    /** A clip's frame starts at 30 fps, in µs: 0, 33 333, 66 667, … */
    private fun frameUs(index: Int): Long = index * 1_000_000L / 30

    private fun walkOf(endUs: Long, samples: List<Sample>): RetrieverFrameClock {
        val queue = ArrayDeque(samples)
        return RetrieverFrameClock.walk(endUs) { queue.removeFirstOrNull() }
    }

    @Test
    fun `nearest picks the closer neighbour, the later on a tie, and clamps at either end`() {
        val sorted = listOf(0L, 100L, 200L)
        assertNull(RetrieverFrameClock.nearest(emptyList(), 50L))
        assertEquals(0L, RetrieverFrameClock.nearest(sorted, -5L))
        assertEquals(100L, RetrieverFrameClock.nearest(sorted, 100L))
        assertEquals(100L, RetrieverFrameClock.nearest(sorted, 140L))
        assertEquals(200L, RetrieverFrameClock.nearest(sorted, 160L))
        assertEquals(200L, RetrieverFrameClock.nearest(sorted, 150L))
        assertEquals(200L, RetrieverFrameClock.nearest(sorted, 999L))
    }

    @Test
    fun `a uniform sample between frames is timed as the nearest frame, not the time asked for`() {
        // One sync frame a second, as the device test's clip has.
        val samples = (0 until 90).map { Sample(frameUs(it), isSync = it % 30 == 0) }
        val clock = walkOf(endUs = 3_000_000L, samples)
        // 7 fps asks for 142 857 µs: frame 4 (133 333) is nearer than frame 5 (166 667).
        assertEquals(frameUs(4), clock.frameTimeUs(142_857L, syncFrame = false))
        // The last sample, asked for at the duration, is the last frame's start.
        assertEquals(frameUs(89), clock.frameTimeUs(3_000_000L, syncFrame = false))
        // A frame from OPTION_CLOSEST_SYNC is the sync frame nearest the time asked for.
        assertEquals(frameUs(30), clock.frameTimeUs(1_400_000L, syncFrame = true))
        assertEquals(frameUs(60), clock.frameTimeUs(1_600_000L, syncFrame = true))
    }

    @Test
    fun `the walk reads through the first sync frame past the end and the frames that show before it`() {
        // Decode order of an IPBB stream: I0 P3 B1 B2 P6 B4 B5 I9 B7 B8 P12 …
        val decodeOrder = listOf(0, 3, 1, 2, 6, 4, 5, 9, 7, 8, 12, 10, 11, 15)
        val samples = decodeOrder.map { Sample(frameUs(it), isSync = it % 9 == 0) }
        // The segment ends between frames 7 and 8, before the next sync frame (9).
        val clock = walkOf(endUs = frameUs(7) + 1, samples)
        // Frame 8 decodes after the sync frame but shows before it, so it is read
        // and a time just past it resolves to it; nothing after frame 9 is read.
        assertEquals(frameUs(8), clock.frameTimeUs(frameUs(8) + 2, syncFrame = false))
        assertEquals(frameUs(9), clock.frameTimeUs(frameUs(11), syncFrame = false))
        assertEquals(frameUs(9), clock.frameTimeUs(frameUs(7), syncFrame = true))
    }

    @Test
    fun `the walk stops at the end of a track with no sync frame past the segment`() {
        val samples = (0 until 10).map { Sample(frameUs(it), isSync = it == 0) }
        val clock = walkOf(endUs = frameUs(20), samples)
        assertEquals(frameUs(9), clock.frameTimeUs(frameUs(20), syncFrame = false))
        assertEquals(0L, clock.frameTimeUs(frameUs(9), syncFrame = true))
    }

    @Test
    fun `with no samples each frame keeps the time asked for`() {
        assertEquals(142_857L, RetrieverFrameClock.NONE.frameTimeUs(142_857L, syncFrame = false))
        assertEquals(142_857L, walkOf(endUs = 1_000_000L, emptyList()).frameTimeUs(142_857L, syncFrame = true))
    }
}
