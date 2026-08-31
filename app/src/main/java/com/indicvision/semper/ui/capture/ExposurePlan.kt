package com.indicvision.semper.ui.capture

/**
 * Turns a converged auto-exposure into a frozen one that is flicker-safe by
 * construction.
 *
 * The reason the capture path historically left AE running is sound: freezing
 * whatever duration AE happened to land on is *not* flicker-safe, and under
 * mains-powered lighting a wrong frozen duration stays wrong for every frame
 * after it. That objection has a better answer than leaving AE free, which
 * re-converges exposure and ISO between frames and so moves both the brightness
 * and the noise level of every image the engine correlates.
 *
 * The answer is to round the exposure **up** to a whole number of mains
 * half-cycles. A lamp on 50 Hz mains peaks 100 times a second, so any exposure
 * that is an exact multiple of 10 ms integrates the same total light regardless
 * of when the shutter opens; on 60 Hz the period is 8.333 ms. When the device
 * will not say which mains it is under, [BOTH_SAFE_EXPOSURE_NS] (50 ms) is
 * simultaneously 5 × 10 ms and 6 × 8.333 ms and so is safe under either.
 *
 * Brightness is held by scaling ISO down by the same ratio the exposure grew.
 * That is a second win on its own: sensor read noise falls with ISO, `D(η)`
 * falls with it, and displacement noise scales with `√D(η)`.
 *
 * No Camera2 types here — the caller reads the converged values out of a
 * `TotalCaptureResult` and applies the result, so the arithmetic stays testable.
 */
object ExposurePlan {

    /** Mains frequency as the device resolved it, via its antibanding mode. */
    enum class Mains { HZ_50, HZ_60, UNKNOWN }

    /** How the plan will actually be applied, weakest last. */
    enum class Lock {
        /** `CONTROL_AE_MODE_OFF` with explicit exposure and ISO. Needs MANUAL_SENSOR. */
        MANUAL,

        /** `CONTROL_AE_LOCK`: the device holds its own converged values. */
        AE_LOCK,

        /** Neither available — AE keeps running, exactly as before this work. */
        AUTO,
    }

    /** The device's own limits, all read from `CameraCharacteristics`. */
    data class SensorLimits(
        val minExposureNs: Long,
        val maxExposureNs: Long,
        val minSensitivity: Int,
        val maxSensitivity: Int,
        val manualSensor: Boolean,
        val aeLockAvailable: Boolean,
    )

    /** What AE settled on, read back from the capture result. */
    data class Converged(
        val exposureNs: Long,
        val sensitivity: Int,
        val mains: Mains,
    )

    /**
     * The exposure to apply, and how.
     *
     * [flickerSafe] is false when the rounded exposure had to be clamped back
     * outside the device's range — the plan is still the best available, but the
     * caller must not claim flicker immunity it does not have. The burst in the
     * noise-floor check measures whether it actually held.
     */
    data class Result(
        val exposureNs: Long,
        val sensitivity: Int,
        val frameDurationNs: Long,
        val lock: Lock,
        val mains: Mains,
        val flickerSafe: Boolean,
        /**
         * How much brighter than AE chose this plan will expose: 1.0 when ISO
         * absorbed the whole exposure increase, higher when it could not.
         *
         * Growing the exposure onto the flicker grid is paid for by dividing
         * ISO by the same factor — until ISO hits base, after which there is
         * nothing left to pay with and the frame simply gets brighter. That is
         * usually the right trade, but only usually: the claim that "an
         * over-bright frame still correlates" holds for bright and fails for
         * **clipped**, and a clipped pixel has no gradient at all, which is the
         * one thing correlation cannot recover from. Speckle is high-contrast
         * by design, so its white dots are the first thing to go.
         *
         * Reported rather than acted on, because how much headroom the scene
         * actually has is not knowable from the exposure numbers alone — the
         * burst measures the consequence directly. Neither test device reached
         * it (ISO 81/73/50/44 all scaled cleanly), so this is a latent case
         * being made visible, not a live one being papered over.
         */
        val overExposureFactor: Double = 1.0,
    ) {
        /** Ceiling on frame rate implied by the exposure alone, frames/second. */
        val maxFps: Double
            get() = if (frameDurationNs <= 0L) 0.0 else NANOS_PER_SECOND.toDouble() / frameDurationNs

        /** True when ISO could not absorb the whole exposure increase. */
        val overExposed: Boolean get() = overExposureFactor > 1.0 + OVER_EXPOSURE_EPSILON
    }

