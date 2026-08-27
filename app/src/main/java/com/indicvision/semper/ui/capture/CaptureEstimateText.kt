package com.indicvision.semper.ui.capture

import android.content.Context
import com.indicvision.semper.R
import java.util.Locale

/**
 * Turns a [CapturePlanOptions.Option] into the sentence under the chips.
 *
 * It states the rate the user picked, what that comes to in frames, and how
 * far apart they land — all three, because a rate alone hides the run's cost
 * and a frame count alone hides its resolution in time.
 */
internal object CaptureEstimateText {

    private const val MILLIS_PER_SECOND = 1_000

    fun line(context: Context, option: CapturePlanOptions.Option, modeLabel: String): String =
        context.getString(
            R.string.capture_estimate_fmt,
            fps(option.fps),
            option.frames,
            spacing(context, option.intervalMs),
            modeLabel,
        )

    /**
     * Why the list stops where it does. Points at whichever limit is actually
     * binding, so the fix offered is the one that would work.
     */
    fun ceilingNote(context: Context, cappedBySetting: Boolean, maxFramesSetting: Int): String =
        if (cappedBySetting) {
            context.getString(R.string.capture_fps_ceiling_setting_fmt, maxFramesSetting)
        } else {
            context.getString(R.string.capture_fps_ceiling_camera)
        }

    /** "2", "0.5" — never "2.0", which reads like false precision. */
    fun fps(value: Float): String =
        if (value >= 1f && value == value.toInt().toFloat()) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }

    private fun spacing(context: Context, intervalMs: Long): String =
        if (intervalMs >= MILLIS_PER_SECOND) {
            context.getString(
                R.string.capture_interval_sec_fmt,
                intervalMs / MILLIS_PER_SECOND.toFloat(),
            )
        } else {
            context.getString(R.string.capture_interval_ms_fmt, intervalMs)
        }
}
