@file:Suppress("TooManyFunctions", "LongMethod")

package com.indicvision.semper.ui.capture

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.TextureView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.analysis.SubsetRecommender
import com.indicvision.semper.ui.analysis.VideoFrameExtractor
import com.indicvision.semper.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Test shot (Camera app) → SSSIG → focus lock → timed stills or video → wizard.
 */
class CaptureSessionActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var preview: TextureView

    private var fps = 5
    private var durationSec = 10
    private var frameCount = 10
    private var planWidth = 1920
    private var planHeight = 1080
    private var mode = CapturePlanner.Mode.STILLS
    private var cameraId = "0"

    private var focusLock: CaptureFocusLock? = null
    private var lockedSession: LockedCameraSession? = null
    private var testShotFile: File? = null
    private val deformedPaths = mutableListOf<String>()

    private val takeTestShot = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            offerRetryTestShot(getString(R.string.capture_test_shot_empty))
            return@registerForActivityResult
        }
        val file = testShotFile
        if (file == null || !file.exists() || file.length() == 0L) {
            offerRetryTestShot(getString(R.string.capture_test_shot_empty))
            return@registerForActivityResult
        }
        onTestShotReady(file)
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) openLockedSession() else {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_permission_needed)
                .setPositiveButton(R.string.capture_retry) { _, _ -> ensureCameraThenLock() }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_session)
        Insets.padTop(findViewById(R.id.toolbarCaptureSession))

        fps = intent.getIntExtra(CaptureSetupActivity.EXTRA_FPS, 5).coerceAtLeast(1)
        durationSec = intent.getIntExtra(CaptureSetupActivity.EXTRA_DURATION_SEC, 10).coerceAtLeast(1)
        frameCount = intent.getIntExtra(CaptureSetupActivity.EXTRA_FRAME_COUNT, fps * durationSec)
            .coerceAtLeast(1)
        planWidth = intent.getIntExtra(CaptureSetupActivity.EXTRA_WIDTH, 1920)
        planHeight = intent.getIntExtra(CaptureSetupActivity.EXTRA_HEIGHT, 1080)
        mode = runCatching {
            CapturePlanner.Mode.valueOf(
                intent.getStringExtra(CaptureSetupActivity.EXTRA_MODE)
                    ?: CapturePlanner.Mode.STILLS.name,
            )
        }.getOrDefault(CapturePlanner.Mode.STILLS)
        cameraId = intent.getStringExtra(CaptureSetupActivity.EXTRA_CAMERA_ID) ?: "0"

        tvStatus = findViewById(R.id.tvCaptureStatus)
        tvProgress = findViewById(R.id.tvCaptureProgress)
        btnStart = findViewById(R.id.btnCaptureStart)
        preview = findViewById(R.id.capturePreview)

        findViewById<MaterialToolbar>(R.id.toolbarCaptureSession).apply {
            setNavigationOnClickListener { finish() }
            setNavigationIconTint(getColor(R.color.text_on_primary))
        }

        btnStart.setOnClickListener { startRecording() }
        launchTestShot()
    }

    override fun onDestroy() {
        lockedSession?.close()
        lockedSession = null
        super.onDestroy()
    }

    private fun launchTestShot() {
        tvStatus.setText(R.string.capture_status_test_shot)
        btnStart.isVisible = false
        val dir = SystemCamera.captureDir(this)
        val file = File(dir, "test.jpg")
        if (file.exists()) file.delete()
        testShotFile = file
        val uri = SystemCamera.fileProviderUri(this, file)
        val intent = SystemCamera.stillCaptureIntent(this, uri)
        if (intent == null) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_no_camera_app)
                .setPositiveButton(R.string.cancel) { _, _ -> finish() }
                .show()
            return
        }
        takeTestShot.launch(intent)
    }

    private fun offerRetryTestShot(message: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.capture_retry) { _, _ -> launchTestShot() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun onTestShotReady(file: File) {
        tvStatus.setText(R.string.capture_status_checking)
        lifecycleScope.launch {
            val check = withContext(Dispatchers.IO) { evaluateSpeckle(file) }
            if (check == null || check.lowTexture) {
                MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                    .setTitle(R.string.capture_speckle_fail_title)
                    .setMessage(R.string.capture_speckle_fail_body)
                    .setPositiveButton(R.string.capture_retry) { _, _ -> launchTestShot() }
                    .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                    .setNeutralButton(R.string.action_why) { _, _ ->
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_faq_speckle))),
                        )
                    }
                    .show()
                return@launch
            }

            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            val w = opts.outWidth.coerceAtLeast(1)
            val h = opts.outHeight.coerceAtLeast(1)
            val bytesPer = file.length().coerceAtLeast(1L)

            val estimate = when (mode) {
                CapturePlanner.Mode.STILLS ->
                    CaptureBudget.estimateStills(w, h, frameCount, bytesPer)
                CapturePlanner.Mode.VIDEO ->
                    CaptureBudget.estimateVideo(w, h, durationSec, frameCount, bytesPer)
            }
            val budget = CaptureBudget.check(estimate, availRam(), availStorage())
            if (!budget.ok) {
                val msg = when {
                    !budget.ramOk && !budget.storageOk -> R.string.capture_budget_fail_both
                    !budget.ramOk -> R.string.capture_budget_fail_ram
                    else -> R.string.capture_budget_fail_storage
                }
                MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                    .setTitle(R.string.capture_budget_fail_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.cancel) { _, _ -> finish() }
                    .show()
                return@launch
            }

            focusLock = CaptureFocusLock.fromExif(
                file,
                check.focusNormX,
                check.focusNormY,
                w,
                h,
            )
            planWidth = w
            planHeight = h
            ensureCameraThenLock()
        }
    }

    private fun evaluateSpeckle(file: File): SubsetRecommender.Result? {
        val bytes = file.readBytes()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        return SubsetRecommender.recommend(
            refBytes = bytes,
            imgW = w,
            imgH = h,
            roi = Rect(0, 0, w, h),
        )
    }

    private fun ensureCameraThenLock() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> openLockedSession()
            else -> requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openLockedSession() {
        tvStatus.setText(R.string.capture_status_locking)
        val focus = focusLock ?: return
        val jpeg = CameraCapabilities.Resolution(planWidth, planHeight)
        val session = LockedCameraSession(this, cameraId, jpeg, focus)
        lockedSession = session

        fun startLock(texture: SurfaceTexture?) {
            lifecycleScope.launch {
                val ok = runCatching { session.openAndLock(texture) }.getOrDefault(false)
                if (!ok) {
                    session.close()
                    lockedSession = null
                    MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                        .setTitle(R.string.capture_af_fail_title)
                        .setMessage(R.string.capture_af_fail_body)
                        .setPositiveButton(R.string.capture_retry) { _, _ -> launchTestShot() }
                        .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                        .show()
                    return@launch
                }
                tvStatus.setText(R.string.capture_status_ready)
                btnStart.isVisible = true
            }
        }

        if (preview.isAvailable) {
            startLock(preview.surfaceTexture)
        } else {
            preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    startLock(st)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }

    private fun startRecording() {
        btnStart.isVisible = false
        when (mode) {
            CapturePlanner.Mode.STILLS -> runStills()
            CapturePlanner.Mode.VIDEO -> runVideo()
        }
    }

    private fun runStills() {
        tvStatus.setText(R.string.capture_status_recording_stills)
        val session = lockedSession ?: return
        val dir = SystemCamera.captureDir(this)
        val runner = StillSequenceRunner(fps = fps, frameCount = frameCount)
        lifecycleScope.launch {
            val result = runner.run(
                sink = object : StillSequenceRunner.CaptureSink {
                    override suspend fun capture(index: Int): String? {
                        val file = File(dir, "frame_%04d.jpg".format(index))
                        if (file.exists()) file.delete()
                        val ok = session.captureStill(file)
                        return if (ok) file.absolutePath else null
                    }
                },
                onProgress = { done, total ->
                    runOnUiThread {
                        tvProgress.text = getString(R.string.capture_progress_fmt, done, total)
                    }
                },
                isActive = { !isFinishing && !isDestroyed },
            )
            if (!result.completed || result.paths.isEmpty()) {
                MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                    .setMessage(R.string.capture_cancelled)
                    .setPositiveButton(R.string.capture_retry) { _, _ -> startRecording() }
                    .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                    .show()
                return@launch
            }
            deformedPaths.clear()
            deformedPaths.addAll(result.paths)
            handOffToWizard()
        }
    }

    private fun runVideo() {
        tvStatus.setText(R.string.capture_status_recording_video)
        val session = lockedSession ?: return
        val dir = SystemCamera.captureDir(this)
        val videoFile = File(dir, "capture.mp4")
        if (videoFile.exists()) videoFile.delete()
        val videoSize = CameraCapabilities.nearest(
            CameraCapabilities.query(this).videoSizes,
            planWidth,
            planHeight,
        )
        lifecycleScope.launch {
            val ok = session.recordVideo(videoFile, durationSec, videoSize)
            if (!ok) {
                MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                    .setMessage(R.string.capture_cancelled)
                    .setPositiveButton(R.string.capture_retry) { _, _ -> startRecording() }
                    .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                    .show()
                return@launch
            }
            tvStatus.setText(R.string.capture_status_extracting)
            val extracted = withContext(Dispatchers.IO) {
                extractVideoFrames(videoFile)
            }
            if (extracted.isNullOrEmpty()) {
                MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                    .setMessage(R.string.capture_video_extract_fail)
                    .setPositiveButton(R.string.capture_retry) { _, _ -> startRecording() }
                    .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                    .show()
                return@launch
            }
            // First extracted frame aligns with reference timing; use all as deformed
            // when test shot is already the reference (skip frame 0 if identical timing).
            deformedPaths.clear()
            deformedPaths.addAll(extracted.drop(1).ifEmpty { extracted })
            handOffToWizard()
        }
    }

    private suspend fun extractVideoFrames(videoFile: File): List<String>? {
        val uri = Uri.fromFile(videoFile)
        val result = VideoFrameExtractor.extract(
            context = this,
            uri = uri,
            fpsExtract = fps.toDouble(),
            startMs = 0L,
            endMs = durationSec * 1000L,
            maxFrames = frameCount + 1,
            cacheDir = cacheDir,
            onProgress = { pct, _ ->
                runOnUiThread {
                    tvProgress.text = "$pct%"
                }
            },
        ) ?: return null
        return result.batch.filePaths
    }

    private fun handOffToWizard() {
        lockedSession?.close()
        lockedSession = null
        val ref = testShotFile?.absolutePath
        if (ref == null || deformedPaths.isEmpty()) {
            finish()
            return
        }
        val intent = Intent(this, StaticAnalysisActivity::class.java).apply {
            putExtra(DicKeys.PICKED_REF_URI, SystemCamera.fileProviderUri(this@CaptureSessionActivity, File(ref)).toString())
            putStringArrayListExtra(
                DicKeys.PICKED_DEF_URIS,
                ArrayList(
                    deformedPaths.map { path ->
                        SystemCamera.fileProviderUri(this@CaptureSessionActivity, File(path)).toString()
                    },
                ),
            )
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // Finish setup + session so Back from wizard returns to Home.
        startActivity(intent)
        finish()
    }

    private fun availRam(): Long {
        val am = getSystemService(ActivityManager::class.java) ?: return 0L
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    private fun availStorage(): Long = cacheDir.usableSpace
}
