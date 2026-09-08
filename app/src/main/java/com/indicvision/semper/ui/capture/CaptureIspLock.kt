package com.indicvision.semper.ui.capture

/**
 * Decides which parts of the phone's image pipeline to freeze for a DIC run.
 *
 * DIC assumes brightness constancy: the only thing that may change between two
 * frames is the specimen. A phone's ISP assumes the opposite — it re-derives
 * stabilisation, denoise, sharpening, tone mapping and shading per frame, each
 * of which rewrites intensities or moves pixels for reasons that have nothing to
 * do with strain. A scene-adaptive denoiser also correlates neighbouring pixels,
 * which is precisely what sub-pixel interpolation assumes is independent, so it
 * biases the result rather than merely adding noise to it.
 *
 * Two rules shape everything here, and both come from robustness rather than
 * precision:
 *
 * 1. **Never request an unlisted value.** A HAL handed a mode it did not
 *    enumerate can reject the whole request and take the capture session with
 *    it. A precise setting is worth nothing if the run does not happen, so every
 *    decision below is gated on the device's own availability list.
 * 2. **No Camera2 types in the decision.** The caller resolves [Key] to real
 *    `CaptureRequest` keys; this file takes a plain [DeviceProfile] and returns
 *    plain [Decision]s, so the whole policy is testable against synthetic
 *    LEGACY/LIMITED/FULL profiles instead of whatever phone is on the desk.
 *
 * What the hardware *actually did* is a separate question from what was asked —
 * a HAL may accept a key and ignore it — so the caller reads the applied values
 * back out of the capture result and reconciles them with [Decision.applied].
 */
object CaptureIspLock {

    /**
     * Hardware level, weakest first. [camera2Value] is the raw
     * `INFO_SUPPORTED_HARDWARE_LEVEL_*` int, which is deliberately *not* in this
     * order — LIMITED is 0 and LEGACY is 2 — so the mapping is explicit rather
     * than an ordinal coincidence waiting to break.
     */
    enum class HardwareLevel(val camera2Value: Int) {
        LEGACY(LEVEL_LEGACY),
        LIMITED(LEVEL_LIMITED),
        EXTERNAL(LEVEL_EXTERNAL),
        FULL(LEVEL_FULL),
        LEVEL_3(LEVEL_THREE),
        ;

        companion object {
            /**
             * Map a raw Camera2 level. An unrecognised value means a level newer
             * than this build knows about, which by Camera2's own compatibility
             * rules is at least LIMITED — so it is treated as LIMITED rather
             * than LEGACY, which would strip a capable phone of settings it
             * supports.
             */
            fun fromCamera2(value: Int?): HardwareLevel =
                entries.firstOrNull { it.camera2Value == value } ?: LIMITED
        }
    }

    /**
     * The pipeline settings this object decides, independent of Camera2.
     *
     * [wanted] is the set of modes that count as DIC getting what it asked for
     * on that key, and it is deliberately per-key rather than one global rule:
     * OFF is the goal for denoise and OIS, but the goal for shading correction
     * is FAST — a *re-estimated* vignetting field is the problem, not a stable
     * one — and for the shading map it is ON. Judging every key against the same
     * modes would report a correctly configured FULL device as compromised, and
     * the user would see a warning about a setting that worked.
     */
    enum class Key(internal val wanted: Set<Mode>) {
        OPTICAL_STABILIZATION(setOf(Mode.OFF)),
        VIDEO_STABILIZATION(setOf(Mode.OFF)),

        /**
         * Zero-shutter-lag: the still is served from, or merged over, a ring
         * buffer of frames the pipeline was already holding. See [zeroShutterLag].
         *
         * [Mode.UNAVAILABLE] counts as ideal here, uniquely. Elsewhere it means
         * the device would not give DIC what it asked for; here it means the
         * device does not list `CONTROL_ENABLE_ZSL` at all, and a camera with no
         * zero-shutter-lag to switch off is already in the state wanted. Warning
         * about it would fire on the majority of phones, for a setting that was
         * never on.
         */
        ZERO_SHUTTER_LAG(setOf(Mode.OFF, Mode.UNAVAILABLE)),
        NOISE_REDUCTION(setOf(Mode.OFF)),
        EDGE(setOf(Mode.OFF)),
        TONEMAP(setOf(Mode.LINEAR)),
        SHADING(setOf(Mode.FAST, Mode.ON)),
        LENS_SHADING_MAP(setOf(Mode.ON)),
        ABERRATION_CORRECTION(setOf(Mode.OFF)),
        SCENE_MODE(setOf(Mode.OFF)),
        EFFECT_MODE(setOf(Mode.OFF)),
        ZOOM_RATIO(setOf(Mode.FULL_FRAME)),
        CROP_REGION(setOf(Mode.FULL_FRAME)),

