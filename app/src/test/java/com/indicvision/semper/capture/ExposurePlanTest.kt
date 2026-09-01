package com.indicvision.semper.capture

import android.hardware.camera2.CameraMetadata
import com.indicvision.semper.ui.capture.ExposurePlan
import com.indicvision.semper.ui.capture.ExposurePlan.Lock
import com.indicvision.semper.ui.capture.ExposurePlan.Mains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezing the exposure is only an improvement if the frozen value is a whole
 * number of mains half-cycles; a frozen exposure that is *not* stays wrong for
 * every frame after it, which is exactly why the capture path left AE running
 * before this. These tests pin that arithmetic.
 */
class ExposurePlanTest {

    /** A capable sensor; each test narrows one field with [ExposurePlan.SensorLimits.copy]. */
    private fun limits() = ExposurePlan.SensorLimits(
        minExposureNs = 20_000L,
        maxExposureNs = 200_000_000L,
        minSensitivity = 50,
        maxSensitivity = 3200,
        manualSensor = true,
        aeLockAvailable = true,
    )

    // ------------------------------------------------------------------
    // Rounding onto the flicker grid
    // ------------------------------------------------------------------

    @Test
    fun `50 Hz rounds up to a whole number of 10 ms half-cycles`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 12_000_000L, sensitivity = 400, mains = Mains.HZ_50),
            limits(),
        )
        assertEquals(20_000_000L, result.exposureNs)
        assertTrue(result.flickerSafe)
    }

    @Test
    fun `60 Hz rounds up onto the 8_333 microsecond grid`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 10_000_000L, sensitivity = 400, mains = Mains.HZ_60),
            limits(),
        )
        // Two half-cycles: 16.67 ms, the first multiple at or above 10 ms.
        assertEquals(2 * ExposurePlan.HALF_PERIOD_60HZ_NS, result.exposureNs)
        assertTrue(result.flickerSafe)
    }

    @Test
    fun `an exposure already on the grid is left alone`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 30_000_000L, sensitivity = 200, mains = Mains.HZ_50),
            limits(),
        )
        assertEquals(30_000_000L, result.exposureNs)
        assertEquals(200, result.sensitivity)
        assertTrue(result.flickerSafe)
    }

    @Test
    fun `unknown mains yields the 50 ms both-safe exposure, not a guess`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 4_000_000L, sensitivity = 800, mains = Mains.UNKNOWN),
            limits(),
        )
        assertEquals(ExposurePlan.BOTH_SAFE_EXPOSURE_NS, result.exposureNs)
        assertTrue(result.flickerSafe)
        // 50 ms is a whole number of half-cycles under either mains, which is
        // the entire reason it is the fallback.
        assertTrue(ExposurePlan.isMultipleOf(result.exposureNs, ExposurePlan.HALF_PERIOD_50HZ_NS))
        assertTrue(ExposurePlan.isMultipleOf(result.exposureNs, ExposurePlan.HALF_PERIOD_60HZ_NS))
    }

    // ------------------------------------------------------------------
    // Holding brightness
    // ------------------------------------------------------------------

    @Test
    fun `ISO scales down by the same ratio the exposure grew`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 10_000_000L, sensitivity = 800, mains = Mains.HZ_50),
            limits(),
        )
        // Exposure is unchanged at 10 ms, so ISO must be too.
        assertEquals(10_000_000L, result.exposureNs)
        assertEquals(800, result.sensitivity)

        val doubled = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 11_000_000L, sensitivity = 800, mains = Mains.HZ_50),
            limits(),
        )
        // 11 ms rounds to 20 ms, a factor of 1.818, so ISO drops to ~440.
        assertEquals(20_000_000L, doubled.exposureNs)
        assertEquals(440, doubled.sensitivity)
    }

    @Test
    fun `ISO never falls below the sensor's floor`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 1_000_000L, sensitivity = 100, mains = Mains.UNKNOWN),
            limits().copy(minSensitivity = 50),
        )
        // A 50x exposure increase would want ISO 2; the floor is 50.
        assertEquals(50, result.sensitivity)
    }

    // ------------------------------------------------------------------
    // Honesty when the device cannot deliver
    // ------------------------------------------------------------------

    @Test
    fun `an exposure clamped outside the sensor range is not claimed flicker-safe`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 30_000_000L, sensitivity = 100, mains = Mains.HZ_50),
            limits().copy(maxExposureNs = 33_000_000L),
        )
        // 30 ms was already on the grid, so this one is safe...
        assertTrue(result.flickerSafe)

        val clamped = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 31_000_000L, sensitivity = 100, mains = Mains.HZ_50),
            limits().copy(maxExposureNs = 33_000_000L),
        )
        // ...but 31 ms wants 40 ms, which the sensor cannot reach. The plan
        // still applies the best it can and must not claim immunity for it.
        assertEquals(33_000_000L, clamped.exposureNs)
        assertFalse(clamped.flickerSafe)
    }

    @Test
    fun `no MANUAL_SENSOR falls back to the AE lock and claims nothing`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 12_000_000L, sensitivity = 400, mains = Mains.HZ_50),
            limits().copy(manualSensor = false),
        )
        assertEquals(Lock.AE_LOCK, result.lock)
        assertEquals(12_000_000L, result.exposureNs)
        assertFalse(result.flickerSafe)
    }

    @Test
    fun `a device with neither lock keeps today's behaviour`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 12_000_000L, sensitivity = 400, mains = Mains.HZ_50),
            limits().copy(manualSensor = false, aeLockAvailable = false),
        )
        assertEquals(Lock.AUTO, result.lock)
        assertFalse(result.flickerSafe)
    }

    // ------------------------------------------------------------------
    // Frame-rate honesty: the exposure floor caps the achievable rate
    // ------------------------------------------------------------------

    @Test
    fun `the both-safe exposure caps the frame rate at 20 fps`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 3_000_000L, sensitivity = 400, mains = Mains.UNKNOWN),
            limits(),
        )
        assertEquals(FPS_AT_50MS, result.maxFps, FPS_TOLERANCE)
    }

    // ------------------------------------------------------------------
    // Edge cases that must not divide by zero or loop
    // ------------------------------------------------------------------

    @Test
    fun `a zero converged exposure does not divide by zero`() {
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 0L, sensitivity = 400, mains = Mains.HZ_50),
            limits(),
        )
        assertTrue(result.exposureNs > 0L)
        assertTrue(result.frameDurationNs > 0L)
    }

    @Test
    fun `rounding is a no-op for a non-positive period`() {
        assertEquals(1234L, ExposurePlan.roundUpToMultiple(1234L, 0L))
        assertFalse(ExposurePlan.isMultipleOf(1234L, 0L))
    }

    @Test
    fun `antibanding maps to mains, and anything ambiguous maps to unknown`() {
        assertEquals(
            Mains.HZ_50,
            ExposurePlan.mainsFromAntibanding(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ),
        )
        assertEquals(
            Mains.HZ_60,
            ExposurePlan.mainsFromAntibanding(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ),
        )
        // AUTO handles banding but will not say on what grid, so it is unknown.
        assertEquals(
            Mains.UNKNOWN,
            ExposurePlan.mainsFromAntibanding(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO),
        )
        assertEquals(
            Mains.UNKNOWN,
            ExposurePlan.mainsFromAntibanding(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF),
        )
        assertEquals(Mains.UNKNOWN, ExposurePlan.mainsFromAntibanding(null))
    }

    // ------------------------------------------------------------------
    // Over-exposure when ISO cannot absorb the whole exposure increase
    // ------------------------------------------------------------------

    @Test
    fun `iso absorbing the exposure increase is not over-exposure`() {
        // 12 ms at ISO 400 rounds to 20 ms, so ISO wants 240 — well above the
        // sensor's base of 50, and the frame stays as bright as AE metered it.
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 12_000_000L, sensitivity = 400, mains = Mains.HZ_50),
            limits(),
        )
        assertEquals(240, result.sensitivity)
        assertEquals(1.0, result.overExposureFactor, FACTOR_TOLERANCE)
        assertFalse(result.overExposed)
    }

    @Test
    fun `iso clamped at base reports how much brighter the frames will be`() {
        // 12 ms at ISO 60 rounds to 20 ms, so ISO wants 36 — below this
        // sensor's base of 50. The extra light has nowhere to go and stays in
        // the frame, which a clipped speckle dot cannot be recovered from.
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 12_000_000L, sensitivity = 60, mains = Mains.HZ_50),
            limits(),
        )
        assertEquals(50, result.sensitivity)
        assertTrue(result.overExposed)
        assertEquals(50.0 / 36.0, result.overExposureFactor, FACTOR_TOLERANCE)
    }

    @Test
    fun `a downward iso clamp is not reported as over-exposure`() {
        // Under-exposure costs signal-to-noise; it never destroys a gradient,
        // so it is not the thing this factor exists to warn about.
        val result = ExposurePlan.plan(
            ExposurePlan.Converged(exposureNs = 1_000_000L, sensitivity = 3200, mains = Mains.HZ_50),
            limits().copy(maxSensitivity = 100),
        )
        assertEquals(100, result.sensitivity)
        assertFalse(result.overExposed)
        assertEquals(1.0, result.overExposureFactor, FACTOR_TOLERANCE)
    }

    private companion object {
        const val FPS_AT_50MS = 20.0
        const val FPS_TOLERANCE = 0.01
        const val FACTOR_TOLERANCE = 0.01
    }
}
