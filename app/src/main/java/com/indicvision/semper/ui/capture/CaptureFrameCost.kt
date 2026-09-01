package com.indicvision.semper.ui.capture

import android.content.Context
import kotlin.math.max

/**
 * What one still really costs at a given resolution, from the two independent
 * limits that apply to it.
 *
 * A frame cannot arrive faster than the sensor will read that size out, and it
 * cannot be written faster than the CPU encodes the PNG. Neither figure alone
 * is the answer: the sensor floor comes from Camera2's stream configuration
 * map and knows nothing about this app's software encode, while the measured
 * encode cost knows nothing about read-out. Planning from whichever is larger
 * is the only bound that holds on both counts.
 *
 * Taking the larger of the two rather than their sum is deliberately
 * optimistic: today's loop is serial, since LockedCameraSession finishes the
 * encode inside the ImageReader callback before the next capture is issued.
 * The gap between max and sum is part of what
 * [CapturePlanOptions.ASSURANCE_MARGIN] is paid to cover. Planning from the
 * sum would price in a no-overlap worst case on every device and cut the
 * offered rates well below what phones actually sustain.
 */
internal object CaptureFrameCost {

    /** Per-frame budget in ms for [res] on the camera described by [caps]. */
    fun perFrameMs(
        context: Context,
        caps: CameraCapabilities.Info,
        res: CameraCapabilities.Resolution,
    ): Long {
        val encodeMs = CaptureCalibration.estimateFrameMs(context, res.width, res.height)
        val sensorMs = caps.sensorFloorMs(res)
        return max(encodeMs, sensorMs).coerceAtLeast(1L)
    }
}
