@file:Suppress("TooManyFunctions", "LongMethod")

package com.indicvision.semper.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Bundle
import android.view.TextureView
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.ui.analysis.RoiDrawActivity
import com.indicvision.semper.ui.analysis.StaticAnalysisActivity
import com.indicvision.semper.ui.analysis.SubsetRecommender
import com.indicvision.semper.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Test shot (Camera app) → ROI for contrast → SSSIG → focus lock →
 * locked reference → timed locked stills → wizard.
 *
 * The test shot is a check, not data: everything handed to the wizard comes
 * off the locked session. See [captureLockedReference].
 */
class CaptureSessionActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnStart: MaterialButton
    private lateinit var preview: TextureView

    /** Gap between frame starts. The only thing calibration is allowed to move:
     *  [frameCount] was promised on the setup screen and is never reduced. */
    private var frameIntervalMs = DEFAULT_DURATION_SEC.toLong() * MILLIS_PER_SECOND / DEFAULT_FRAME_COUNT
    private var durationSec = DEFAULT_DURATION_SEC
    private var frameCount = DEFAULT_FRAME_COUNT
    private var planWidth = DEFAULT_WIDTH
    private var planHeight = DEFAULT_HEIGHT
    private var cameraId = "0"

    private var focusLock: CaptureFocusLock? = null
    private var lockedSession: LockedCameraSession? = null
    private var testShotFile: File? = null

    /** Reference taken through the locked session; see [captureLockedReference]. */
    private var referenceFile: File? = null
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
        openContrastRoi(file)
    }

    private val pickContrastRoi = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val file = testShotFile
        if (file == null || !file.exists()) {
            offerRetryTestShot(getString(R.string.capture_test_shot_empty))
            return@registerForActivityResult
        }
        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            offerRoiAgainOrRetake()
            return@registerForActivityResult
        }
        val data = result.data!!
        val (imgW, imgH) = imageBounds(file)
        val x = data.getIntExtra(DicKeys.ROI_X, 0).coerceIn(0, imgW - 1)
        val y = data.getIntExtra(DicKeys.ROI_Y, 0).coerceIn(0, imgH - 1)
        val w = data.getIntExtra(DicKeys.ROI_W, imgW).coerceAtLeast(1)
            .coerceAtMost(imgW - x)
        val h = data.getIntExtra(DicKeys.ROI_H, imgH).coerceAtLeast(1)
            .coerceAtMost(imgH - y)
        onContrastRoiReady(file, Rect(x, y, x + w, y + h))
    }

    private var afterCameraGranted: (() -> Unit)? = null

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            val next = afterCameraGranted
            afterCameraGranted = null
            next?.invoke()
        } else {
            val retry = afterCameraGranted
            afterCameraGranted = null
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_permission_needed)
                .setPositiveButton(R.string.capture_retry) { _, _ ->
                    if (retry != null) withCameraPermission(retry) else finish()
                }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture_session)
        Insets.padTop(findViewById(R.id.toolbarCaptureSession))
        // A locked capture takes no touch input for its whole duration, so any
        // run longer than the display timeout puts the screen to sleep — which
        // backgrounds the app and has the HAL revoke the camera
        // (ERROR_CAMERA_DEVICE) mid-sequence. Observed killing a run at ~37s
        // against a 30s screen timeout, and it would do the same on any device.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        durationSec = intent.getIntExtra(CaptureSetupActivity.EXTRA_DURATION_SEC, DEFAULT_DURATION_SEC)
            .coerceAtLeast(1)
        frameCount = intent.getIntExtra(CaptureSetupActivity.EXTRA_FRAME_COUNT, DEFAULT_FRAME_COUNT)
            .coerceAtLeast(1)
        frameIntervalMs = intent
            .getLongExtra(
                CaptureSetupActivity.EXTRA_INTERVAL_MS,
                durationSec.toLong() * MILLIS_PER_SECOND / frameCount,
            )
            .coerceAtLeast(1L)
        planWidth = intent.getIntExtra(CaptureSetupActivity.EXTRA_WIDTH, DEFAULT_WIDTH)
        planHeight = intent.getIntExtra(CaptureSetupActivity.EXTRA_HEIGHT, DEFAULT_HEIGHT)
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

        val restored = restoreFrom(savedInstanceState)
        if (restored) {
            // Process death after a passing test shot: the focus point and
            // the file it came from are still on disk — re-lock directly
            // instead of asking for a new test shot.
            ensureCameraThenLock()
        } else {
            // IMAGE_CAPTURE crashes if CAMERA is declared but not granted (Android 11+).
            withCameraPermission { launchTestShot() }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        focusLock?.let { outState.putSerializable(STATE_FOCUS_LOCK, it) }
        outState.putStringArrayList(STATE_DEFORMED_PATHS, ArrayList(deformedPaths))
        outState.putInt(STATE_PLAN_WIDTH, planWidth)
        outState.putInt(STATE_PLAN_HEIGHT, planHeight)
        outState.putString(STATE_CAMERA_ID, cameraId)
        outState.putLong(STATE_INTERVAL_MS, frameIntervalMs)
        outState.putString(STATE_REFERENCE_PATH, referenceFile?.absolutePath)
    }

    /** True when a resumable [CaptureFocusLock] and its source file survived. */
    @Suppress("ReturnCount")
    private fun restoreFrom(savedInstanceState: Bundle?): Boolean {
        val bundle = savedInstanceState ?: return false
        val lock = BundleCompat.getSerializable(bundle, STATE_FOCUS_LOCK, CaptureFocusLock::class.java)
            ?: return false
        val file = File(lock.testShotPath)
        if (!file.exists() || file.length() == 0L) return false
        focusLock = lock
        testShotFile = file
        deformedPaths.clear()
        deformedPaths.addAll(bundle.getStringArrayList(STATE_DEFORMED_PATHS).orEmpty())
        planWidth = bundle.getInt(STATE_PLAN_WIDTH, planWidth)
        planHeight = bundle.getInt(STATE_PLAN_HEIGHT, planHeight)
        cameraId = bundle.getString(STATE_CAMERA_ID) ?: cameraId
        frameIntervalMs = bundle.getLong(STATE_INTERVAL_MS, frameIntervalMs).coerceAtLeast(1L)
        // Only if it survived: a half-written reference from a killed process
        // would be handed to the wizard as though it were a real frame.
        referenceFile = bundle.getString(STATE_REFERENCE_PATH)
            ?.let { File(it) }
            ?.takeIf { it.exists() && it.length() > 0L }
        return true
    }

    override fun onDestroy() {
        lockedSession?.close()
        lockedSession = null
        super.onDestroy()
    }

    private fun withCameraPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onGranted()
        } else {
            afterCameraGranted = onGranted
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchTestShot() {
        tvStatus.setText(R.string.capture_status_test_shot)
        btnStart.isVisible = false
        val dir = SystemCamera.captureDir(this)
        val file = File(dir, CaptureWorkspace.TEST_SHOT_NAME)
        file.delete()
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
        try {
            takeTestShot.launch(intent)
        } catch (_: SecurityException) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.capture_permission_needed)
                .setPositiveButton(R.string.capture_retry) { _, _ ->
                    withCameraPermission { launchTestShot() }
                }
                .setNegativeButton(R.string.cancel) { _, _ -> finish() }
                .show()
        }
    }

    private fun offerRetryTestShot(message: String) = showRetryDialog(message) { launchTestShot() }

    /**
     * The one shape every dead end on this screen takes: say what went wrong,
     * offer the step again, or leave. Cancel always finishes — there is
     * nothing to return to with a half-built plan.
     */
    private fun showRetryDialog(message: CharSequence, onRetry: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(R.string.capture_retry) { _, _ -> onRetry() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun offerRoiAgainOrRetake() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_roi_needed_title)
            .setMessage(R.string.capture_roi_needed_body)
            .setPositiveButton(R.string.capture_roi_select) { _, _ ->
                testShotFile?.let { openContrastRoi(it) } ?: launchTestShot()
            }
            .setNeutralButton(R.string.capture_retry) { _, _ -> launchTestShot() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .show()
    }

    /** Opens the ROI editor so the user picks where contrast is measured. */
    private fun openContrastRoi(file: File) {
        tvStatus.setText(R.string.capture_status_select_roi)
        btnStart.isVisible = false
        // Camera JPEGs often tag ORIENTATION_ROTATE_90 while OpenCV preview is
        // upright — mismatched dims made ROI Y past the declared height.
        lifecycleScope.launch {
            // Baking EXIF orientation into the pixels is a full-resolution
            // decode, rotate and re-encode — seconds and ~100MB of bitmap on a
            // 12MP shot. On the main thread that is an ANR, and this is reached
            // straight from an activity-result callback.
            val bounds = withContext(Dispatchers.IO) {
                CaptureJpegOrient.uprightInPlace(file)
                imageBounds(file)
            }
            if (bounds.first <= 1 && bounds.second <= 1) {
                offerRetryTestShot(getString(R.string.capture_test_shot_empty))
                return@launch
            }
            pickContrastRoi.launch(
                Intent(this@CaptureSessionActivity, RoiDrawActivity::class.java).apply {
                    putExtra(DicKeys.IMAGE_FILE_PATH, file.absolutePath)
                    putExtra(DicKeys.IMAGE_WIDTH, bounds.first)
                    putExtra(DicKeys.IMAGE_HEIGHT, bounds.second)
                },
            )
        }
    }

    /** Pixel size of [file] from its header alone, clamped so callers can
     *  divide by it. (1, 1) means nothing decodable was there. */
    private fun imageBounds(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth.coerceAtLeast(1) to opts.outHeight.coerceAtLeast(1)
    }

    private fun onContrastRoiReady(file: File, roi: Rect) {
        tvStatus.setText(R.string.capture_status_checking)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { evaluateSpeckleBounded(file, roi) }
            if (outcome == SpeckleOutcome.TimedOut) {
                offerRetryTestShot(getString(R.string.capture_speckle_check_timeout))
                return@launch
            }
            val check = (outcome as SpeckleOutcome.Done).result
            if (check == null || check.lowTexture) {
                showSpeckleFailDialog(file, tooSmall = check == null)
                return@launch
            }

            val (w, h) = imageBounds(file)
            val caps = CameraCapabilities.query(this@CaptureSessionActivity)

            // The test shot's own size is the vendor Camera app's choice, not
            // the user's — the budget has to be checked against what this run
            // will actually write.
            applySupportedResolution(caps)
            if (!checkBudgetOrShowDialog(planWidth, planHeight)) return@launch

            focusLock = CaptureFocusLock.fromExif(file, check.focusNormX, check.focusNormY, w, h)
            // Software PNG encode has no Camera2-reported stall (unlike JPEG),
            // so pacing is measured on a real locked still once the session is
            // up — see [calibrateAgainstRealCapture] — not guessed from here.
            ensureCameraThenLock()
        }
    }

    private fun showSpeckleFailDialog(file: File, tooSmall: Boolean) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (tooSmall) R.string.capture_roi_too_small_title else R.string.capture_speckle_fail_title,
            )
            .setMessage(
                if (tooSmall) R.string.capture_roi_too_small_body else R.string.capture_speckle_fail_body,
            )
            .setPositiveButton(R.string.capture_roi_select) { _, _ -> openContrastRoi(file) }
            .setNegativeButton(R.string.capture_retry) { _, _ -> launchTestShot() }
        if (!tooSmall) {
            dialog.setNeutralButton(R.string.action_why) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_faq_speckle))))
            }
        } else {
            dialog.setNeutralButton(R.string.cancel) { _, _ -> finish() }
        }
        // Deferred a frame: showing a dialog synchronously right after an
        // activity-result callback returns has been unreliable in testing.
        tvStatus.post {
            if (!isFinishing && !isDestroyed) dialog.show()
        }
    }

    /**
     * No bytesPer from the test shot: deformed frames are lossless PNG, not
     * comparable in size to the vendor Camera app's JPEG — the budget's own
     * conservative PNG-per-pixel constant applies instead. Shows the fail
     * dialog and returns false when the plan doesn't fit.
     */
    private fun checkBudgetOrShowDialog(w: Int, h: Int): Boolean {
        val estimate = CaptureBudget.estimateStills(w, h, frameCount)
        val budget = CaptureBudget.check(
            estimate,
            CaptureResources.availRamBytes(this),
            CaptureResources.availStorageBytes(this),
        )
        if (budget.ok) return true
        val msg = when {
            !budget.ramOk && !budget.storageOk -> R.string.capture_budget_fail_both
            !budget.ramOk -> R.string.capture_budget_fail_ram
            else -> R.string.capture_budget_fail_storage
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.capture_budget_fail_title)
            .setMessage(msg)
            .setPositiveButton(R.string.cancel) { _, _ -> finish() }
            .show()
        return false
    }

    /**
     * Pins the run to the resolution chosen on the setup screen.
     *
     * This used to match against the *test shot's* dimensions instead, which
     * silently threw the choice away: the vendor Camera app shoots at whatever
     * size it likes, so picking 3264×2448 in setup and getting the camera
     * app's 4080×3072 back meant every frame was written at a size the user
     * never asked for and the plan was never costed against.
     *
     * The list matched against is [CameraCapabilities.Info.yuvSizes], not the
     * camera's JPEG sizes: the locked session's still ImageReader requests
     * YUV_420_888 (for lossless PNG), and real hardware often supports a
     * smaller max YUV size than JPEG. Matching against JPEG sizes here can
     * request an unsupported stream configuration and fail session creation
     * outright.
     *
     * The chosen size came from that same list, so the nearest-match is a
     * no-op in the normal case; it only bites if the catalogue changed between
     * setup and now (camera hot-swap, process death, a restored instance
     * state).
     */
    private fun applySupportedResolution(caps: CameraCapabilities.Info) {
        val matched = caps.nearest(planWidth, planHeight)
        if (matched != null && (matched.width != planWidth || matched.height != planHeight)) {
            Timber.w(
                "Chosen %dx%d is not in this camera's YUV catalogue; using nearest %dx%d",
                planWidth,
                planHeight,
                matched.width,
                matched.height,
            )
        }
        planWidth = matched?.width ?: planWidth
        planHeight = matched?.height ?: planHeight
        cameraId = caps.cameraId
    }

    /**
     * Re-paces the run against the cost a real still just took, without ever
     * changing the frame count.
     *
     * The count is a promise made on the setup screen, where it was chosen
     * from [CapturePlanOptions] — counts that already carry the calibration's
     * safety factor and [CapturePlanOptions.ASSURANCE_MARGIN] on top. Trimming
     * it here is what produced "I picked 150 and got 60": the user plans an
     * experiment around a number and the app quietly delivers a different one.
     * If this device turns out slower than both margins allowed, the run
     * overruns its duration slightly and still delivers every frame promised —
     * a late frame is usable data, a missing one is not.
     *
     * The interval only ever grows: pacing faster than a frame physically
     * costs would just queue captures that arrive late anyway.
     */
    private fun applyMeasuredFrameCost(perFrameMs: Long) {
        // Spread the promised frames across the whole requested duration.
        // Pacing at the raw per-frame cost instead would bunch them into the
        // start of the run — 30 frames of a 120s test crammed into the first
        // 10s, missing the deformation that follows.
        frameIntervalMs = (durationSec * MILLIS_PER_SECOND / frameCount.coerceAtLeast(1))
            .coerceAtLeast(perFrameMs.coerceAtLeast(1L))
        val projectedSec = frameCount * frameIntervalMs / MILLIS_PER_SECOND
        if (projectedSec > durationSec) {
            Timber.d(
                "Frame cost %dms exceeds the assured budget; %d frames will take ~%ds not %ds",
                perFrameMs,
                frameCount,
                projectedSec,
                durationSec,
            )
        }
    }

    private fun evaluateSpeckle(file: File, roi: Rect): SubsetRecommender.Result? {
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
            roi = roi,
        )
    }

    private sealed interface SpeckleOutcome {
        data class Done(val result: SubsetRecommender.Result?) : SpeckleOutcome
        data object TimedOut : SpeckleOutcome
    }

    /**
     * [SubsetRecommender.recommend] ultimately calls into `BitmapRegionDecoder`,
     * a native codec call observed to hang indefinitely on some device/emulator
     * media stacks with no exception and no ANR (it blocks a background
     * thread, not the main thread). A coroutine `withTimeoutOrNull` around a
     * plain blocking call does not actually preempt it — the dispatcher
     * thread executing it never returns control. Racing it on its own
     * executor with a bounded `Future.get` does: on timeout this abandons
     * the stuck thread (leaked, harmless) and lets the UI recover instead of
     * freezing forever.
     */
    private fun evaluateSpeckleBounded(file: File, roi: Rect): SpeckleOutcome {
        val future = speckleCheckExecutor.submit<SubsetRecommender.Result?> { evaluateSpeckle(file, roi) }
        return try {
            SpeckleOutcome.Done(future.get(SPECKLE_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            Timber.w("Speckle check timed out")
            future.cancel(true)
            SpeckleOutcome.TimedOut
        } catch (e: ExecutionException) {
            Timber.w(e, "Speckle check failed")
            SpeckleOutcome.Done(null)
        }
    }

    private fun ensureCameraThenLock() {
        withCameraPermission { openLockedSession() }
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
                applyPreviewTransform(session)
                calibrateAgainstRealCapture(session)
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

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    // The letterboxing is computed from the view's size, so it
                    // has to be redone when that changes.
                    lockedSession?.let { applyPreviewTransform(it) }
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }

    /**
     * Makes the preview show exactly the frame that will be written: same
     * rotation, same field of view, same shape.
     *
     * A [TextureView] left alone stretches the camera buffer to its own bounds
     * and ignores sensor orientation entirely, which on a portrait phone means
     * a sideways, distorted picture. That is bad as a picture and worse as a
     * framing surface — the user lines a specimen up against the frame edges,
     * and until now those edges were not where the capture's edges are.
     *
     * The transform is composed on top of the default buffer-to-view stretch,
     * so it starts by undoing it: back into buffer pixels, rotate about the
     * centre, scale to fit, then centre in the view.
     */
    private fun applyPreviewTransform(session: LockedCameraSession) {
        val viewW = preview.width
        val viewH = preview.height
        val buffer = session.previewBufferSize
        if (minOf(viewW, viewH, buffer.width, buffer.height) <= 0) return
        val rotation = session.frameRotationDegrees
        val scale = CaptureOrientation.previewFitScale(
            viewW = viewW,
            viewH = viewH,
            bufW = buffer.width,
            bufH = buffer.height,
            rotationDegrees = rotation,
        )
        val matrix = Matrix().apply {
            postScale(buffer.width.toFloat() / viewW, buffer.height.toFloat() / viewH)
            postTranslate(-buffer.width / 2f, -buffer.height / 2f)
            postRotate(rotation.toFloat())
            postScale(scale, scale)
            postTranslate(viewW / 2f, viewH / 2f)
        }
        preview.setTransform(matrix)
    }

    /**
     * [measureStillStallMs] only times the PNG encode; it misses the camera
     * round trip (request → sensor → YUV frame → write), which dominates on
     * real hardware. Planning from encode time alone leaves fps optimistic,
     * so every frame lands late and the run overshoots the chosen duration.
     * Time one real still through the exact path the sequence uses and
     * re-derive the rate from that. The warm-up frame is discarded, and
     * taking it also primes the pipeline so frame 0 isn't the slow one.
     */
    private suspend fun calibrateAgainstRealCapture(session: LockedCameraSession) {
        val warmup = File(SystemCamera.captureDir(this), CaptureWorkspace.WARMUP_NAME)
        val startNs = System.nanoTime()
        val ok = session.captureStill(warmup)
        val elapsedMs = ((System.nanoTime() - startNs) / NANOS_PER_MILLI).coerceAtLeast(1L)
        warmup.delete()
        if (!ok) return
        Timber.d("Warm-up still took %d ms", elapsedMs)
        // Feeds the setup screen's frame-count offers next time, so they
        // reflect this device rather than Camera2's unrelated JPEG stall.
        CaptureCalibration.record(this, planWidth, planHeight, elapsedMs)
        applyMeasuredFrameCost(elapsedMs)
    }

    private fun startRecording() {
        btnStart.isVisible = false
        runStills()
    }

    /**
     * Takes the reference through the locked session, at the same resolution
     * and under the same locked focus and exposure as every deformed frame.
     *
     * The camera-app test shot cannot serve as the reference: it is JPEG, it
     * is shot with the vendor app's own auto-everything, and it comes out at
     * whatever resolution that app prefers — which then forced every lossless
     * frame to be resampled to match it. Correlation compares the reference
     * against each frame pixel for pixel, so all three of those differences
     * land directly in the measurement. The test shot stays what it is good
     * for: picking the contrast ROI and measuring speckle before committing.
     */
    private suspend fun captureLockedReference(session: LockedCameraSession): Boolean {
        tvStatus.setText(R.string.capture_status_reference)
        tvProgress.text = ""
        val file = File(SystemCamera.captureDir(this), CaptureWorkspace.REFERENCE_NAME)
        file.delete()
        val ok = session.captureStill(file)
        referenceFile = if (ok) file else null
        return ok
    }

    private fun runStills() {
        tvStatus.setText(R.string.capture_status_recording_stills)
        val session = lockedSession ?: return
        val dir = SystemCamera.captureDir(this)
        val runner = StillSequenceRunner(intervalMs = frameIntervalMs, frameCount = frameCount)
        var budgetExceededMidRun = false
        lifecycleScope.launch {
            // Before the first frame, not after the last: a crash or a cancel
            // must not leave one run's frames where the next run will pick
            // them up as its own.
            val cleared = withContext(Dispatchers.IO) {
                CaptureWorkspace.clearPreviousRun(dir)
            }
            if (cleared > 0) Timber.d("Cleared %d file(s) from a previous run", cleared)
            if (!captureLockedReference(session)) {
                showRetryDialog(getText(incompleteRunMessage(session))) { startRecording() }
                return@launch
            }
            tvStatus.setText(R.string.capture_status_recording_stills)
            val result = runner.run(
                sink = object : StillSequenceRunner.CaptureSink {
                    override suspend fun capture(index: Int): String? {
                        val file = File(dir, CaptureWorkspace.frameName(index))
                        val ok = session.captureStill(file)
                        return if (ok) file.absolutePath else null
                    }
                },
                onProgress = { done, total ->
                    // PNG size is far less predictable than JPEG's — recheck
                    // storage from the first real frame's byte size before
                    // committing to the rest of the sequence.
                    if (done == 1) {
                        val remaining = total - done
                        if (remaining > 0) {
                            val firstBytes = File(dir, CaptureWorkspace.frameName(0)).length()
                                .takeIf { it > 0 }
                            val remainingEstimate = CaptureBudget.estimateStills(
                                planWidth,
                                planHeight,
                                remaining,
                                firstBytes,
                            )
                            val remainingCheck =
                                CaptureBudget.check(
                                    remainingEstimate,
                                    CaptureResources.availRamBytes(this@CaptureSessionActivity),
                                    CaptureResources.availStorageBytes(this@CaptureSessionActivity),
                                )
                            if (!remainingCheck.ok) budgetExceededMidRun = true
                        }
                    }
                    runOnUiThread {
                        tvProgress.text = getString(R.string.capture_progress_fmt, done, total)
                    }
                },
                isActive = { !isFinishing && !isDestroyed && !budgetExceededMidRun && session.isUsable },
            )
            if (budgetExceededMidRun) {
                MaterialAlertDialogBuilder(this@CaptureSessionActivity)
                    .setTitle(R.string.capture_budget_fail_title)
                    .setMessage(R.string.capture_budget_fail_storage)
                    .setPositiveButton(R.string.cancel) { _, _ -> finish() }
                    .show()
                return@launch
            }
            if (!result.completed || result.paths.isEmpty()) {
                showRetryDialog(getText(incompleteRunMessage(session))) { startRecording() }
                return@launch
            }
            deformedPaths.clear()
            deformedPaths.addAll(result.paths)
            handOffToWizard()
        }
    }

    /**
     * Why a run ended short. Nothing here cancelled it unless the user left,
     * so name which of the three actually happened rather than blaming a
     * mistake they did not make.
     */
    private fun incompleteRunMessage(session: LockedCameraSession): Int = when {
        session.deviceFailed -> R.string.capture_camera_lost
        session.isUsable && !isFinishing -> R.string.capture_frames_failed
        else -> R.string.capture_cancelled
    }

    private suspend fun handOffToWizard() {
        lockedSession?.close()
        lockedSession = null
        // The locked reference when there is one; the test shot only as a
        // fallback, since it costs the frames a resample (see below).
        val ref = (referenceFile ?: testShotFile)?.absolutePath
        if (ref == null || deformedPaths.isEmpty()) {
            finish()
            return
        }
        // Why this has to happen at all, and why it is usually a no-op:
        // see [CaptureFrameSizeMatcher].
        tvStatus.setText(R.string.capture_status_matching_sizes)
        tvProgress.text = ""
        val matchedPaths = CaptureFrameSizeMatcher
            .matchToReference(File(ref), deformedPaths) { done, total ->
                runOnUiThread {
                    tvProgress.text = getString(R.string.capture_progress_fmt, done, total)
                }
            }
        val intent = Intent(this, StaticAnalysisActivity::class.java).apply {
            putExtra(
                DicKeys.PICKED_REF_URI,
                SystemCamera.fileProviderUri(this@CaptureSessionActivity, File(ref)).toString(),
            )
            putStringArrayListExtra(
                DicKeys.PICKED_DEF_URIS,
                ArrayList(
                    matchedPaths.map { path ->
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

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MILLIS_PER_SECOND = 1_000L

        /** Only reached when the setup extra is missing; the setup screen owns the value. */
        const val DEFAULT_DURATION_SEC = CaptureSetupActivity.DEFAULT_DURATION_SEC
        const val DEFAULT_FRAME_COUNT = 10
        const val SPECKLE_CHECK_TIMEOUT_MS = 8_000L
        val speckleCheckExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "SpeckleCheck").apply { isDaemon = true }
        }
        const val DEFAULT_WIDTH = 1920
        const val DEFAULT_HEIGHT = 1080
        const val STATE_FOCUS_LOCK = "capture_focus_lock"
        const val STATE_DEFORMED_PATHS = "capture_deformed_paths"
        const val STATE_PLAN_WIDTH = "capture_plan_width"
        const val STATE_PLAN_HEIGHT = "capture_plan_height"
        const val STATE_CAMERA_ID = "capture_camera_id_state"
        const val STATE_INTERVAL_MS = "capture_interval_ms_state"
        const val STATE_REFERENCE_PATH = "capture_reference_path"
    }
}
