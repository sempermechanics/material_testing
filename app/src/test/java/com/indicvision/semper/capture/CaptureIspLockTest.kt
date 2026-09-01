package com.indicvision.semper.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import com.indicvision.semper.ui.capture.CaptureIspLock
import com.indicvision.semper.ui.capture.CaptureIspLock.HardwareLevel
import com.indicvision.semper.ui.capture.CaptureIspLock.Key
import com.indicvision.semper.ui.capture.CaptureIspLock.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lockdown policy is tested against synthetic capability profiles rather
 * than a phone, because the failure this guards against is device-specific: a
 * plan that works on the handset on the desk and takes the capture session down
 * on someone else's. Three profiles stand in for the range of real hardware.
 */
class CaptureIspLockTest {

    /** A LEGACY HAL: advertises almost nothing and must still yield a valid plan. */
    private fun legacy() = CaptureIspLock.DeviceProfile(
        hardwareLevel = HardwareLevel.LEGACY,
        manualSensor = false,
        manualPostProcessing = false,
        sceneModes = setOf(CaptureIspLock.SCENE_MODE_DISABLED),
        effectModes = setOf(CaptureIspLock.EFFECT_OFF),
        hasActiveArray = true,
        supportsZoomRatio = true,
    )

    /** Typical mid-range: OIS present, no MANUAL_SENSOR, most post-processing listed. */
    private fun limited() = CaptureIspLock.DeviceProfile(
        hardwareLevel = HardwareLevel.LIMITED,
        manualSensor = false,
        manualPostProcessing = false,
        opticalStabilizationModes = setOf(CaptureIspLock.OIS_OFF, 1),
        videoStabilizationModes = setOf(CaptureIspLock.VIDEO_STAB_OFF, 1),
        noiseReductionModes = setOf(CaptureIspLock.NR_OFF, CaptureIspLock.NR_FAST),
        edgeModes = setOf(CaptureIspLock.EDGE_OFF, CaptureIspLock.EDGE_FAST),
        tonemapModes = setOf(CaptureIspLock.TONEMAP_FAST),
        shadingModes = setOf(CaptureIspLock.SHADING_FAST),
        lensShadingMapModes = setOf(CaptureIspLock.SHADING_MAP_ON),
        aberrationModes = setOf(CaptureIspLock.ABERRATION_OFF, CaptureIspLock.ABERRATION_FAST),
        sceneModes = setOf(CaptureIspLock.SCENE_MODE_DISABLED),
        effectModes = setOf(CaptureIspLock.EFFECT_OFF),
        awbLockAvailable = true,
        supportsZoomRatio = true,
        hasActiveArray = true,
        supportsZsl = true,
    )

    /** FULL: everything DIC wants is available. */
    private fun full() = limited().copy(
        hardwareLevel = HardwareLevel.FULL,
        manualSensor = true,
        manualPostProcessing = true,
        tonemapModes = setOf(CaptureIspLock.TONEMAP_FAST, CaptureIspLock.TONEMAP_GAMMA_VALUE),
        awbModes = setOf(CaptureIspLock.AWB_MODE_OFF, 1),
    )

    // ------------------------------------------------------------------
    // Rule 1: never request a value the device did not list
    // ------------------------------------------------------------------

    @Test
    fun `no decision applies a value outside the device's own list`() {
        val profile = limited()
        val enumerated = mapOf(
            Key.OPTICAL_STABILIZATION to profile.opticalStabilizationModes,
            Key.VIDEO_STABILIZATION to profile.videoStabilizationModes,
            Key.NOISE_REDUCTION to profile.noiseReductionModes,
            Key.EDGE to profile.edgeModes,
            Key.TONEMAP to profile.tonemapModes,
            Key.SHADING to profile.shadingModes,
            Key.LENS_SHADING_MAP to profile.lensShadingMapModes,
            Key.ABERRATION_CORRECTION to profile.aberrationModes,
            Key.SCENE_MODE to profile.sceneModes,
            Key.EFFECT_MODE to profile.effectModes,
        )
        CaptureIspLock.plan(profile).applied().forEach { decision ->
            val list = enumerated[decision.key] ?: return@forEach
            val value = decision.value ?: return@forEach
            assertTrue(
                "${decision.key} would request $value, which the device does not list",
                value in list,
            )
        }
    }

    @Test
    fun `a device that lists nothing still produces an applicable plan`() {
        val bare = CaptureIspLock.DeviceProfile(
            hardwareLevel = HardwareLevel.LEGACY,
            manualSensor = false,
            manualPostProcessing = false,
        )
        val plan = CaptureIspLock.plan(bare)
        // Nothing is applied, nothing throws, and the caller still gets a plan
        // it can iterate — an empty lockdown is today's behaviour, not a crash.
        assertTrue(plan.applied().isEmpty())
        assertEquals(Key.entries.size, plan.decisions.size)
    }

