package com.indicvision.semper.ui.capture

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import timber.log.Timber
import kotlin.math.max

/**
 * Catalogue of back-camera JPEG / video sizes and max fps from Camera2.
 * Does not open a capture session.
 */
object CameraCapabilities {

    data class Resolution(val width: Int, val height: Int) {
        val label: String get() = "${width}×${height}"
        val pixels: Long get() = width.toLong() * height
    }

    data class Info(
        val cameraId: String,
        val jpegSizes: List<Resolution>,
        val videoSizes: List<Resolution>,
        val maxFps: Int,
        /** Typical JPEG stall in ms from the stream config, or a default. */
        val jpegStallMs: Long,
    )

    private val FALLBACK_SIZES = listOf(
        Resolution(4032, 3024),
        Resolution(1920, 1080),
        Resolution(1280, 720),
    )

    fun query(context: Context): Info {
        return runCatching { queryCamera2(context) }.getOrElse {
            Timber.w(it, "Camera2 catalogue unavailable; using fallbacks")
            fallback()
        }
    }

    private fun fallback(): Info = Info(
        cameraId = "0",
        jpegSizes = FALLBACK_SIZES,
        videoSizes = listOf(Resolution(1920, 1080), Resolution(1280, 720)),
        maxFps = 30,
        jpegStallMs = CapturePlanner.DEFAULT_JPEG_STALL_MS,
    )

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

        val video = map.getOutputSizes(android.media.MediaRecorder::class.java)
            ?.map { Resolution(it.width, it.height) }
            ?.let { distinctLargestFirst(it) }
            .orEmpty()
            .ifEmpty { listOf(Resolution(1920, 1080)) }

        val maxFps = maxFpsFrom(chars).coerceIn(1, 240)
        val stall = jpegStallMs(map, jpeg.first())
        return Info(id, jpeg, video, maxFps, stall)
    }

    private fun pickBackCameraId(manager: CameraManager): String? {
        for (id in manager.cameraIdList) {
            val facing = manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return manager.cameraIdList.firstOrNull()
    }

    private fun maxFpsFrom(chars: CameraCharacteristics): Int {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return 30
        var best = 30
        for (r in ranges) {
            best = max(best, r.upper)
        }
        return best
    }

    private fun jpegStallMs(
        map: android.hardware.camera2.params.StreamConfigurationMap,
        size: Resolution,
    ): Long {
        val ns = runCatching {
            map.getOutputMinFrameDuration(ImageFormat.JPEG, Size(size.width, size.height))
        }.getOrDefault(0L)
        if (ns <= 0L) return CapturePlanner.DEFAULT_JPEG_STALL_MS
        return (ns / 1_000_000L).coerceAtLeast(CapturePlanner.DEFAULT_JPEG_STALL_MS)
    }

    private fun distinctLargestFirst(sizes: List<Resolution>): List<Resolution> {
        val minPixels = 640L * 480L
        return sizes
            .filter { it.pixels >= minPixels }
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.pixels }
    }

    /** Closest catalogue size to an actual captured JPEG. */
    fun nearest(sizes: List<Resolution>, width: Int, height: Int): Resolution {
        if (sizes.isEmpty()) return Resolution(width, height)
        val target = width.toLong() * height
        return sizes.minBy { kotlin.math.abs(it.pixels - target) }
    }
}
