package com.indicvision.semper.ui.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import timber.log.Timber

/**
 * Catalogue of back-camera output sizes from Camera2, with the sensor's own
 * floor on how fast each size can be read out.
 *
 * That floor is per-resolution, not per-device: [StreamConfigurationMap]
 * reports a minimum frame duration and a stall duration for each (format,
 * size) pair, and a full-sensor read is slower than a binned one on the same
 * hardware. A single device-wide "max fps" — which is what the AE target-fps
 * ranges give you — cannot express that, and reading it at full resolution
 * promised rates the sensor was never going to deliver.
 *
 * Durations are queried for YUV_420_888 because that is the format the locked
 * session actually requests (see [LockedCameraSession]); the JPEG figures
 * describe the hardware JPEG encoder, which this path does not use.
 *
 * [Info.yuvSizes] is capped at [CAPTURE_MAX_LONG_EDGE] — a DIC decision, not a
 * hardware one; see that constant.
 */
object CameraCapabilities {

    data class Resolution(val width: Int, val height: Int) {
        val label: String get() = "$width×$height"
        val pixels: Long get() = width.toLong() * height

        /** Width over height. Zero height cannot happen from Camera2, but a
         *  divide-by-zero on the capture path is not worth the risk. */
        val aspect: Float get() = if (height == 0) 0f else width.toFloat() / height
    }

    data class Info(
        val cameraId: String,
        /** YUV_420_888-supported sizes — what the locked-session still ImageReader
         *  actually requests (lossless PNG path). Often capped lower than the vendor
         *  JPEG sizes by ISP/memory bandwidth limits on real hardware; matching
         *  against JPEG sizes instead can request an unsupported stream config and
         *  fail session creation entirely. */
        val yuvSizes: List<Resolution>,
        /** Shortest gap the sensor will allow between two stills at a given size,
         *  in ms. Absent when the device does not report one (LEGACY hardware
         *  level returns zero), in which case only the measured encode cost
         *  bounds the plan. */
        val minFrameMs: Map<Resolution, Long>,
        /** Sizes valid for a [SurfaceTexture] target — the preview stream. A
         *  size the camera does not list here can fail session configuration
         *  outright, so the preview size is chosen from this and not simply
         *  scaled down from the capture size. */
        val previewSizes: List<Resolution>,
    ) {
        /** Sensor floor for [res] in ms, or 0 when this device reports none. */
        fun sensorFloorMs(res: Resolution): Long = minFrameMs[res] ?: 0L

        /**
         * Preview size to frame [capture] with: the largest supported size at
         * or under [maxLongEdge] that shares [capture]'s aspect ratio.
         *
         * Aspect ratio is the part that cannot be compromised. Clamping width
         * and height independently — which is what the old code did — lands on
         * a differently-shaped frame, and then the preview shows a field of
         * view the capture will not have. Since the user frames against this
         * preview, that is not a cosmetic difference.
         */
        /**
         * The catalogued size closest to [width]x[height].
         *
         * Aspect ratio first: a plain |dw| + |dh| metric can land on a
         * differently-shaped size, and a frame whose shape the user never chose
         * is the failure [previewSizeFor] exists to avoid. Only within the
         * closest shape does total pixel count decide.
         */
        fun nearest(width: Int, height: Int): Resolution? {
            if (yuvSizes.isEmpty()) return null
            val wanted = Resolution(width, height).aspect
            val closestShape = yuvSizes.minOf { kotlin.math.abs(it.aspect - wanted) }
            val target = width.toLong() * height
            return yuvSizes
                .filter { kotlin.math.abs(it.aspect - wanted) <= closestShape + ASPECT_EPSILON }
                .minByOrNull { kotlin.math.abs(it.pixels - target) }
        }

        fun previewSizeFor(capture: Resolution, maxLongEdge: Int): Resolution {
            if (previewSizes.isEmpty()) return capture
            val wanted = capture.aspect
            val sameShape = previewSizes.filter { kotlin.math.abs(it.aspect - wanted) <= ASPECT_EPSILON }
            val pool = sameShape.ifEmpty {
                // No exact match: the closest shape still beats a stretch.
                listOfNotNull(previewSizes.minByOrNull { kotlin.math.abs(it.aspect - wanted) })
            }
            val small = pool.filter { maxOf(it.width, it.height) <= maxLongEdge }
            return small.maxByOrNull { it.pixels }
                ?: pool.minByOrNull { it.pixels }
                ?: capture
        }
    }

    private const val FALLBACK_MAX_WIDTH = 4032
    private const val FALLBACK_MAX_HEIGHT = 3024
    private const val RES_1080P_WIDTH = 1920
    private const val RES_1080P_HEIGHT = 1080
    private const val RES_720P_WIDTH = 1280
    private const val RES_720P_HEIGHT = 720
    private const val NANOS_PER_MILLI = 1_000_000L

    /** Aspect ratios within this of each other are the same shape in practice —
     *  4:3 catalogues routinely list 1440x1080 alongside 2048x1536. */
    private const val ASPECT_EPSILON = 0.02f