    // ------------------------------------------------------------------
    // Rule 2: every lever lands on its documented fallback
    // ------------------------------------------------------------------

    @Test
    fun `noise reduction and edge prefer OFF and fall back to FAST`() {
        assertEquals(Mode.OFF, CaptureIspLock.plan(limited()).modeOf(Key.NOISE_REDUCTION))
        assertEquals(Mode.OFF, CaptureIspLock.plan(limited()).modeOf(Key.EDGE))

        val fastOnly = limited().copy(
            noiseReductionModes = setOf(CaptureIspLock.NR_FAST),
            edgeModes = setOf(CaptureIspLock.EDGE_FAST),
        )
        val plan = CaptureIspLock.plan(fastOnly)
        assertEquals(Mode.FAST, plan.modeOf(Key.NOISE_REDUCTION))
        assertEquals(Mode.FAST, plan.modeOf(Key.EDGE))
    }

    @Test
    fun `a linear tonemap needs MANUAL_POST_PROCESSING, not just the mode`() {
        // The mode is listed but the capability is absent: writing the curve
        // would be rejected, so the plan must not claim a linear response.
        val listedButUnusable = limited().copy(
            tonemapModes = setOf(CaptureIspLock.TONEMAP_FAST, CaptureIspLock.TONEMAP_GAMMA_VALUE),
            manualPostProcessing = false,
        )
        assertEquals(Mode.FAST, CaptureIspLock.plan(listedButUnusable).modeOf(Key.TONEMAP))
        assertEquals(Mode.LINEAR, CaptureIspLock.plan(full()).modeOf(Key.TONEMAP))
    }

    @Test
    fun `AWB prefers fixed gains, falls back to the lock, then to nothing`() {
        assertEquals(Mode.OFF, CaptureIspLock.plan(full()).modeOf(Key.AWB))
        assertEquals(Mode.LOCKED, CaptureIspLock.plan(limited()).modeOf(Key.AWB))
        val noLock = limited().copy(awbLockAvailable = false)
        assertEquals(Mode.UNAVAILABLE, CaptureIspLock.plan(noLock).modeOf(Key.AWB))
    }

    @Test
    fun `LEGACY does not get a scaler crop it honours inconsistently`() {
        val plan = CaptureIspLock.plan(legacy())
        assertEquals(Mode.UNAVAILABLE, plan.modeOf(Key.CROP_REGION))
        assertEquals(Mode.UNAVAILABLE, plan.modeOf(Key.ZOOM_RATIO))
        // The same device at LIMITED does get them.
        assertEquals(Mode.FULL_FRAME, CaptureIspLock.plan(limited()).modeOf(Key.CROP_REGION))
    }

    @Test
    fun `zoom ratio is skipped below API 30 even on capable hardware`() {
        val old = full().copy(supportsZoomRatio = false)
        assertEquals(Mode.UNAVAILABLE, CaptureIspLock.plan(old).modeOf(Key.ZOOM_RATIO))
        // The crop region is the fallback for the same intent and stays available.
        assertEquals(Mode.FULL_FRAME, CaptureIspLock.plan(old).modeOf(Key.CROP_REGION))
    }

    // ------------------------------------------------------------------
    // Rule 3: what the device refused is reportable
    // ------------------------------------------------------------------

    @Test
    fun `a FULL device compromises on nothing DIC asked for`() {
        // Nothing at all, including SHADING: FAST is what that key is *for*,
        // not a fallback. This drives the user-facing warning, so a device that
        // honoured everything must produce an empty list rather than a short
        // one — a spurious warning is as much a defect as a missing one.
        assertEquals(emptyList<Key>(), CaptureIspLock.plan(full()).compromised().map { it.key })
    }

    @Test
    fun `a LEGACY device reports many compromises so one warning can collapse them`() {
        val compromised = CaptureIspLock.plan(legacy()).compromised()
        assertTrue(
            "a LEGACY profile should surface several refusals, got $compromised",
            compromised.size >= LEGACY_MIN_REFUSALS,
        )
        assertTrue(compromised.any { it.key == Key.OPTICAL_STABILIZATION })
    }

    @Test
    fun `stabilisation off is reported as ideal only when the device offered it`() {
        assertTrue(
            CaptureIspLock.plan(limited()).decisions
                .first { it.key == Key.OPTICAL_STABILIZATION }.ideal,
        )
        assertFalse(
            CaptureIspLock.plan(legacy()).decisions
                .first { it.key == Key.OPTICAL_STABILIZATION }.ideal,
        )
    }

    // ------------------------------------------------------------------
    // The constants are duplicated to keep Camera2 out of the policy; if they
    // ever drift from the platform the whole lockdown silently misfires.
    // ------------------------------------------------------------------

