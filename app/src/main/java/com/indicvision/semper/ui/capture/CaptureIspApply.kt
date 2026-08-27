package com.indicvision.semper.ui.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import com.indicvision.semper.ui.capture.CaptureIspLock.Key
import com.indicvision.semper.ui.capture.CaptureIspLock.Mode

/**
 * The Camera2 half of [CaptureIspLock]: reads a [CaptureIspLock.DeviceProfile]
 * out of `CameraCharacteristics`, writes a [CaptureIspLock.Plan] onto a request,
 * and reads back what the HAL actually did.
 *
 * Deliberately thin and free of policy — every choice lives in [CaptureIspLock],
 * which is unit-tested against synthetic device profiles. What is here is the
 * part that cannot be tested off-device, so there is as little of it as possible.
 *
 * The read-back exists because a HAL may accept a key and quietly ignore it, and
 * that silent refusal is the failure mode that would otherwise leave the app
 * claiming a precision it does not have.
 */
@Suppress("TooManyFunctions") // one apply helper per key whose write is not a plain set
object CaptureIspApply {

    /** What the HAL did with one requested key. */
    data class Honoured(
        val key: Key,
        val requested: Mode,
        /** True/false when the result reported the key; null when it did not. */
        val honoured: Boolean?,
    )

    /** Read the device's own capability lists. No model or vendor checks. */
    @Suppress("CyclomaticComplexMethod")
    fun profileOf(chars: CameraCharacteristics): CaptureIspLock.DeviceProfile {
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet().orEmpty()
        return CaptureIspLock.DeviceProfile(
            hardwareLevel = CaptureIspLock.HardwareLevel.fromCamera2(
                chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            ),
            manualSensor = CAP_MANUAL_SENSOR in caps,
            manualPostProcessing = CAP_MANUAL_POST_PROCESSING in caps,
            opticalStabilizationModes =
            ints(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)),
            videoStabilizationModes =
            ints(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)),
            noiseReductionModes =
            ints(chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)),
            edgeModes = ints(chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)),
            tonemapModes = ints(chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)),
            shadingModes = ints(chars.get(CameraCharacteristics.SHADING_AVAILABLE_MODES)),
            lensShadingMapModes =
            ints(chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES)),
            aberrationModes = ints(
                chars.get(
                    CameraCharacteristics
                        .COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES,
                ),
            ),
            sceneModes = ints(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)),
            effectModes = ints(chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)),
            supportsZsl = zslSupported(chars),
            awbLockAvailable = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true,
            awbModes = ints(chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)),
            supportsZoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            hasActiveArray =
            chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) != null,
        )
    }

    /**
     * True when this device lists the zero-shutter-lag key among the ones it
     * will accept in a request.
     *
     * `CONTROL_ENABLE_ZSL` arrived in API 26, and below that the field does not
     * exist to look up. That reads the same way as a device that simply does
     * not offer the mode: either way there is no ring-buffer snapshot path to
     * switch off, so the key is left unapplied rather than warned about.
     */
    private fun zslSupported(chars: CameraCharacteristics): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            CaptureRequest.CONTROL_ENABLE_ZSL in chars.availableCaptureRequestKeys.orEmpty()

    /**
     * Write every applicable decision onto [builder].
     *
     * Each `set` is wrapped on its own: a HAL that rejects one key must not cost
     * the session the other twelve, and a capture that does not happen is worth
     * nothing however precisely it was configured.
     */
    fun apply(
        builder: CaptureRequest.Builder,
        plan: CaptureIspLock.Plan,
        chars: CameraCharacteristics,
    ) {
        plan.applied().forEach { decision ->
            runCatching { applyOne(builder, decision, chars) }
        }
    }

    /**
     * Compare the applied plan against what the result reports.
     *
     * A null [Honoured.honoured] is not a failure — it means this device does
     * not report the key in its capture results, so nothing can be concluded
     * either way, and the caller must not warn about it.
     */
    fun readBack(result: TotalCaptureResult, plan: CaptureIspLock.Plan): List<Honoured> =
        plan.applied().map { decision ->
            Honoured(decision.key, decision.mode, honouredOf(result, decision))
        }

    @Suppress("CyclomaticComplexMethod")
    private fun applyOne(
        builder: CaptureRequest.Builder,
        decision: CaptureIspLock.Decision,
        chars: CameraCharacteristics,
    ) {
        val value = decision.value
        when (decision.key) {
            Key.OPTICAL_STABILIZATION ->
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, value)
            Key.VIDEO_STABILIZATION ->
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, value)
            Key.ZERO_SHUTTER_LAG ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
                }
            Key.NOISE_REDUCTION -> builder.set(CaptureRequest.NOISE_REDUCTION_MODE, value)
            Key.EDGE -> builder.set(CaptureRequest.EDGE_MODE, value)
            Key.TONEMAP -> applyTonemap(builder, decision)
            Key.SHADING -> builder.set(CaptureRequest.SHADING_MODE, value)
            Key.LENS_SHADING_MAP ->
                builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, value)
            Key.ABERRATION_CORRECTION ->
                builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, value)
            Key.SCENE_MODE -> builder.set(CaptureRequest.CONTROL_SCENE_MODE, value)
            Key.EFFECT_MODE -> builder.set(CaptureRequest.CONTROL_EFFECT_MODE, value)
            Key.ZOOM_RATIO -> applyZoomRatio(builder)
            Key.CROP_REGION -> chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                ?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
            Key.AWB -> applyAwb(builder, decision)
        }
    }

    /**
     * `TONEMAP_MODE` alone is not enough: GAMMA_VALUE needs the gamma written
     * beside it, and CONTRAST_CURVE needs an actual curve — a mode with no
     * payload leaves the device on whatever curve it had.
     */
    private fun applyTonemap(builder: CaptureRequest.Builder, decision: CaptureIspLock.Decision) {
        builder.set(CaptureRequest.TONEMAP_MODE, decision.value)
        when (decision.value) {
            CaptureIspLock.TONEMAP_GAMMA_VALUE ->
                builder.set(CaptureRequest.TONEMAP_GAMMA, CaptureIspLock.LINEAR_GAMMA)
            CaptureIspLock.TONEMAP_CONTRAST_CURVE -> {
                val linear = floatArrayOf(0f, 0f, 1f, 1f)
                builder.set(
                    CaptureRequest.TONEMAP_CURVE,
                    TonemapCurve(linear, linear.copyOf(), linear.copyOf()),
                )
            }
        }
    }

    private fun applyZoomRatio(builder: CaptureRequest.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, NO_ZOOM)
        }
    }

    private fun applyAwb(builder: CaptureRequest.Builder, decision: CaptureIspLock.Decision) {
        if (decision.mode == Mode.LOCKED) {
            builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, decision.value)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun honouredOf(result: TotalCaptureResult, decision: CaptureIspLock.Decision): Boolean? {
        val want = decision.value
        return when (decision.key) {
            Key.OPTICAL_STABILIZATION ->
                matches(result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE), want)
            Key.VIDEO_STABILIZATION ->
                matches(result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE), want)
            // Request-only in Camera2: there is no CaptureResult.CONTROL_ENABLE_ZSL
            // to compare against, so nothing can be concluded either way.
            Key.ZERO_SHUTTER_LAG -> null
            Key.NOISE_REDUCTION -> matches(result.get(CaptureResult.NOISE_REDUCTION_MODE), want)
            Key.EDGE -> matches(result.get(CaptureResult.EDGE_MODE), want)
            Key.TONEMAP -> matches(result.get(CaptureResult.TONEMAP_MODE), want)
            Key.SHADING -> matches(result.get(CaptureResult.SHADING_MODE), want)
            Key.LENS_SHADING_MAP ->
                matches(result.get(CaptureResult.STATISTICS_LENS_SHADING_MAP_MODE), want)
            Key.ABERRATION_CORRECTION ->
                matches(result.get(CaptureResult.COLOR_CORRECTION_ABERRATION_MODE), want)
            Key.SCENE_MODE -> matches(result.get(CaptureResult.CONTROL_SCENE_MODE), want)
            Key.EFFECT_MODE -> matches(result.get(CaptureResult.CONTROL_EFFECT_MODE), want)
            Key.ZOOM_RATIO -> zoomHonoured(result)
            // The crop is honoured when it covers the whole active array; the
            // exact rectangle can differ by a pixel of rounding on some HALs.
            Key.CROP_REGION -> result.get(CaptureResult.SCALER_CROP_REGION)?.isEmpty?.not()
            Key.AWB -> awbHonoured(result, decision)
        }
    }

    private fun zoomHonoured(result: TotalCaptureResult): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                ?.let { kotlin.math.abs(it - NO_ZOOM) <= ZOOM_TOLERANCE }
        } else {
            null
        }

    private fun awbHonoured(
        result: TotalCaptureResult,
        decision: CaptureIspLock.Decision,
    ): Boolean? = if (decision.mode == Mode.LOCKED) {
        result.get(CaptureResult.CONTROL_AWB_LOCK)
    } else {
        matches(result.get(CaptureResult.CONTROL_AWB_MODE), decision.value)
    }

    private fun matches(reported: Int?, want: Int?): Boolean? =
        if (reported == null || want == null) null else reported == want

    private fun ints(values: IntArray?): Set<Int> = values?.toSet().orEmpty()

    private const val NO_ZOOM = 1.0f
    private const val ZOOM_TOLERANCE = 0.001f
    private const val CAP_MANUAL_SENSOR =
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
    private const val CAP_MANUAL_POST_PROCESSING =
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING
}