        /** Fixed gains are better than a lock, but a lock does hold white
         *  balance still, which is the whole requirement. */
        AWB(setOf(Mode.OFF, Mode.LOCKED)),
    }

    /**
     * What a key was set to. [OFF] and [LINEAR] are the values DIC wants;
     * [FAST] and [LOCKED] are the honest second bests; [UNAVAILABLE] means the
     * device offered nothing usable and the key was not set at all.
     */
    enum class Mode { OFF, FAST, LINEAR, LOCKED, ON, FULL_FRAME, UNAVAILABLE }

    /**
     * What the device says it can do. Every field is read from
     * `CameraCharacteristics` at runtime — there are no model or vendor checks
     * anywhere in this policy, by design.
     */
    data class DeviceProfile(
        val hardwareLevel: HardwareLevel,
        val manualSensor: Boolean,
        val manualPostProcessing: Boolean,
        /** `LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION`, raw Camera2 ints. */
        val opticalStabilizationModes: Set<Int> = emptySet(),
        val videoStabilizationModes: Set<Int> = emptySet(),
        val noiseReductionModes: Set<Int> = emptySet(),
        val edgeModes: Set<Int> = emptySet(),
        val tonemapModes: Set<Int> = emptySet(),
        val shadingModes: Set<Int> = emptySet(),
        val lensShadingMapModes: Set<Int> = emptySet(),
        val aberrationModes: Set<Int> = emptySet(),
        val sceneModes: Set<Int> = emptySet(),
        val effectModes: Set<Int> = emptySet(),
        /** The device lists `CONTROL_ENABLE_ZSL` among its request keys. */
        val supportsZsl: Boolean = false,
        val awbLockAvailable: Boolean = false,
        val awbModes: Set<Int> = emptySet(),
        /** API 30+ only; `CONTROL_ZOOM_RATIO` is absent below it. */
        val supportsZoomRatio: Boolean = false,
        val hasActiveArray: Boolean = false,
    ) {
        val isLegacy: Boolean get() = hardwareLevel == HardwareLevel.LEGACY
    }

    /**
     * One resolved setting. [value] is the Camera2 int the caller should apply
     * for the enum-valued keys, and is null for the keys whose value is not an
     * enum (zoom ratio, crop region, AWB lock).
     */
    data class Decision(
        val key: Key,
        val mode: Mode,
        val value: Int? = null,
    ) {
        /** True when the key should be written to the request at all. */
        val applied: Boolean get() = mode != Mode.UNAVAILABLE

        /** True when DIC got what it wanted on this key; see [Key.wanted]. */
        val ideal: Boolean get() = mode in key.wanted
    }

    /** The full plan: what to apply, and what this device would not give. */
    data class Plan(val decisions: List<Decision>) {
        fun applied(): List<Decision> = decisions.filter { it.applied }

        /**
         * Keys that landed on a fallback or could not be set at all. These are
         * what the user is warned about — collapsed into one message, since a
         * LEGACY device refuses several at once and six warnings is noise.
         */
        fun compromised(): List<Decision> = decisions.filterNot { it.ideal }

        fun modeOf(key: Key): Mode = decisions.firstOrNull { it.key == key }?.mode ?: Mode.UNAVAILABLE
    }

