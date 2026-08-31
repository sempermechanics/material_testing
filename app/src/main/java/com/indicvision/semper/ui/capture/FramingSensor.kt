package com.indicvision.semper.ui.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import timber.log.Timber

/**
 * Feeds the device's gravity readings to a [FramingWatch] and reports the one
 * moment it says the camera has been re-aimed.
 *
 * All the judgement lives in [FramingWatch]; this is only the plumbing, kept
 * apart from it so the thresholds stay unit-testable.
 *
 * `TYPE_GRAVITY` first because it is already the settled direction with linear
 * acceleration removed, then the raw accelerometer, which every Android phone
 * has — [FramingWatch]'s own rest gate and smoothing are what make the raw
 * signal usable. A device with neither reports [available] false and the caller
 * keeps today's behaviour, which is the rule every lever in this work follows:
 * the last link in the chain is always what the app did before.
 */
internal class FramingSensor(
    context: Context,
    private val onMoved: () -> Unit,
) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val watch = FramingWatch()
    private var watching = false

    val available: Boolean get() = sensor != null

    /**
     * Hold the framing the phone is in right now.
     *
     * Called when the floor has been measured and accepted, because that is the
     * moment the focus and the floor start describing one particular framing.
     */
    fun holdCurrentFraming() {
        val target = sensor ?: return
        // Already holding one: keep it. Re-taking the reference here is how a
        // real move gets laundered — the screen is re-shown after a browser hop
        // that the phone may have been picked up for, and re-holding would
        // adopt the new attitude as though it had always been the framing.
        if (watching) return
        watch.reset()
        // SENSOR_DELAY_UI, not FASTEST: the question is which way the rig is
        // pointing, which does not change between frames, and a faster rate
        // would only spend battery during a step where the user is looking at
        // the screen deciding.
        watching = manager?.registerListener(this, target, SensorManager.SENSOR_DELAY_UI) == true
    }

    /** Stop watching; safe to call when it never started. */
    fun stop() {
        if (!watching) return
        watching = false
        manager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        if (values.size < AXES) return
        if (!watch.accept(values[0].toDouble(), values[1].toDouble(), values[2].toDouble())) return
        Timber.i("framing: camera re-aimed by %.1f degrees; lock and floor are stale", watch.movedDegrees)
        stop()
        onMoved()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val AXES = 3
    }
}
