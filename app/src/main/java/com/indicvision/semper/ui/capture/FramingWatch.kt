package com.indicvision.semper.ui.capture

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Watches the direction of gravity for a change big enough to mean the camera
 * is pointing somewhere else.
 *
 * Between the noise burst passing and the user tapping Start, the run is
 * carrying two things measured through one framing: the focus distance, frozen
 * at a plane a particular distance away, and the noise floor, measured on the
 * light and pattern that were in the frame. Re-aim the rig in that window and
 * both describe a scene that is no longer there — silently, because nothing
 * downstream re-checks either.
 *
 * Pure JVM: the caller feeds it raw sensor triples and it answers with a verdict,
 * so the persistence rule and the rest gate below are testable without a device.
 *
 * **Three filters stand between a reading and a verdict**, and each removes a
 * different false alarm:
 *
 * 1. **The rest gate.** Only samples whose magnitude is close to gravity are used
 *    at all. A hand brushing the tripod is an *acceleration*, not a new attitude,
 *    and it shows up as a magnitude far from 9.81 — so it is dropped rather than
 *    smoothed in. On a device with a real `TYPE_GRAVITY` sensor this is already
 *    true of every sample and the gate costs nothing.
 * 2. **Smoothing.** The direction is low-passed, so the reference is a settled
 *    attitude rather than one instant of it.
 * 3. **Persistence.** The angle has to stay over the threshold for several
 *    consecutive samples. A re-aimed tripod stays re-aimed; noise does not.
 *
 * **On the threshold.** Two degrees is not a measured number and the error is
 * deliberately on the sensitive side: a false alarm costs the user one more test
 * shot, while a miss carries a stale focus and a stale floor into a run that
 * cannot be repeated. At a working standoff of a couple of hundred millimetres,
 * two degrees moves the field by several millimetres — a genuine re-frame, well
 * clear of the sub-degree wander a tripod shows. It is a device-verification
 * item, not a settled constant.
 *
 * **What this does not catch:** sliding the rig without tilting it, which changes
 * the standoff while gravity holds still. That case is left to the person, who
 * has just been asked to look at the framing, rather than guessed at with a
 * second threshold nothing has measured.
 */
internal class FramingWatch {

    private var smoothed: DoubleArray? = null
    private var reference: DoubleArray? = null
    private var overThreshold = 0
    private var fired = false

    /** Angle between the current attitude and the one being held to, in degrees. */
    var movedDegrees: Double = 0.0
        private set

    /** Forget everything; the next resting sample becomes the new reference. */
    fun reset() {
        smoothed = null
        reference = null
        overThreshold = 0
        fired = false
        movedDegrees = 0.0
    }

    /**
     * Feed one sensor triple.
     *
     * @return true exactly once, on the sample that first confirms the camera
     *   has been re-aimed. Further samples return false, so the caller acts on it
     *   as an event and does not have to de-duplicate.
     */
    fun accept(x: Double, y: Double, z: Double): Boolean {
        if (fired || !isResting(x, y, z)) return false
        val next = smooth(doubleArrayOf(x, y, z))
        smoothed = next
        val held = reference ?: next.copyOf().also { reference = it }
        movedDegrees = angleDegrees(held, next)
        overThreshold = if (movedDegrees > MOVED_DEGREES) overThreshold + 1 else 0
        fired = overThreshold >= MOVED_SAMPLES
        return fired
    }

    private fun smooth(sample: DoubleArray): DoubleArray {
        val previous = smoothed ?: return sample
        return DoubleArray(AXES) { previous[it] + SMOOTHING * (sample[it] - previous[it]) }
    }

    private companion object {
        const val AXES = 3

        /** Standard gravity; only the tolerance around it matters here. */
        const val GRAVITY = 9.80665

        /** How far off gravity a sample may be and still count as "at rest". */
        const val REST_TOLERANCE = 0.1

        const val SMOOTHING = 0.15
        const val MOVED_DEGREES = 2.0

        /** Consecutive confirming samples. At the UI sensor rate, about half a second. */
        const val MOVED_SAMPLES = 5

        fun isResting(x: Double, y: Double, z: Double): Boolean {
            val magnitude = sqrt(x * x + y * y + z * z)
            return abs(magnitude - GRAVITY) <= GRAVITY * REST_TOLERANCE
        }

        /** Zero for a degenerate vector: an unreadable sensor is not a re-frame. */
        fun angleDegrees(a: DoubleArray, b: DoubleArray): Double {
            val lengths = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]) *
                sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
            if (lengths <= 0.0) return 0.0
            val cosine = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / lengths
            return Math.toDegrees(acos(cosine.coerceIn(-1.0, 1.0)))
        }
    }
}