    /**
     * Resolve [profile] into the settings to apply.
     *
     * The order below is the order the pipeline applies them in, which is also
     * roughly their importance to DIC: stabilisation moves pixels, denoise and
     * edge rewrite gradients, tonemap and shading rewrite intensity.
     */
    fun plan(profile: DeviceProfile): Plan = Plan(
        listOf(
            // OIS floats the lens element between frames and carries the
            // distortion field with it, which is exactly the signature seen on a
            // tripod: a shift of a few pixels that differs across the frame. A
            // uniform shift would largely cancel in strain; this one does not.
            offOrUnavailable(Key.OPTICAL_STABILIZATION, profile.opticalStabilizationModes, OIS_OFF),
            // EIS warps frames non-rigidly to hold the scene still, which is
            // fabricated strain rather than merely noise.
            offOrUnavailable(Key.VIDEO_STABILIZATION, profile.videoStabilizationModes, VIDEO_STAB_OFF),
            zeroShutterLag(profile),
            // Scene-adaptive denoise correlates neighbouring pixels — the one
            // thing sub-pixel interpolation assumes is independent.
            offElseFast(Key.NOISE_REDUCTION, profile.noiseReductionModes, NR_OFF, NR_FAST),
            // Sharpening rewrites the intensity gradients SSSIG is computed from.
            offElseFast(Key.EDGE, profile.edgeModes, EDGE_OFF, EDGE_FAST),
            tonemap(profile),
            shading(profile),
            lensShadingMap(profile),
            offElseFast(
                Key.ABERRATION_CORRECTION,
                profile.aberrationModes,
                ABERRATION_OFF,
                ABERRATION_FAST,
            ),
            // Scene modes re-tune the whole pipeline behind every other setting.
            offOrUnavailable(Key.SCENE_MODE, profile.sceneModes, SCENE_MODE_DISABLED),
            offOrUnavailable(Key.EFFECT_MODE, profile.effectModes, EFFECT_OFF),
            // Any zoom but 1.0 means the ISP is resampling, which invents
            // intensities between real samples — the interpolation DIC is trying
            // to do itself, done worse and without telling us.
            fullFrameOr(Key.ZOOM_RATIO, profile.supportsZoomRatio && !profile.isLegacy),
            // The one place hardware level gates a decision rather than an
            // availability list: LEGACY devices emulate the scaler crop through
            // the old camera API and honour it inconsistently, so writing one
            // risks a differently framed stream for no gain.
            fullFrameOr(Key.CROP_REGION, profile.hasActiveArray && !profile.isLegacy),
            awb(profile),
        ),
    )

    // ------------------------------------------------------------------
    // The decisions with more than one condition; the rest are inline above.
    // ------------------------------------------------------------------

    /**
     * Zero-shutter-lag hands back a frame the pipeline was already holding, and
     * on several vendors merges a burst of them into one still.
     *
     * That is the worst possible input for DIC and the hardest to notice, because
     * it makes frames look *better*: a merged still has visibly less noise than
     * any single exposure, so the measured image noise collapses while the
     * displacement it produces becomes correlated with whichever base frame the
     * vendor's aligner happened to pick that time. Two stills taken seconds apart
     * are then averages of two different, independently aligned sets of frames —
     * exactly the non-uniform frame-to-frame shift a tripod cannot explain.
     *
     * `TEMPLATE_STILL_CAPTURE` turns it on by default wherever it is supported,
     * which is why every other lock in this file can read back as honoured while
     * this one silently undoes them.
     *
     * There is no result key to read it back from, so [CaptureIspApply] reports
     * null here and the user is never told this one was honoured — which is the
     * honest answer rather than a claim the hardware never made.
     */
    private fun zeroShutterLag(p: DeviceProfile): Decision =
        if (p.supportsZsl) {
            Decision(Key.ZERO_SHUTTER_LAG, Mode.OFF)
        } else {
            Decision(Key.ZERO_SHUTTER_LAG, Mode.UNAVAILABLE)
        }

    /**
     * Local tone mapping is scene-adaptive, so brightness constancy between two
     * frames is simply false while it is running. A gamma of 1.0 is the linear
     * response DIC wants, but writing a tonemap curve at all needs
     * `MANUAL_POST_PROCESSING`; without it the device keeps its own curve.
     */
    private fun tonemap(p: DeviceProfile): Decision = when {
        p.manualPostProcessing && TONEMAP_GAMMA_VALUE in p.tonemapModes ->
            Decision(Key.TONEMAP, Mode.LINEAR, TONEMAP_GAMMA_VALUE)
        p.manualPostProcessing && TONEMAP_CONTRAST_CURVE in p.tonemapModes ->
            Decision(Key.TONEMAP, Mode.LINEAR, TONEMAP_CONTRAST_CURVE)
        TONEMAP_FAST in p.tonemapModes -> Decision(Key.TONEMAP, Mode.FAST, TONEMAP_FAST)
        else -> Decision(Key.TONEMAP, Mode.UNAVAILABLE)
    }

