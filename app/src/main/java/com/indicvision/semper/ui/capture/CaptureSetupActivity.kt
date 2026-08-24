package com.indicvision.semper.ui.capture

import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.indicvision.semper.R
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.ui.common.Insets

/**
 * Collects fps / duration / resolution, runs the budget gate, then hands off
 * to [CaptureSessionActivity] for the test shot and locked recording.
 */
class CaptureSetupActivity : AppCompatActivity() {

    private lateinit var caps: CameraCapabilities.Info
    private lateinit var sliderFps: Slider
    private lateinit var sliderDuration: Slider
    private lateinit var tvFps: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvEstimate: TextView
    private lateinit var tvMode: TextView
    private lateinit var spinnerRes: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_setup)
        Insets.padTop(findViewById(R.id.toolbarCaptureSetup))

        caps = CameraCapabilities.query(this)

        findViewById<MaterialToolbar>(R.id.toolbarCaptureSetup).apply {
            setNavigationOnClickListener { finish() }
        }

        sliderFps = findViewById(R.id.sliderCaptureFps)
        sliderDuration = findViewById(R.id.sliderCaptureDuration)
        tvFps = findViewById(R.id.tvCaptureFps)
        tvDuration = findViewById(R.id.tvCaptureDuration)
        tvEstimate = findViewById(R.id.tvCaptureEstimate)
        tvMode = findViewById(R.id.tvCaptureMode)
        spinnerRes = findViewById(R.id.spinnerCaptureResolution)

        sliderFps.valueFrom = 1f
        sliderFps.valueTo = caps.maxFps.toFloat().coerceAtLeast(1f)
        sliderFps.value = minOf(5, caps.maxFps).toFloat()

        val labels = caps.jpegSizes.map { it.label }
        spinnerRes.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels,
        )

        val refresh = {
            refreshEstimate()
        }
        sliderFps.addOnChangeListener { _, _, _ -> refresh() }
        sliderDuration.addOnChangeListener { _, _, _ -> refresh() }
        spinnerRes.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) = refresh()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })
        refresh()

        findViewById<MaterialButton>(R.id.btnCaptureContinue).setOnClickListener {
            onContinue()
        }
    }

    private fun selectedResolution(): CameraCapabilities.Resolution =
        caps.jpegSizes.getOrElse(spinnerRes.selectedItemPosition) { caps.jpegSizes.first() }

    private fun plannedFrames(): Int {
        val fps = sliderFps.value.toInt().coerceAtLeast(1)
        val duration = sliderDuration.value.toInt().coerceAtLeast(1)
        val maxFrames = DicSettings.maxFrames(this, AppRemoteConfig.maxFrames(this))
        return (fps * duration).coerceIn(1, maxFrames)
    }

    private fun decision(): CapturePlanner.Decision {
        val fps = sliderFps.value.toInt().coerceAtLeast(1)
        return CapturePlanner.chooseMode(
            requestedFps = fps,
            maxDeviceFps = caps.maxFps,
            jpegStallMs = caps.jpegStallMs,
            canTakeStills = true,
            // Locked Camera2 session can record; system video intent is a fallback.
            canRecordVideo = true,
        )
    }

    private fun refreshEstimate() {
        val fps = sliderFps.value.toInt()
        val duration = sliderDuration.value.toInt()
        tvFps.text = getString(R.string.capture_fps_fmt, fps)
        tvDuration.text = getString(R.string.capture_duration_fmt, duration)
        val frames = plannedFrames()
        val d = decision()
        val modeLabel = when (d.mode) {
            CapturePlanner.Mode.STILLS -> getString(R.string.capture_mode_stills)
            CapturePlanner.Mode.VIDEO -> getString(R.string.capture_mode_video)
        }
        tvEstimate.text = getString(R.string.capture_estimate_fmt, frames, modeLabel)
        tvMode.text = modeLabel
        // Reflect any clamp from the planner.
        if (d.fps != fps && sliderFps.valueTo >= d.fps) {
            // Do not fight the user mid-drag; only show mode text.
        }
    }

    private fun onContinue() {
        if (!SystemCamera.canCaptureStill(this)) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_no_camera_app)
                .setPositiveButton(R.string.cancel, null)
                .show()
            return
        }
        val res = selectedResolution()
        val frames = plannedFrames()
        val duration = sliderDuration.value.toInt().coerceAtLeast(1)
        val d = decision()
        val estimate = when (d.mode) {
            CapturePlanner.Mode.STILLS -> CaptureBudget.estimateStills(res.width, res.height, frames)
            CapturePlanner.Mode.VIDEO -> CaptureBudget.estimateVideo(
                res.width,
                res.height,
                duration,
                frames,
            )
        }
        val check = CaptureBudget.check(estimate, availRam(), availStorage())
        if (!check.ok) {
            val msg = when {
                !check.ramOk && !check.storageOk -> R.string.capture_budget_fail_both
                !check.ramOk -> R.string.capture_budget_fail_ram
                else -> R.string.capture_budget_fail_storage
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.capture_budget_fail_title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        if (frames < 1) return

        startActivity(
            Intent(this, CaptureSessionActivity::class.java).apply {
                putExtra(EXTRA_FPS, d.fps)
                putExtra(EXTRA_DURATION_SEC, duration)
                putExtra(EXTRA_FRAME_COUNT, frames)
                putExtra(EXTRA_WIDTH, res.width)
                putExtra(EXTRA_HEIGHT, res.height)
                putExtra(EXTRA_MODE, d.mode.name)
                putExtra(EXTRA_CAMERA_ID, caps.cameraId)
                putExtra(EXTRA_JPEG_STALL_MS, caps.jpegStallMs)
            },
        )
        finish()
    }

    private fun availRam(): Long {
        val am = getSystemService(ActivityManager::class.java) ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    private fun availStorage(): Long = cacheDir.usableSpace

    companion object {
        const val EXTRA_FPS = "capture_fps"
        const val EXTRA_DURATION_SEC = "capture_duration_sec"
        const val EXTRA_FRAME_COUNT = "capture_frame_count"
        const val EXTRA_WIDTH = "capture_width"
        const val EXTRA_HEIGHT = "capture_height"
        const val EXTRA_MODE = "capture_mode"
        const val EXTRA_CAMERA_ID = "capture_camera_id"
        const val EXTRA_JPEG_STALL_MS = "capture_jpeg_stall_ms"
    }
}
