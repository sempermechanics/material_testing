package com.indicvision.semper.ui.capture

/**
 * RAM and storage gates before (and after) a test shot.
 *
 * Proceed only when available RAM ≥ 1.5× the decode budget and free storage ≥
 * 1.25× the estimated recording footprint.
 */
object CaptureBudget {

    const val RAM_FACTOR = 1.5
    const val STORAGE_FACTOR = 1.25

    /** Bytes per decoded ARGB pixel. */
    const val BYTES_PER_PIXEL = 4

    /**
     * Conservative lossless-PNG estimate before a real frame exists. Speckle
     * texture is high-frequency noise-like content that DEFLATE compresses
     * poorly, so this assumes no compression rather than a JPEG-like ratio.
     * Stills are single-channel 8-bit gray (see [GrayPngEncoder]), so that
     * worst case is one byte per pixel, not one per colour channel.
     */
    const val STILL_BYTES_PER_PIXEL = 1L

    data class Estimate(
        val ramRequiredBytes: Long,
        val storageRequiredBytes: Long,
    )

    data class Check(
        val ok: Boolean,
        val ramOk: Boolean,
        val storageOk: Boolean,
        val estimate: Estimate,
        val availRamBytes: Long,
        val availStorageBytes: Long,
    )

    /**
     * Decode buffer: one full frame × 4 bytes, doubled for a second buffer
     * during decode / extract.
     */
    fun ramRequired(width: Int, height: Int): Long {
        val w = width.coerceAtLeast(1).toLong()
        val h = height.coerceAtLeast(1).toLong()
        return w * h * BYTES_PER_PIXEL * 2L
    }

    fun storageForStills(width: Int, height: Int, frameCount: Int, bytesPerFrame: Long? = null): Long {
        val frames = frameCount.coerceAtLeast(1).toLong()
        val per = bytesPerFrame ?: (
            width.coerceAtLeast(1).toLong() *
                height.coerceAtLeast(1).toLong() *
                STILL_BYTES_PER_PIXEL
            )
        return per * frames
    }

    fun estimateStills(
        width: Int,
        height: Int,
        frameCount: Int,
        bytesPerFrame: Long? = null,
    ): Estimate = Estimate(
        ramRequiredBytes = ramRequired(width, height),
        storageRequiredBytes = storageForStills(width, height, frameCount, bytesPerFrame),
    )

    fun check(
        estimate: Estimate,
        availRamBytes: Long,
        availStorageBytes: Long,
    ): Check {
        val ramNeed = (estimate.ramRequiredBytes * RAM_FACTOR).toLong()
        val storageNeed = (estimate.storageRequiredBytes * STORAGE_FACTOR).toLong()
        val ramOk = availRamBytes >= ramNeed
        val storageOk = availStorageBytes >= storageNeed
        return Check(
            ok = ramOk && storageOk,
            ramOk = ramOk,
            storageOk = storageOk,
            estimate = estimate,
            availRamBytes = availRamBytes,
            availStorageBytes = availStorageBytes,
        )
    }
}
