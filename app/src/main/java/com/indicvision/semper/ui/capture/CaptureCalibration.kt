package com.indicvision.semper.ui.capture

import android.content.Context
import androidx.core.content.edit

/**
 * Learns how long one locked still actually costs on *this* device, so the
 * setup screen offers frame counts the hardware can really deliver.
 *
 * This is the software half of the per-frame cost. The sensor's own read-out
 * floor is the other half and comes from Camera2 instead
 * ([CameraCapabilities.Info.minFrameMs]); [CaptureFrameCost] combines them.
 *
 * Nothing here is tuned to a particular phone. Camera2 reports a JPEG stall
 * duration, but the still path encodes grayscale PNG in software
 * ([GrayPngEncoder]) and never touches the hardware JPEG encoder, so that
 * figure describes a pipeline this app does not use — and it varies wildly
 * between vendors regardless. The only trustworthy number is one measured on
 * the device in front of us.
 *
 * The cost is dominated by the encode, which scales with pixel count, so it is
 * stored per megapixel: a measurement at one resolution then predicts another.
 * Until a real still has been taken the defaults deliberately assume a slow
 * device — over-estimating costs a few frames the user could have had, while
 * under-estimating promises a rate that stalls, which is the failure this
 * exists to prevent.
 */
object CaptureCalibration {

    /**
     * Assumed cost before any measurement. Set well above every device
     * measured so far rather than at a typical value: the first run of the
     * app on unknown hardware must not over-promise.
     */
    const val DEFAULT_MS_PER_MEGAPIXEL = 90f

    /**
     * No device completes the full sensor→buffer→encode→write round trip
     * faster than this, whatever the arithmetic says. Guards the prediction
     * when scaling a measurement down to a much smaller resolution, where the
     * per-frame overhead that does not shrink with pixel count would
     * otherwise be scaled away.
     */
    const val MIN_FRAME_MS = 80L

    /**
     * Headroom on predictions. Thermal throttling, a busy CPU and background
     * work all make a later frame slower than the calibration frame, and the
     * asymmetry matters: a slightly pessimistic offer loses a frame or two, an
     * optimistic one stalls the capture.
     */
    const val SAFETY_FACTOR = 1.15f

    private const val PREFS = "capture_calibration"
    private const val KEY_MS_PER_MP = "ms_per_megapixel"
    private const val PIXELS_PER_MEGAPIXEL = 1_000_000f

    /** Smooths one-off scheduling noise without ignoring a real change. */
    private const val SMOOTHING = 0.5f

    private const val MIN_MS_PER_MP = 1f
    private const val MAX_MS_PER_MP = 20_000f

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun msPerMegapixel(context: Context): Float =
        prefs(context).getFloat(KEY_MS_PER_MP, DEFAULT_MS_PER_MEGAPIXEL)

    /**
     * Folds one measured still into the stored figure. [measuredMs] must be
     * the whole capture — sensor, encode and write — for [width] × [height].
     */
    fun record(context: Context, width: Int, height: Int, measuredMs: Long) {
        if (width <= 0 || height <= 0 || measuredMs <= 0L) return
        val sample = (measuredMs / megapixels(width, height))
            .coerceIn(MIN_MS_PER_MP, MAX_MS_PER_MP)
        val blended = if (prefs(context).contains(KEY_MS_PER_MP)) {
            msPerMegapixel(context) * (1f - SMOOTHING) + sample * SMOOTHING
        } else {
            sample
        }
        prefs(context).edit { putFloat(KEY_MS_PER_MP, blended) }
    }

    /** Predicted cost of one still at [width] × [height], in milliseconds. */
    fun estimateFrameMs(context: Context, width: Int, height: Int): Long {
        val predicted = msPerMegapixel(context) * megapixels(width, height) * SAFETY_FACTOR
        return predicted.toLong().coerceAtLeast(MIN_FRAME_MS)
    }

    private fun megapixels(width: Int, height: Int): Float =
        width.coerceAtLeast(1).toFloat() * height.coerceAtLeast(1) / PIXELS_PER_MEGAPIXEL
}
