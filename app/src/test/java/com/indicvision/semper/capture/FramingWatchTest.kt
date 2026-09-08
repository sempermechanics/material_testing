package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.FramingWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The two ways this can be wrong both cost a run: firing on a rig that never
 * moved sends the user back for a test shot they did not need, and staying quiet
 * through a re-aim carries a stale focus and a stale floor into the capture.
 */
class FramingWatchTest {

    private companion object {
        const val G = 9.80665

        /** Long enough for the smoother to settle and the persistence rule to pass. */
        const val SAMPLES = 200
    }

    /** A phone tilted [degrees] from level, at rest. */
    private fun attitude(degrees: Double): Triple<Double, Double, Double> {
        val radians = degrees * PI / 180.0
        return Triple(G * sin(radians), 0.0, G * cos(radians))
    }

    /** @return true if the watch fired anywhere in the run. */
    private fun feed(watch: FramingWatch, at: Triple<Double, Double, Double>, times: Int = SAMPLES): Boolean {
        var fired = false
        repeat(times) { if (watch.accept(at.first, at.second, at.third)) fired = true }
        return fired
    }

    @Test
    fun `a rig that does not move never fires`() {
        val watch = FramingWatch()
        assertFalse(feed(watch, attitude(0.0)))
        assertEquals(0.0, watch.movedDegrees, 1e-6)
    }

    @Test
    fun `a re-aim past the threshold fires`() {
        val watch = FramingWatch()
        feed(watch, attitude(0.0))
        assertTrue(feed(watch, attitude(6.0)))
    }

    @Test
    fun `it fires once, so the caller can treat it as an event`() {
        val watch = FramingWatch()
        feed(watch, attitude(0.0))
        assertTrue(feed(watch, attitude(30.0)))
        // Still pointing at the new place, and still nothing more to say.
        assertFalse(feed(watch, attitude(30.0)))
    }

    @Test
    fun `wander well under the threshold is not a re-frame`() {
        // A tripod is not perfectly rigid, and the app must not turn its own
        // noise into a refusal.
        val watch = FramingWatch()
        feed(watch, attitude(0.0))
        var fired = false
        repeat(SAMPLES) { i ->
            val (x, y, z) = attitude(if (i % 2 == 0) 0.4 else -0.4)
            if (watch.accept(x, y, z)) fired = true
        }
        assertFalse(fired)
    }

    @Test
    fun `a knock is dropped rather than smoothed in`() {
        // A hand on the tripod is an acceleration, not a new attitude: the
        // magnitude is nowhere near gravity, so the rest gate must discard it
        // outright. Feeding it in would swing the smoothed direction and refuse
        // a run over a nudge.
        val watch = FramingWatch()
        feed(watch, attitude(0.0))
        var fired = false
        repeat(SAMPLES) { if (watch.accept(4 * G, 0.0, 0.0)) fired = true }
        assertFalse(fired)
        assertEquals(0.0, watch.movedDegrees, 1e-6)
    }

    @Test
    fun `one stray resting sample does not fire on its own`() {
        // Persistence, separately from the rest gate: even a sample that passes
        // as "at rest" has to be confirmed by the ones after it.
        val watch = FramingWatch()
        feed(watch, attitude(0.0))
        val (x, y, z) = attitude(40.0)
        assertFalse(watch.accept(x, y, z))
    }

    @Test
    fun `reset lets the next framing become the one being held`() {
        val watch = FramingWatch()
        feed(watch, attitude(0.0))
        assertTrue(feed(watch, attitude(20.0)))
        watch.reset()
        // Held at 20 now, so staying there is not a move.
        assertFalse(feed(watch, attitude(20.0)))
        assertTrue(feed(watch, attitude(0.0)))
    }

    @Test
    fun `a dead sensor reading is not read as a re-frame`() {
        val watch = FramingWatch()
        feed(watch, attitude(0.0))
        var fired = false
        repeat(SAMPLES) { if (watch.accept(0.0, 0.0, 0.0)) fired = true }
        assertFalse(fired)
    }
}
