package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.StillSequenceRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StillSequenceRunnerTest {

    @Test
    fun `fires every frame and never skips late ones`() = runBlocking {
        var now = 0L
        val fireTimes = mutableListOf<Long>()
        val runner = StillSequenceRunner(
            fps = 2,
            frameCount = 3,
            clockMs = { now },
            delayMs = { ms -> now += ms },
        )
        val result = runner.run(
            sink = object : StillSequenceRunner.CaptureSink {
                override suspend fun capture(index: Int): String? {
                    fireTimes.add(now)
                    // Simulate a capture that takes longer than the interval on frame 1.
                    if (index == 1) now += 800
                    return "frame_$index"
                }
            },
            t0Ms = 0L,
        )
        assertTrue(result.completed)
        assertEquals(listOf("frame_0", "frame_1", "frame_2"), result.paths)
        // Frame 0 at t=0, frame 1 due at 500 but runs then, frame 2 due at 1000 —
        // after late frame 1 (now=1300) it fires immediately.
        assertEquals(0L, fireTimes[0])
        assertEquals(500L, fireTimes[1])
        assertTrue(fireTimes[2] >= 1000L)
    }

    @Test
    fun `retries null capture on the same index`() = runBlocking {
        var attempts = 0
        val runner = StillSequenceRunner(
            fps = 1,
            frameCount = 1,
            clockMs = { 0L },
            delayMs = { },
        )
        val result = runner.run(
            sink = object : StillSequenceRunner.CaptureSink {
                override suspend fun capture(index: Int): String? {
                    attempts++
                    return if (attempts < 3) null else "ok"
                }
            },
        )
        assertTrue(result.completed)
        assertEquals(listOf("ok"), result.paths)
        assertEquals(3, attempts)
    }

    @Test
    fun `dueAtMs follows fps`() {
        val runner = StillSequenceRunner(fps = 5, frameCount = 10)
        assertEquals(0L, runner.dueAtMs(0L, 0))
        assertEquals(200L, runner.dueAtMs(0L, 1))
        assertEquals(1000L, runner.dueAtMs(0L, 5))
    }
}