    /**
     * Longest edge offered for capture: 2K.
     *
     * Not a hardware limit — every phone here shoots larger. It is a DIC
     * limit. Correlation cost, RAM held per frame and PNG encode time all grow
     * with pixel count, and the encode is what sets the sustainable rate
     * ([CaptureFrameCost]), so a 12MP frame buys resolution the solver rarely
     * needs at the price of the frame rate the experiment does. Capping here
     * rather than in the picker keeps [Info.yuvSizes] and the session's own
     * validation looking at the same list.
     */
    const val CAPTURE_MAX_LONG_EDGE = 2048
    private const val MIN_USABLE_WIDTH = 640L
    private const val MIN_USABLE_HEIGHT = 480L

    /**
     * Size to fall back on when a camera reports no usable output at all. 720p
     * because every Camera2 device is required to support it; reaching for it
     * means something is badly wrong, and a working picker beats a crash.
     */
    val LAST_RESORT = Resolution(RES_720P_WIDTH, RES_720P_HEIGHT)

    /** What the still and preview streams fall back to when Camera2 says nothing. */
    private val FALLBACK_STREAM_SIZES = listOf(
        Resolution(RES_1080P_WIDTH, RES_1080P_HEIGHT),
        Resolution(RES_720P_WIDTH, RES_720P_HEIGHT),
    )

    private val FALLBACK_SIZES = listOf(
        Resolution(FALLBACK_MAX_WIDTH, FALLBACK_MAX_HEIGHT),
        Resolution(RES_1080P_WIDTH, RES_1080P_HEIGHT),
        Resolution(RES_720P_WIDTH, RES_720P_HEIGHT),
    )

    @Volatile
    private var cached: Info? = null

    /**
     * The back camera's catalogue, read once per process.
     *
     * Not cheap to build: it opens characteristics for every camera id the
     * device exposes (a binder round trip each, and modern phones list six or
     * more), then asks for min-frame and stall durations per size. Three
     * separate screens want it during one capture, and it cannot change while
     * the app is in the foreground.
     */
    fun query(context: Context): Info = cached ?: synchronized(this) {
        cached ?: runCatching { queryCamera2(context) }.getOrElse {
            Timber.w(it, "Camera2 catalogue unavailable; using fallbacks")
            fallback()
        }.also { cached = it }
    }

    private fun fallback(): Info = Info(
        cameraId = "0",
        yuvSizes = FALLBACK_STREAM_SIZES,
        minFrameMs = emptyMap(),
        previewSizes = FALLBACK_STREAM_SIZES,
    )

    @Suppress("ReturnCount")
    private fun queryCamera2(context: Context): Info {
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return fallback()
        val id = pickBackCameraId(manager) ?: return fallback()
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return fallback()

        val jpeg = map.getOutputSizes(ImageFormat.JPEG)
            ?.map { Resolution(it.width, it.height) }
            ?.let { distinctLargestFirst(it) }
            .orEmpty()
            .ifEmpty { FALLBACK_SIZES }

        val yuv = map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.map { Resolution(it.width, it.height) }
            ?.let { distinctLargestFirst(it) }
            .orEmpty()
            .ifEmpty { jpeg }
            .let { withinCaptureCeiling(it) }

        val previews = map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { Resolution(it.width, it.height) }
            ?.let { distinctLargestFirst(it) }
            .orEmpty()
            .ifEmpty { yuv }

        return Info(id, yuv, minFrameDurations(map, yuv), previews)
    }

    /**
     * Sensor read-out floor per size, from the stream configuration map.
     *
     * Min frame duration and stall duration are added because they are
     * consecutive costs, not alternatives: the stall is time the pipeline
     * spends before it can accept the next request for that stream. Sizes the
     * device declines to answer for are left out rather than defaulted, so an
     * unknown floor reads as "unknown" downstream and never as "fast".
     */
    private fun minFrameDurations(
        map: StreamConfigurationMap,
        sizes: List<Resolution>,
    ): Map<Resolution, Long> = sizes.mapNotNull { res ->
        val size = Size(res.width, res.height)
        val ns = runCatching {
            map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, size) +
                map.getOutputStallDuration(ImageFormat.YUV_420_888, size)
        }.getOrDefault(0L)
        if (ns <= 0L) null else res to (ns / NANOS_PER_MILLI).coerceAtLeast(1L)
    }.toMap()

    private fun pickBackCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    /**
     * Sizes at or under [CAPTURE_MAX_LONG_EDGE]. If a camera reports nothing
     * that small — no phone does, but a fixed-function sensor might — the
     * smallest it does offer is kept, because an empty picker is worse than an
     * over-sized frame.
     */
    internal fun withinCaptureCeiling(sizes: List<Resolution>): List<Resolution> {
        val kept = sizes.filter { maxOf(it.width, it.height) <= CAPTURE_MAX_LONG_EDGE }
        return kept.ifEmpty { listOfNotNull(sizes.minByOrNull { it.pixels }) }
    }

    private fun distinctLargestFirst(sizes: List<Resolution>): List<Resolution> {
        val minPixels = MIN_USABLE_WIDTH * MIN_USABLE_HEIGHT
        return sizes
            .filter { it.pixels >= minPixels }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.pixels }
    }
}
