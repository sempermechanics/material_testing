package com.indicvision.semper.ui.capture

/**
 * Chooses stills vs video for a planned recording. Prefer stills whenever the
 * frame interval can absorb a locked JPEG stall; otherwise fall back to one
 * video and extract frames later. Never pick a fps the chosen mode cannot deliver.
 */
object CapturePlanner {

    enum class Mode {
        STILLS,
        VIDEO,
    }

    data class Decision(
        val mode: Mode,
        /** Frame rate actually used (may be clamped below the request). */
        val fps: Int,
        val reason: String,
    )

    /**
     * @param requestedFps user-chosen fps in 1..maxDeviceFps
     * @param maxDeviceFps hardware / catalogue ceiling
     * @param jpegStallMs typical time to take one locked JPEG still
     * @param canTakeStills true when JPEG output is available
     * @param canRecordVideo true when a video recording path exists
     * @param stallMarginMs extra headroom on top of [jpegStallMs]
     */
    fun chooseMode(
        requestedFps: Int,
        maxDeviceFps: Int,
        jpegStallMs: Long,
        canTakeStills: Boolean,
        canRecordVideo: Boolean,
        stallMarginMs: Long = DEFAULT_STALL_MARGIN_MS,
    ): Decision {
        val maxFps = maxDeviceFps.coerceAtLeast(1)
        val fps = requestedFps.coerceIn(1, maxFps)
        val intervalMs = 1000.0 / fps
        val stillBudgetMs = (jpegStallMs + stallMarginMs).coerceAtLeast(1L)
        val stillsFeasible = canTakeStills && intervalMs >= stillBudgetMs

        return when {
            !canTakeStills && !canRecordVideo ->
                Decision(Mode.STILLS, fps, "no_capture_path")
            stillsFeasible ->
                Decision(Mode.STILLS, fps, "stills_interval_ok")
            canRecordVideo -> {
                // Video can usually deliver the device max; clamp to that.
                Decision(Mode.VIDEO, fps, "interval_too_short_for_stills")
            }
            canTakeStills -> {
                // No video: keep stills even if late — finish rather than fail.
                val maxStillFps = (1000.0 / stillBudgetMs).toInt().coerceIn(1, maxFps)
                val clamped = fps.coerceAtMost(maxStillFps)
                Decision(Mode.STILLS, clamped, "no_video_clamp_stills")
            }
            else -> Decision(Mode.STILLS, fps, "fallback_stills")
        }
    }

    const val DEFAULT_STALL_MARGIN_MS = 80L
    /** Conservative stall when Camera2 does not report JPEG min duration. */
    const val DEFAULT_JPEG_STALL_MS = 120L
}