    /**
     * Shading correction is kept ON rather than off: vignetting itself is
     * static and cancels between frames, but a *re-estimated* correction does
     * not. FAST is the stable choice, and the shading map is requested so the
     * applied field is recorded with the session.
     */
    private fun shading(p: DeviceProfile): Decision = when {
        SHADING_FAST in p.shadingModes -> Decision(Key.SHADING, Mode.FAST, SHADING_FAST)
        SHADING_HIGH_QUALITY in p.shadingModes ->
            Decision(Key.SHADING, Mode.ON, SHADING_HIGH_QUALITY)
        else -> Decision(Key.SHADING, Mode.UNAVAILABLE)
    }

    private fun lensShadingMap(p: DeviceProfile): Decision =
        if (SHADING_MAP_ON in p.lensShadingMapModes) {
            Decision(Key.LENS_SHADING_MAP, Mode.ON, SHADING_MAP_ON)
        } else {
            Decision(Key.LENS_SHADING_MAP, Mode.UNAVAILABLE)
        }

    /**
     * Per-channel white-balance gains drift frame to frame, and the engine
     * correlates on luma derived from those channels. Fixed gains are better
     * than a lock, but need `MANUAL_POST_PROCESSING`; the lock is the fallback.
     */
    private fun awb(p: DeviceProfile): Decision = when {
        p.manualPostProcessing && AWB_MODE_OFF in p.awbModes ->
            Decision(Key.AWB, Mode.OFF, AWB_MODE_OFF)
        p.awbLockAvailable -> Decision(Key.AWB, Mode.LOCKED)
        else -> Decision(Key.AWB, Mode.UNAVAILABLE)
    }

    // ------------------------------------------------------------------
    // Shared shapes
    // ------------------------------------------------------------------

    private fun fullFrameOr(key: Key, available: Boolean): Decision =
        if (available) Decision(key, Mode.FULL_FRAME) else Decision(key, Mode.UNAVAILABLE)

    private fun offOrUnavailable(key: Key, available: Set<Int>, off: Int): Decision =
        if (off in available) Decision(key, Mode.OFF, off) else Decision(key, Mode.UNAVAILABLE)

    private fun offElseFast(key: Key, available: Set<Int>, off: Int, fast: Int): Decision = when {
        off in available -> Decision(key, Mode.OFF, off)
        fast in available -> Decision(key, Mode.FAST, fast)
        else -> Decision(key, Mode.UNAVAILABLE)
    }

    // Camera2 constants, duplicated rather than imported so this file stays
    // free of android.hardware — the caller asserts they match in a test.
    const val OIS_OFF = 0
    const val VIDEO_STAB_OFF = 0
    const val NR_OFF = 0
    const val NR_FAST = 1
    const val EDGE_OFF = 0
    const val EDGE_FAST = 1
    const val TONEMAP_CONTRAST_CURVE = 0
    const val TONEMAP_FAST = 1
    const val TONEMAP_GAMMA_VALUE = 3
    const val SHADING_FAST = 1
    const val SHADING_HIGH_QUALITY = 2
    const val SHADING_MAP_ON = 1
    const val ABERRATION_OFF = 0
    const val ABERRATION_FAST = 1
    const val SCENE_MODE_DISABLED = 0
    const val EFFECT_OFF = 0
    const val AWB_MODE_OFF = 0

    /** Linear response: the gamma DIC wants when the device lets us set one. */
    const val LINEAR_GAMMA = 1.0f

    // INFO_SUPPORTED_HARDWARE_LEVEL_* — see HardwareLevel.camera2Value.
    private const val LEVEL_LIMITED = 0
    private const val LEVEL_FULL = 1
    private const val LEVEL_LEGACY = 2
    private const val LEVEL_THREE = 3
    private const val LEVEL_EXTERNAL = 4
}
