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
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private var aeLocked = false
    private val closed = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    suspend fun openAndLock(previewSurfaceTexture: SurfaceTexture?): Boolean =
        withContext(Dispatchers.IO) {
            if (closed.get()) return@withContext false
            startThread()
            val mgr = context.getSystemService(CameraManager::class.java) ?: return@withContext false
            device = openDevice(mgr, cameraId)
            val reader = ImageReader.newInstance(
                jpegSize.width,
                jpegSize.height,
                ImageFormat.JPEG,
                /* maxImages = */ 2,
            )
            imageReader = reader

            val surfaces = mutableListOf<Surface>(reader.surface)
            if (previewSurfaceTexture != null) {
                previewSurfaceTexture.setDefaultBufferSize(
                    jpegSize.width.coerceAtMost(1280),
                    jpegSize.height.coerceAtMost(720),
                )
                val preview = Surface(previewSurfaceTexture)
                previewSurface = preview
                surfaces.add(preview)
            }

            session = createSession(device!!, surfaces)
            lockFocusAndExposure()
        }

    /**
     * Capture one JPEG to [file]. Returns true when the file is non-empty.
     */
    suspend fun captureStill(file: File): Boolean = withContext(Dispatchers.IO) {
        val sess = session ?: return@withContext false
        val reader = imageReader ?: return@withContext false
        val cam = device ?: return@withContext false
        val lens = lockedLensDistance ?: return@withContext false

        val ready = CompletableDeferred<Boolean>()
        reader.setOnImageAvailableListener({
            runCatching {
                it.acquireNextImage().use { image ->
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    FileOutputStream(file).use { out -> out.write(bytes) }
                }
                ready.complete(file.exists() && file.length() > 0L)
            }.onFailure {
                Timber.w(it, "JPEG save failed")
                ready.complete(false)
            }
        }, handler)

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
            },
            handler,
        )
        ready.await()
    }

    /**
     * Record a video of [durationSec] seconds to [file] with AF still locked.
     */
    suspend fun recordVideo(
        file: File,
        durationSec: Int,
        videoSize: CameraCapabilities.Resolution,
    ): Boolean = withContext(Dispatchers.IO) {
        val sess = session
        val cam = device
        val lens = lockedLensDistance
        if (sess == null || cam == null || lens == null) return@withContext false

        val recorder = MediaRecorder()
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(videoSize.width, videoSize.height)
            recorder.setVideoEncodingBitRate(CaptureBudget.VIDEO_BITRATE_BPS.toInt())
            recorder.setVideoFrameRate(30)
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            val recorderSurface = recorder.surface

            // Rebuild session with recorder surface (+ optional preview).
            val surfaces = mutableListOf(recorderSurface)
            previewSurface?.let { surfaces.add(it) }
            imageReader?.let { surfaces.add(it.surface) }
            session?.close()
            session = createSession(cam, surfaces)

            val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recorderSurface)
                previewSurface?.let { addTarget(it) }
                applyLockedControls(this, lens)
            }
            session!!.setRepeatingRequest(builder.build(), null, handler)
            recorder.start()
            kotlinx.coroutines.delay(durationSec.coerceAtLeast(1) * 1000L)
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            file.exists() && file.length() > 0L
        } catch (e: Exception) {
            Timber.e(e, "Video record failed")
            false
        } finally {
            runCatching { recorder.release() }
        }
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
                        camera.close()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
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

    private suspend fun lockFocusAndExposure(): Boolean {
        val sess = session ?: return false
        val cam = device ?: return false
        val reader = imageReader ?: return false
        val chars = context.getSystemService(CameraManager::class.java)
            ?.getCameraCharacteristics(cameraId) ?: return false

        val builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader.surface)
            previewSurface?.let { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            setAfRegion(this, chars)
        }

        val focused = CompletableDeferred<Float?>()
        sess.setRepeatingRequest(
            builder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val state = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                        state == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
                    ) {
                        val dist = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        if (!focused.isCompleted) focused.complete(dist)
                    }
                }
            },
            handler,
        )

        // Trigger AF
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
        sess.capture(builder.build(), null, handler)
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)

        val dist = withTimeoutOrNull(AF_TIMEOUT_MS) { focused.await() }
        if (dist == null) {
            Timber.w("AF lock timed out")
            return false
        }
        lockedLensDistance = dist

        // Lock AE after it settles, then freeze AF (manual lens distance).
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, dist)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        sess.setRepeatingRequest(builder.build(), null, handler)
        aeLocked = true
        kotlinx.coroutines.delay(200L)
        return true
    }

    private fun applyLockedControls(builder: CaptureRequest.Builder, lens: Float) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, lens)
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
        builder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
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
        val half = (sensor.width() * 0.05f).toInt().coerceAtLeast(50)
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

    private suspend fun <T> withTimeoutOrNull(ms: Long, block: suspend () -> T): T? =
        try {
            kotlinx.coroutines.withTimeout(ms) { block() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            null
        }

    companion object {
        private const val AF_TIMEOUT_MS = 4_000L
    }
}
