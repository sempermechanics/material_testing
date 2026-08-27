package com.indicvision.semper.ui.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.core.content.ContextCompat
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.data.DeviceEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera2 session used after a passing test shot: AF once on the focus point,
 * lock lens + AE, then take timed stills or one video.
 */
@Suppress("TooManyFunctions")
class LockedCameraSession(
    private val context: Context,
    private val cameraId: String,
    private val jpegSize: CameraCapabilities.Resolution,
    private val focus: CaptureFocusLock,
) {
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var lockedLensDistance: Float? = null
    private val closed = AtomicBoolean(false)

    /**
     * The imaging-pipeline lockdown resolved for this camera, or null until the
     * session opens. Held so the same plan is written to the preview request,
     * the repeating request and every still — a still processed differently from
     * the frames the noise floor was measured on describes a different camera.
     */
    private var ispPlan: CaptureIspLock.Plan? = null
    private var characteristics: CameraCharacteristics? = null

    /**
     * What the HAL actually did with the lockdown, filled in from the first
     * still's result. Empty until a still completes.
     *
     * The app must not claim a lockdown the hardware refused, and a HAL may
     * accept a key and ignore it, so this is read back rather than assumed. The
     * capture screen turns it into the one collapsed warning the user sees.
     */
    @Volatile
    var ispReport: List<CaptureIspApply.Honoured> = emptyList()
        private set

    /**
     * Settings this device could not give DIC what it wanted, worst first.
     *
     * Both halves of the failure in one list: keys the plan already knew were
     * unavailable or only available as a fallback, and keys the HAL accepted
     * and then ignored. The capture screen collapses it into one warning, and
     * its size is what the noise-floor gate weighs against a failing floor.
     */
    val ispShortfall: List<CaptureIspLock.Key>
        get() = CaptureIspWarning.shortfall(ispPlan, ispReport)

    /**
     * The frozen exposure, once AE has converged and the lock took, or null
     * while exposure is still on auto.
     *
     * Public because the rate ladder has to know: a 50 ms flicker-safe exposure
     * caps the sustainable rate at 20 fps before encoding is even considered,
     * and offering a rate the run cannot hold is the one thing capture setup
     * promises not to do.
     */
    @Volatile
    var exposurePlan: ExposurePlan.Result? = null
        private set

    /**
     * Clockwise rotation applied to every frame this session writes, so the
     * PNGs come out the way the specimen was actually facing rather than the
     * way the sensor is mounted. Resolved once at open — it depends only on
     * the camera and the display, neither of which moves while the capture
     * screen is locked to portrait. See [CaptureOrientation].
     */
    var frameRotationDegrees: Int = 0
        private set

    /**
     * Size of the preview buffer, in sensor orientation. The activity needs it
     * to letterbox the preview to the captured field of view — the preview is
     * the framing surface, so it must show that field of view and no other.
     */
    var previewBufferSize: CameraCapabilities.Resolution = jpegSize
        private set

    /**
     * Set once the platform reports the device gone (onDisconnected/onError)
     * after it was already open. Every subsequent captureStill() would just
     * throw "CameraDevice was already closed" — without this flag,
     * StillSequenceRunner's "retry until success" loop spins on that
     * exception as fast as the CPU allows, forever, with no way out and no
     * signal to the user. [isUsable] lets the caller stop the sequence and
     * show the existing retry/cancel dialog instead.
     */
    private val deviceLost = AtomicBoolean(false)

    /** Guards [logGeometryOnce]; the answer cannot change within a session. */
    private val geometryLogged = AtomicBoolean(false)

    /**
     * The still currently being waited on, or null when nothing is.
     *
     * A capture that times out leaves its frame in flight: the HAL can deliver
     * it seconds later, after this frame has already been given up on. With no
     * claim to check against, that late image was written to whichever file the
     * listener had closed over and reported as a fresh capture, so a run could
     * end holding a frame that was never taken when its filename says.
     *
     * Clearing the claim on timeout makes the arrival discardable. It does not
     * close the window entirely — an image that lands while the *next* capture
     * is outstanding is still claimed by it — but that is deliberate: telling
     * the two apart needs per-frame timestamp matching, and guessing wrong
     * there discards good frames and fails whole runs, which is far worse than
     * one frame carrying an older exposure.
     */
    @Volatile
    private var pendingStill: PendingStill? = null

    private class PendingStill(val file: File, val done: CompletableDeferred<Boolean>)

    /** False once the device is known gone or [close] has been called. */
    val isUsable: Boolean get() = !closed.get() && !deviceLost.get()

    /**
     * True when the camera itself went away — disconnected or errored — as
     * opposed to the caller closing the session. Lets the UI say which of the
     * two happened instead of blaming the user for a cancel they did not make.
     */
    val deviceFailed: Boolean get() = deviceLost.get()

    @SuppressLint("MissingPermission")
    suspend fun openAndLock(previewSurfaceTexture: SurfaceTexture?): Boolean =
        withContext(Dispatchers.IO) {
            if (closed.get()) return@withContext false
            startThread()
            val mgr = context.getSystemService(CameraManager::class.java) ?: return@withContext false
            resolveOrientation(mgr)
            device = openDevice(mgr, cameraId)
            // YUV_420_888, not JPEG: the still path writes this (the ISP's own
            // demosaiced/tuned output, pre-JPEG-compression) to PNG so captured
            // deformed frames are genuinely lossless. See [captureStill].
            val reader = ImageReader.newInstance(
                jpegSize.width,
                jpegSize.height,
                ImageFormat.YUV_420_888,
                /* maxImages = */
                2,
            )
            imageReader = reader

            val surfaces = mutableListOf<Surface>(reader.surface)
            if (previewSurfaceTexture != null) {
                // Not the capture size clamped per-axis: that changes the shape
                // of the frame, and a preview shaped differently from the
                // capture shows the user a field of view they will not get.
                val previewSize = CameraCapabilities.query(context)
                    .previewSizeFor(jpegSize, PREVIEW_MAX_LONG_EDGE)
                previewBufferSize = previewSize
                previewSurfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                val preview = Surface(previewSurfaceTexture)
                previewSurface = preview
                surfaces.add(preview)
            }

            session = createSessionWithRetry(device!!, surfaces)
            lockFocusAndExposure()
        }

    /**
     * Writes one acquired frame's luma plane to [file], turned upright.
     *
     * The rotation is resolved once per session ([resolveOrientation]) rather
     * than per frame: it cannot change while the capture screen is up, and a
     * sequence whose frames disagreed about which way was up would be useless
     * for correlation.
     */
    private fun writeStill(image: android.media.Image, file: File) {
        val plane = image.planes[0]
        val luma = ByteArray(plane.buffer.remaining()).also { buf -> plane.buffer.get(buf) }
        FileOutputStream(file).use { out ->
            GrayPngEncoder.encode(
                out,
                GrayPngEncoder.Luma(
                    bytes = luma,
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride,
                    rotationDegrees = frameRotationDegrees,
                ),
            )
        }
    }

    /**
     * Reads the two facts that decide which way is up, once per session.
     *
     * Failure here is not worth aborting a capture over: a frame rotated wrong
     * is still a frame the user can work with, whereas no frames at all is not.
     */
    private fun resolveOrientation(mgr: CameraManager) {
        runCatching {
            val chars = mgr.getCameraCharacteristics(cameraId)
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val front = chars.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
            val display = ContextCompat.getDisplayOrDefault(context).rotation
            frameRotationDegrees = CaptureOrientation.uprightRotation(
                sensorOrientation = sensor,
                displayRotationDegrees = CaptureOrientation.degreesForSurfaceRotation(display),
                frontFacing = front,
            )
            Timber.d(
                "frame rotation %d deg (sensor=%d display=%d front=%b)",
                frameRotationDegrees,
                sensor,
                display,
                front,
            )
        }.onFailure { Timber.w(it, "orientation unavailable; writing frames unrotated") }
    }

    /**
     * Capture one lossless grayscale PNG still to [file]. Returns true when
     * the file is non-empty.
     *
     * The YUV_420_888 buffer is the ISP's fully-processed output (the same
     * stage the hardware JPEG encoder would consume), so writing it straight
     * to PNG keeps every manufacturer optimization while skipping the only
     * lossy step, JPEG compression. Only the Y (luma) plane is written: it is
     * already the luminance the DIC engine and the speckle check correlate on
     * — both weight colour back down to gray — so the chroma planes are cost
     * without benefit. See [GrayPngEncoder].
     */
    suspend fun captureStill(file: File): Boolean = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext false
        val reader = imageReader ?: return@withContext false
        val cam = device ?: return@withContext false
        val lens = lockedLensDistance ?: return@withContext false

        val ready = CompletableDeferred<Boolean>()
        val issuedNs = System.nanoTime()
        pendingStill = PendingStill(file, ready)
        reader.setOnImageAvailableListener({ r ->
            val claim = pendingStill
            runCatching {
                val frameNs = System.nanoTime()
                r.acquireNextImage().use { image ->
                    if (claim == null) {
                        Timber.w("Discarding a still that arrived after its capture timed out")
                        return@use
                    }
                    writeStill(image, claim.file)
                    // Sensor latency is a hardware floor; the encode scales with
                    // the chosen resolution. Keeping the split visible is what
                    // caught the colour round trip costing ~5.5s a frame.
                    Timber.d(
                        "still %dx%d: sensor=%dms encode=%dms",
                        jpegSize.width,
                        jpegSize.height,
                        (frameNs - issuedNs) / NANOS_PER_MILLI,
                        (System.nanoTime() - frameNs) / NANOS_PER_MILLI,
                    )
                    claim.done.complete(claim.file.length() > 0L)
                }
            }.onFailure {
                Timber.w(it, "PNG save failed")
                claim?.done?.complete(false)
            }
        }, handler)

        // A HAL-level fault (e.g. CAMERA_ERROR after the device wedges) makes
        // createCaptureRequest/capture throw synchronously instead of failing
        // through a callback. Left uncaught, that crashes the whole app on
        // what should just be one failed frame.
        val issued = runCatching {
            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                applyLockedControls(this, lens)
            }
            sess.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        ready.complete(false)
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        logGeometryOnce(result)
                    }
                },
                handler,
            )
        }.onFailure { Timber.w(it, "captureStill: capture() threw") }.isSuccess
        if (!issued) {
            pendingStill = null
            return@withContext false
        }
        // Backgrounding the activity (e.g. to start a screen recording from
        // Quick Settings) can make the platform silently stop delivering
        // camera callbacks — neither onImageAvailable nor onCaptureFailed
        // ever fires. Without a bound here that hangs this frame, and the
        // sequence runner, forever.
        try {
            withTimeout(captureTimeoutMs()) { ready.await() }
        } catch (_: TimeoutCancellationException) {
            Timber.w("captureStill timed out waiting for camera callback")
            false
        } finally {
            pendingStill = null
        }
    }

    /**
     * One line, once per session, saying how much of the sensor this stream
     * actually sees.
     *
     * A locked still at 3264×2448 was measured covering a centred 91.6% of the
     * field of view the vendor Camera app's JPEG gets — margins symmetric to
     * within 2px, rotation 0.01°, so a hand shift cannot explain it. The
     * candidates (a crop region narrower than the active array, a zoom ratio
     * above 1, distortion correction trimming the corrected frame, or simply
     * the requested size not being the widest the stream offers) are all
     * visible here and nowhere else, so print them rather than guess.
     */
    private fun logGeometryOnce(result: TotalCaptureResult) {
        if (!geometryLogged.compareAndSet(false, true)) return
        runCatching {
            val mgr = context.getSystemService(CameraManager::class.java) ?: return
            val chars = mgr.getCameraCharacteristics(cameraId)
            val widest = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.YUV_420_888)
                ?.maxByOrNull { it.width.toLong() * it.height }
            Timber.i(
                "stream geometry: requested=%dx%d widestYuv=%s crop=%s active=%s " +
                    "preCorrection=%s zoom=%s distortion=%s orientation=%s",
                jpegSize.width,
                jpegSize.height,
                widest,
                result.get(CaptureResult.SCALER_CROP_REGION),
                chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE),
                zoomRatioOf(result),
                distortionModeOf(result),
                chars.get(CameraCharacteristics.SENSOR_ORIENTATION),
            )
        }.onFailure { Timber.w(it, "stream geometry unavailable") }
        logPipelineOnce(result)
    }

    /**
     * Records which pipeline keys the HAL honoured, from the same first still.
     *
     * Requesting a key and having it applied are different things: a HAL may
     * accept `NOISE_REDUCTION_MODE = OFF` and keep denoising, and nothing in the
     * request path would say so. The user is warned on what this reports, not on
     * what was asked for — a warning for a key the phone did honour is as much a
     * defect as a missing one.
     */
    private fun logPipelineOnce(result: TotalCaptureResult) {
        val plan = ispPlan ?: return
        val report = runCatching { CaptureIspApply.readBack(result, plan) }
            .onFailure { Timber.w(it, "pipeline read-back unavailable") }
            .getOrNull() ?: return
        ispReport = report
        report.forEach { entry ->
            val state = when (entry.honoured) {
                true -> "honoured"
                false -> "IGNORED"
                null -> "not reported"
            }
            Timber.i("isp %s: requested=%s %s", entry.key, entry.requested, state)
        }
        val unavailable = plan.decisions.filterNot { it.applied }
        if (unavailable.isNotEmpty()) {
            Timber.i("isp unsupported: %s", unavailable.joinToString { it.key.name })
        }
    }

    /** Added in API 30; below that the platform has no zoom ratio to report. */
    private fun zoomRatioOf(result: TotalCaptureResult): Float? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            result.get(CaptureResult.CONTROL_ZOOM_RATIO)
        } else {
            null
        }

    /** Added in API 28. */
    private fun distortionModeOf(result: TotalCaptureResult): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            result.get(CaptureResult.DISTORTION_CORRECTION_MODE)
        } else {
            null
        }

    /**
     * A flat timeout cannot serve every device: one still at 108MP on a slow
     * encoder legitimately takes many seconds, and a fixed 8s ceiling would
     * fail every frame there while being far too generous on a fast phone at
     * low resolution. Scale it off this device's measured cost for the
     * resolution actually in use, with a wide multiplier so only a genuine
     * hang trips it — never merely slow hardware.
     */
    private fun captureTimeoutMs(): Long {
        val expected = CaptureCalibration.estimateFrameMs(context, jpegSize.width, jpegSize.height)
        return (expected * CAPTURE_TIMEOUT_FACTOR)
            .coerceIn(CAPTURE_TIMEOUT_MIN_MS, CAPTURE_TIMEOUT_MAX_MS)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { imageReader?.close() }
        runCatching { previewSurface?.release() }
        session = null
        device = null
        imageReader = null
        previewSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun startThread() {
        val t = HandlerThread("LockedCamera").also { it.start() }
        thread = t
        handler = Handler(t.looper)
    }

    @SuppressLint("MissingPermission")
    private suspend fun openDevice(mgr: CameraManager, id: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            mgr.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (cont.isActive) cont.resume(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        // Fires for the whole device lifetime, not just during
                        // open — including mid-sequence, well after cont has
                        // already resumed. Marking deviceLost here is what
                        // lets captureStill()'s caller notice and stop.
                        deviceLost.set(true)
                        camera.close()
                        Timber.w("Camera disconnected")
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        deviceLost.set(true)
                        camera.close()
                        Timber.w("Camera error %d", error)
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("camera error $error"))
                        }
                    }
                },
                handler,
            )
        }

    @Suppress("DEPRECATION")
    private suspend fun createSession(
        cam: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        cam.createCaptureSession(
            surfaces,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cont.isActive) cont.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("session configure failed"))
                    }
                }
            },
            handler,
        )
    }

    /**
     * On some hardware, reopening the camera and configuring a session right
     * after a previous session on the same device closed (e.g. a
     * Retry-test-shot cycle) briefly fails configuration even though the
     * exact same surfaces succeed moments later. One short-backoff retry
     * avoids surfacing that transient failure as a spurious "could not lock
     * focus".
     */
    private suspend fun createSessionWithRetry(
        cam: CameraDevice,
        surfaces: List<Surface>,
    ): CameraCaptureSession =
        try {
            createSession(cam, surfaces)
        } catch (e: IllegalStateException) {
            Timber.w(e, "Session configure failed; retrying once after a short delay")
            delay(SESSION_RETRY_DELAY_MS)
            createSession(cam, surfaces)
        }

    /**
     * The auto-everything request the lock runs before it freezes anything.
     *
     * Targets the on-screen preview when there is one; see the note in
     * [lockFocusAndExposure] on why the still reader must not be a repeating
     * target unless it is the only surface there is.
     */
    private fun buildAfRequest(
        cam: CameraDevice,
        reader: ImageReader,
        chars: CameraCharacteristics,
    ): CaptureRequest.Builder =
        cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface ?: reader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            setAfRegion(this, chars)
            // Applied here, before AF and AE run, so they converge through the
            // same pipeline the stills will use. This builder is also the one
            // that becomes the repeating request after the lock, so the preview
            // and the run stay on one configuration.
            ispPlan?.let { CaptureIspApply.apply(this, it, chars) }
        }

    @Suppress("ReturnCount")
    private suspend fun lockFocusAndExposure(): Boolean {
        val sess = session ?: return false
        val cam = device ?: return false
        val reader = imageReader ?: return false
        val chars = context.getSystemService(CameraManager::class.java)
            ?.getCameraCharacteristics(cameraId) ?: return false
        characteristics = chars
        ispPlan = CaptureIspLock.plan(CaptureIspApply.profileOf(chars))

        // The still ImageReader must NOT be a target of this repeating request:
        // it only has maxImages=2 and is drained solely by the brief listener
        // captureStill() attaches for each one-shot capture. Feeding it a
        // continuous stream fills that 2-slot queue almost immediately and
        // never empties it, which starves the camera HAL of free buffers for
        // every subsequent request — including the still captures themselves
        // — and makes the whole session capture nothing forever. Prefer the
        // on-screen preview surface here; fall back to the reader only in the
        // rare case no preview surface exists yet, so this request always has
        // at least one target (zero targets throws).
        val focused = CompletableDeferred<Float?>()
        var lastDist: Float? = null
        // A HAL-level fault can make createCaptureRequest/setRepeatingRequest/
        // capture throw synchronously (CameraAccessException) instead of
        // failing through a callback. Treat that the same as a normal AF-lock
        // failure — return false and let the caller offer Retry — rather than
        // letting it crash the app.
        val builder = runCatching {
            val b = buildAfRequest(cam, reader, chars)
            sess.setRepeatingRequest(
                b.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val state = result.get(CaptureResult.CONTROL_AF_STATE)
                        val sampleDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        if (sampleDist != null) lastDist = sampleDist
                        if (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                            state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                            state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                        ) {
                            if (!focused.isCompleted) focused.complete(sampleDist)
                        }
                    }
                },
                handler,
            )
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            sess.capture(b.build(), null, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            b
        }.getOrElse {
            Timber.w(it, "lockFocusAndExposure: initial request(s) threw")
            return false
        }

        val dist = withTimeoutOrNull(AF_TIMEOUT_MS) { focused.await() }
        val allowFallback = BuildConfig.DEBUG && DeviceEnv.isEmulator()
        val lens = AfLockResolver.resolve(dist, lastDist, allowFallback)
        if (lens == null) {
            // Real devices: never start the sequence with a floating lens —
            // refuse and let CaptureSessionActivity offer Retry test shot.
            Timber.w("AF lock timed out")
            return false
        }
        if (dist == null) {
            // Emulators/debug only (some HAL stacks stay in ACTIVE_SCAN and
            // never emit FOCUSED_LOCKED): freeze the last-seen distance and
            // continue rather than block local testing.
            Timber.w("AF lock timed out (emulator fallback); using lens=%s", lens)
            runCatching {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                sess.capture(builder.build(), null, handler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
        }
        lockedLensDistance = lens

        // Freeze the lens first, then let AE re-converge through that focus
        // before freezing it too. Order matters: an exposure converged while
        // the lens was still moving describes a different scene brightness.
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, lens)
        sess.setRepeatingRequest(builder.build(), null, handler)
        delay(AF_SETTLE_DELAY_MS)
        lockExposure(sess, chars, builder)
        return true
    }

    /**
     * Freeze AE onto an exposure that is flicker-safe by construction.
     *
     * Leaving AE running was the older choice here, and its reasoning was
     * sound: freezing whatever duration AE happened to land on is *not*
     * flicker-safe, and under mains lighting a wrong frozen duration stays
     * wrong for every frame after it. The answer is not to leave AE free —
     * that re-converges exposure and ISO between frames, moving both the
     * brightness and the noise level of every image the engine correlates —
     * but to round the frozen exposure up to a whole number of mains
     * half-cycles, so it integrates the same total light whenever the shutter
     * opens. [ExposurePlan] does that arithmetic.
     *
     * Every failure path here leaves AE running, which is exactly the old
     * behaviour: a run with a moving exposure is far better than no run.
     */
    @Suppress("ReturnCount") // AE that never converged, then a device with no lock to take
    private suspend fun lockExposure(
        sess: CameraCaptureSession,
        chars: CameraCharacteristics,
        builder: CaptureRequest.Builder,
    ) {
        val converged = awaitAeConvergence(sess, builder)
        if (converged == null) {
            Timber.w("AE did not converge; leaving auto exposure running")
            return
        }
        val plan = ExposurePlan.plan(converged, sensorLimitsOf(chars))
        if (plan.lock == ExposurePlan.Lock.AUTO) {
            Timber.i("exposure stays on auto: device offers neither manual sensor nor AE lock")
            return
        }
        applyExposure(builder, plan)
        val applied = runCatching { sess.setRepeatingRequest(builder.build(), null, handler) }
            .onFailure { Timber.w(it, "exposure lock rejected; reverting to auto exposure") }
            .isSuccess
        if (!applied) {
            // The builder was already mutated, so it has to be put back before
            // it becomes the repeating request or feeds a still.
            revertExposure(builder)
            runCatching { sess.setRepeatingRequest(builder.build(), null, handler) }
            return
        }
        exposurePlan = plan
        Timber.i(
            "exposure locked: %s %dus iso=%d mains=%s flickerSafe=%b maxFps=%.1f",
            plan.lock,
            plan.exposureNs / NANOS_PER_MICRO,
            plan.sensitivity,
            plan.mains,
            plan.flickerSafe,
            plan.maxFps,
        )
        delay(AE_SETTLE_DELAY_MS)
    }

    /**
     * Wait for AE to settle and report what it settled on.
     *
     * Returns null rather than a half-converged guess when the device *does*
     * report `CONTROL_AE_STATE` and never reaches converged — freezing an
     * exposure AE was still walking towards would be worse than leaving it
     * free. When the device never reports the state at all, the last observed
     * exposure is used instead, since otherwise those devices could never lock.
     */
    private suspend fun awaitAeConvergence(
        sess: CameraCaptureSession,
        builder: CaptureRequest.Builder,
    ): ExposurePlan.Converged? {
        val settled = CompletableDeferred<ExposurePlan.Converged>()
        var last: ExposurePlan.Converged? = null
        var sawState = false
        val issued = runCatching {
            sess.setRepeatingRequest(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                        val sample = ExposurePlan.Converged(
                            exposureNs = exposure,
                            sensitivity = iso,
                            mains = ExposurePlan.mainsFromAntibanding(
                                result.get(CaptureResult.CONTROL_AE_ANTIBANDING_MODE),
                            ),
                        )
                        last = sample
                        val state = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (state != null) sawState = true
                        if (state == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                            state == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                            state == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                        ) {
                            if (!settled.isCompleted) settled.complete(sample)
                        }
                    }
                },
                handler,
            )
        }.onFailure { Timber.w(it, "awaitAeConvergence: setRepeatingRequest threw") }.isSuccess
        if (!issued) return null
        val converged = withTimeoutOrNull(AE_TIMEOUT_MS) { settled.await() }
        return converged ?: last.takeUnless { sawState }
    }

    /** The device's own exposure and ISO limits; no hardcoded values anywhere. */
    private fun sensorLimitsOf(chars: CameraCharacteristics): ExposurePlan.SensorLimits {
        val exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.toSet().orEmpty()
        return ExposurePlan.SensorLimits(
            minExposureNs = exposure?.lower ?: 0L,
            maxExposureNs = exposure?.upper ?: Long.MAX_VALUE,
            minSensitivity = iso?.lower ?: 1,
            maxSensitivity = iso?.upper ?: Int.MAX_VALUE,
            // A device advertising MANUAL_SENSOR without publishing its ranges
            // cannot be given an exposure: there is nothing to clamp against.
            manualSensor = CameraCharacteristics
                .REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps &&
                exposure != null &&
                iso != null,
            aeLockAvailable = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true,
        )
    }

    private fun applyExposure(builder: CaptureRequest.Builder, plan: ExposurePlan.Result) {
        when (plan.lock) {
            ExposurePlan.Lock.MANUAL -> {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, plan.exposureNs)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, plan.sensitivity)
                // Pinned so the sensor cannot stretch the gap between frames and
                // reintroduce the variability the lock just removed.
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, plan.frameDurationNs)
            }

            ExposurePlan.Lock.AE_LOCK -> builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
            ExposurePlan.Lock.AUTO -> Unit
        }
    }

    private fun revertExposure(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
    }

    private fun applyLockedControls(builder: CaptureRequest.Builder, lens: Float) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, lens)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        // TEMPLATE_STILL_CAPTURE re-enables the vendor's stills tuning —
        // stabilisation, denoise, sharpening — regardless of what the preview
        // request was set to, so the lockdown has to be written again here.
        val chars = characteristics
        val plan = ispPlan
        if (chars != null && plan != null) CaptureIspApply.apply(builder, plan, chars)
        // Same reason: the stills template resets AE to the vendor's own, so
        // the frozen exposure has to be written onto every still as well, or
        // the run would be captured at a different exposure from the burst the
        // noise floor was measured on.
        exposurePlan?.let { applyExposure(builder, it) }
        // No JPEG_QUALITY: the still path captures YUV_420_888 and encodes PNG
        // (lossless) instead of the hardware JPEG encoder.
    }

    private fun setAfRegion(
        builder: CaptureRequest.Builder,
        chars: CameraCharacteristics,
    ) {
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        if (maxRegions <= 0) return
        val cx = (sensor.left + sensor.width() * focus.normX).toInt()
        val cy = (sensor.top + sensor.height() * focus.normY).toInt()
        val half = (sensor.width() * AF_REGION_FRACTION).toInt().coerceAtLeast(AF_REGION_MIN_HALF_PX)
        val left = (cx - half).coerceIn(sensor.left, sensor.right - 1)
        val top = (cy - half).coerceIn(sensor.top, sensor.bottom - 1)
        val right = (cx + half).coerceIn(left + 1, sensor.right)
        val bottom = (cy + half).coerceIn(top + 1, sensor.bottom)
        val region = android.hardware.camera2.params.MeteringRectangle(
            left,
            top,
            right - left,
            bottom - top,
            android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX,
        )
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
    }

    companion object {
        private const val AF_TIMEOUT_MS = 4_000L
        private const val AE_TIMEOUT_MS = 3_000L
        private const val AE_SETTLE_DELAY_MS = 200L
        private const val NANOS_PER_MICRO = 1_000L
        private const val CAPTURE_TIMEOUT_FACTOR = 6L
        private const val CAPTURE_TIMEOUT_MIN_MS = 8_000L
        private const val CAPTURE_TIMEOUT_MAX_MS = 60_000L
        private const val SESSION_RETRY_DELAY_MS = 300L

        /** Preview only has to be legible on a phone screen; anything larger
         *  costs bandwidth the still stream wants. */
        private const val PREVIEW_MAX_LONG_EDGE = 1280
        private const val AF_SETTLE_DELAY_MS = 200L
        private const val AF_REGION_FRACTION = 0.05f
        private const val AF_REGION_MIN_HALF_PX = 50
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
