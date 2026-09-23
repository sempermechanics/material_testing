@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sampling sheet promises a frame count before extraction runs, so these
 * pin that the promise and the frames sampled are the same number.
 */
class VideoSamplingTest {

    @Test
    fun `a whole clip samples every frame once, the last at its own start`() {
        // 20 frames at 5 fps: frames start at 0, 200 … 3800 ms; nothing starts at 4000.
        val last = VideoSampling.lastFrameStartMs(durationMs = 4000, fps = 5.0, fpsKnown = true)
        val times = VideoSampling.sampleTimesMs(startMs = 0, endMs = last, fpsExtract = 5.0, maxFrames = 50)

        assertEquals(3800L, last)
        assertEquals(20, times.size)
        assertEquals(3800.0, times.last(), 1e-9)
    }

    @Test
    fun `sampling at a lower rate than the source spreads over the same span`() {
        val last = VideoSampling.lastFrameStartMs(durationMs = 4000, fps = 5.0, fpsKnown = true)

        assertEquals(listOf(0.0, 1000.0, 2000.0, 3000.0), VideoSampling.sampleTimesMs(0, last, 1.0, 50))
    }

    @Test
    fun `a segment that ends before the last frame is sampled as dragged`() {
        assertEquals(11, VideoSampling.sampleTimesMs(startMs = 0, endMs = 2000, fpsExtract = 5.0, maxFrames = 50).size)
        assertEquals(
            listOf(1000.0, 1200.0, 1400.0),
            VideoSampling.sampleTimesMs(startMs = 1000, endMs = 1400, fpsExtract = 5.0, maxFrames = 50),
        )
    }

    @Test
    fun `the frame cap and an empty segment both hold`() {
        assertEquals(50, VideoSampling.sampleTimesMs(0, 60_000, 30.0, maxFrames = 50).size)
        assertEquals(listOf(3800.0), VideoSampling.sampleTimesMs(3800, 3800, 5.0, 50))
        assertEquals(listOf(3800.0), VideoSampling.sampleTimesMs(3800, 1000, 5.0, 50))
    }

    @Test
    fun `an NTSC rate rounds to its frame length and an unknown rate stops a millisecond short`() {
        // 300 frames at 29.97 fps last 10 010 ms; a frame lasts 33.37 ms.
        assertEquals(9977L, VideoSampling.lastFrameStartMs(10_010, 29.97, fpsKnown = true))
        assertEquals(3999L, VideoSampling.lastFrameStartMs(4000, 30.0, fpsKnown = false))
        assertEquals(0L, VideoSampling.lastFrameStartMs(10, 5.0, fpsKnown = true))
    }
}