    /**
     * Round [converged] up onto a flicker-safe boundary and rebalance ISO.
     *
     * Returns a plan whose [Result.lock] is [Lock.AUTO] — today's behaviour —
     * when the device supports neither manual sensor control nor an AE lock.
     */
    fun plan(converged: Converged, limits: SensorLimits): Result {
        val lock = when {
            limits.manualSensor -> Lock.MANUAL
            limits.aeLockAvailable -> Lock.AE_LOCK
            else -> Lock.AUTO
        }
        if (lock != Lock.MANUAL) {
            // Without manual control there is nothing to round: the device holds
            // (or keeps re-deriving) its own exposure. Report it unchanged and
            // do not claim it is flicker-safe, because nothing made it so.
            return Result(
                exposureNs = converged.exposureNs,
                sensitivity = converged.sensitivity,
                frameDurationNs = converged.exposureNs.coerceAtLeast(1L),
                lock = lock,
                mains = converged.mains,
                flickerSafe = false,
            )
        }

        val period = halfPeriodNs(converged.mains)
        val target = roundUpToMultiple(converged.exposureNs, period)
        val exposure = target.coerceIn(limits.minExposureNs, limits.maxExposureNs)

        // Brightness is exposure × gain, so growing the exposure by a factor
        // means dividing ISO by the same factor. Clamping ISO can leave the
        // frame brighter than AE chose; that is usually the right trade,
        // because a flickering frame is worse than a bright one — but how much
        // brighter is not free information, so it is measured and reported
        // rather than assumed harmless. See [Result.overExposureFactor].
        val ratio = if (converged.exposureNs > 0L) {
            exposure.toDouble() / converged.exposureNs.toDouble()
        } else {
            1.0
        }
        val scaled = if (ratio > 0.0) (converged.sensitivity / ratio).toInt() else converged.sensitivity
        val sensitivity = scaled.coerceIn(limits.minSensitivity, limits.maxSensitivity)
        // Only a clamp *upward* over-exposes: ISO wanted to go lower than the
        // sensor allows, so the extra light stays in the frame. A downward
        // clamp (scaled above maxSensitivity) under-exposes instead, which
        // costs signal-to-noise but never destroys a gradient.
        val overExposure = if (scaled in 1 until sensitivity) {
            sensitivity.toDouble() / scaled.toDouble()
        } else {
            1.0
        }

        return Result(
            exposureNs = exposure,
            sensitivity = sensitivity,
            // Pinned so the sensor cannot stretch the gap between frames and
            // reintroduce the variability the lock just removed.
            frameDurationNs = exposure.coerceAtLeast(1L),
            lock = Lock.MANUAL,
            mains = converged.mains,
            // Only a whole number of half-cycles is safe. Clamping to the
            // device's range can land off that grid, and then it is not.
            flickerSafe = exposure == target && isMultipleOf(exposure, period),
            overExposureFactor = overExposure,
        )
    }

    /** Mains half-period in nanoseconds; the both-safe value when unresolved. */
    fun halfPeriodNs(mains: Mains): Long = when (mains) {
        Mains.HZ_50 -> HALF_PERIOD_50HZ_NS
        Mains.HZ_60 -> HALF_PERIOD_60HZ_NS
        // 50 ms is 5 x 10 ms and 6 x 8.333 ms: a whole number of half-cycles
        // under either mains, so no guess about the local grid is needed.
        Mains.UNKNOWN -> BOTH_SAFE_EXPOSURE_NS
    }

    /** Smallest multiple of [multiple] that is at least [value]; never zero. */
    fun roundUpToMultiple(value: Long, multiple: Long): Long = when {
        multiple <= 0L -> value
        value <= 0L -> multiple
        else -> ((value + multiple - 1) / multiple) * multiple
    }

    /**
     * True when [value] is a whole number of [multiple]s.
     *
     * The 60 Hz half-period is 8333333.33 ns, which is not an integer, so exact
     * division would reject every real exposure. [FLICKER_TOLERANCE_NS] is the
     * rounding slack — well under a percent of a half-cycle, far tighter than
     * any shutter this runs on.
     */
    fun isMultipleOf(value: Long, multiple: Long): Boolean {
        if (multiple <= 0L) return false
        val remainder = value % multiple
        return remainder <= FLICKER_TOLERANCE_NS || multiple - remainder <= FLICKER_TOLERANCE_NS
    }

    /** Map a Camera2 `CONTROL_AE_ANTIBANDING_MODE` result to a mains frequency. */
    fun mainsFromAntibanding(mode: Int?): Mains = when (mode) {
        ANTIBANDING_50HZ -> Mains.HZ_50
        ANTIBANDING_60HZ -> Mains.HZ_60
        // AUTO means the device is handling banding itself but will not say on
        // what grid; OFF and null say nothing at all. Both are UNKNOWN, and the
        // both-safe exposure covers them.
        else -> Mains.UNKNOWN
    }

    /** 10 ms: half a cycle of 50 Hz mains. */
    const val HALF_PERIOD_50HZ_NS = 10_000_000L

    /** 8.333 ms: half a cycle of 60 Hz mains, to the nearest nanosecond. */
    const val HALF_PERIOD_60HZ_NS = 8_333_333L

    /** 50 ms — a whole number of half-cycles on both 50 Hz and 60 Hz. */
    const val BOTH_SAFE_EXPOSURE_NS = 50_000_000L

    /** Integer ISO division leaves a fraction of a percent; not over-exposure. */
    private const val OVER_EXPOSURE_EPSILON = 0.01

    private const val FLICKER_TOLERANCE_NS = 50_000L
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val ANTIBANDING_50HZ = 1
    private const val ANTIBANDING_60HZ = 2
}