    @Test
    fun `the mirrored Camera2 constants match the platform`() {
        assertEquals(
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            CaptureIspLock.OIS_OFF,
        )
        assertEquals(
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            CaptureIspLock.VIDEO_STAB_OFF,
        )
        assertEquals(CameraMetadata.NOISE_REDUCTION_MODE_OFF, CaptureIspLock.NR_OFF)
        assertEquals(CameraMetadata.NOISE_REDUCTION_MODE_FAST, CaptureIspLock.NR_FAST)
        assertEquals(CameraMetadata.EDGE_MODE_OFF, CaptureIspLock.EDGE_OFF)
        assertEquals(CameraMetadata.EDGE_MODE_FAST, CaptureIspLock.EDGE_FAST)
        assertEquals(CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE, CaptureIspLock.TONEMAP_CONTRAST_CURVE)
        assertEquals(CameraMetadata.TONEMAP_MODE_FAST, CaptureIspLock.TONEMAP_FAST)
        assertEquals(CameraMetadata.TONEMAP_MODE_GAMMA_VALUE, CaptureIspLock.TONEMAP_GAMMA_VALUE)
        assertEquals(CameraMetadata.SHADING_MODE_FAST, CaptureIspLock.SHADING_FAST)
        assertEquals(CameraMetadata.SHADING_MODE_HIGH_QUALITY, CaptureIspLock.SHADING_HIGH_QUALITY)
        assertEquals(
            CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON,
            CaptureIspLock.SHADING_MAP_ON,
        )
        assertEquals(
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF,
            CaptureIspLock.ABERRATION_OFF,
        )
        assertEquals(
            CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST,
            CaptureIspLock.ABERRATION_FAST,
        )
        assertEquals(CameraMetadata.CONTROL_SCENE_MODE_DISABLED, CaptureIspLock.SCENE_MODE_DISABLED)
        assertEquals(CameraMetadata.CONTROL_EFFECT_MODE_OFF, CaptureIspLock.EFFECT_OFF)
        assertEquals(CameraMetadata.CONTROL_AWB_MODE_OFF, CaptureIspLock.AWB_MODE_OFF)
    }

    @Test
    fun `hardware levels map to the raw Camera2 values, not to ordinals`() {
        // Camera2 numbers these out of order: LIMITED is 0 and LEGACY is 2. An
        // ordinal-based mapping would quietly call every LIMITED phone LEGACY.
        assertEquals(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            HardwareLevel.LEGACY.camera2Value,
        )
        assertEquals(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED,
            HardwareLevel.LIMITED.camera2Value,
        )
        assertEquals(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
            HardwareLevel.FULL.camera2Value,
        )
        assertEquals(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3,
            HardwareLevel.LEVEL_3.camera2Value,
        )
        assertEquals(
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
            HardwareLevel.EXTERNAL.camera2Value,
        )
    }

    @Test
    fun `an unknown hardware level degrades to LIMITED, never to LEGACY`() {
        // A level newer than this build is at least LIMITED by Camera2's own
        // compatibility rules; calling it LEGACY would strip settings it has.
        assertEquals(HardwareLevel.LIMITED, HardwareLevel.fromCamera2(UNKNOWN_LEVEL))
        assertEquals(HardwareLevel.LIMITED, HardwareLevel.fromCamera2(null))
        assertEquals(
            HardwareLevel.LEGACY,
            HardwareLevel.fromCamera2(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY),
        )
        assertNotNull(HardwareLevel.fromCamera2(UNKNOWN_LEVEL))
    }

    private companion object {
        const val LEGACY_MIN_REFUSALS = 6
        const val UNKNOWN_LEVEL = 99
    }

    // ------------------------------------------------------------------
    // Zero-shutter-lag
    // ------------------------------------------------------------------

    @Test
    fun `a device that offers zero-shutter-lag is asked to switch it off`() {
        assertEquals(Mode.OFF, CaptureIspLock.plan(full()).modeOf(Key.ZERO_SHUTTER_LAG))
    }

    @Test
    fun `a device that does not list the key is left alone and not warned about`() {
        // Unlisted means the pipeline is not serving stills from a ring buffer,
        // so there is nothing to switch off. Writing the key anyway would break
        // the never-request-an-unlisted-value rule, and reporting it as
        // compromised would warn most phones about a setting that was never on.
        val plan = CaptureIspLock.plan(full().copy(supportsZsl = false))
        assertEquals(Mode.UNAVAILABLE, plan.modeOf(Key.ZERO_SHUTTER_LAG))
        assertTrue(plan.applied().none { it.key == Key.ZERO_SHUTTER_LAG })
        assertTrue(plan.compromised().none { it.key == Key.ZERO_SHUTTER_LAG })
    }
}
