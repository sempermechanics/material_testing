package com.indicvision.semper.ui.capture

import androidx.annotation.StringRes
import com.indicvision.semper.R
import com.indicvision.semper.ui.capture.CaptureIspLock.Key

/**
 * Decides what to tell the user when the phone would not freeze part of its
 * imaging pipeline.
 *
 * A setting that could not be switched off is not a silent internal detail: it
 * changes how much the results can be trusted. But a LEGACY device refuses half
 * a dozen keys at once, and six warnings is noise — so this collapses them to
 * one, named by the refusal with the largest consequence for DIC.
 *
 * Two rules the wording follows:
 *
 * 1. **Effect first, mechanism in the FAQ.** The audience is running a strain
 *    measurement and can read "stabiliser" and "exposure"; what they cannot do
 *    is read a paragraph mid-setup. Every string below is under twenty words.
 * 2. **Warn on what the hardware did, not what was asked.** A key the HAL never
 *    reports is not a refusal — nothing can be concluded about it — and a
 *    spurious warning is as much a defect as a missing one.
 */
object CaptureIspWarning {

    /**
     * Settings where this device could not give DIC what it wanted.
     *
     * Two sources, because they are different failures: the plan already knows
     * which keys the device never offered or offered only a fallback for, and
     * the read-back knows which ones it accepted and then ignored.
     */
    fun shortfall(
        plan: CaptureIspLock.Plan?,
        report: List<CaptureIspApply.Honoured>,
    ): List<Key> {
        if (plan == null) return emptyList()
        val ignored = report.filter { it.honoured == false }.map { it.key }.toSet()
        val compromised = plan.compromised().map { it.key }.toSet()
        return PRIORITY.filter { it in ignored || it in compromised }
    }

    /**
     * The refusal worth naming, or null when there is nothing to say.
     *
     * [PRIORITY] is the order the pipeline applies these in, which is also
     * roughly their cost to DIC: stabilisation moves pixels, denoise and edge
     * rewrite gradients, tone and shading rewrite intensity.
     */
    fun headline(shortfall: List<Key>): Key? = PRIORITY.firstOrNull { it in shortfall }

    /** One sentence saying what this refusal does to the measurement. */
    @StringRes
    @Suppress("CyclomaticComplexMethod")
    fun effectOf(key: Key): Int = when (key) {
        Key.OPTICAL_STABILIZATION -> R.string.capture_isp_warn_ois
        Key.VIDEO_STABILIZATION -> R.string.capture_isp_warn_eis
        Key.ZERO_SHUTTER_LAG -> R.string.capture_isp_warn_zsl
        Key.NOISE_REDUCTION -> R.string.capture_isp_warn_denoise
        Key.EDGE -> R.string.capture_isp_warn_edge
        Key.TONEMAP -> R.string.capture_isp_warn_tonemap
        Key.SHADING, Key.LENS_SHADING_MAP -> R.string.capture_isp_warn_shading
        Key.ABERRATION_CORRECTION -> R.string.capture_isp_warn_aberration
        Key.SCENE_MODE, Key.EFFECT_MODE -> R.string.capture_isp_warn_scene
        Key.ZOOM_RATIO, Key.CROP_REGION -> R.string.capture_isp_warn_zoom
        Key.AWB -> R.string.capture_isp_warn_awb
    }

    /**
     * Importance to DIC, worst first. Mirrors the order [CaptureIspLock.plan]
     * resolves them in, so one list cannot drift from the other unnoticed.
     */
    private val PRIORITY = listOf(
        Key.OPTICAL_STABILIZATION,
        Key.VIDEO_STABILIZATION,
        Key.ZERO_SHUTTER_LAG,
        Key.NOISE_REDUCTION,
        Key.EDGE,
        Key.TONEMAP,
        Key.ZOOM_RATIO,
        Key.CROP_REGION,
        Key.SHADING,
        Key.LENS_SHADING_MAP,
        Key.AWB,
        Key.ABERRATION_CORRECTION,
        Key.SCENE_MODE,
        Key.EFFECT_MODE,
    )
}
