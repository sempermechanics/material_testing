package com.indicvision.semper.ui.capture

/**
 * Averages several luma samples of one held state into one.
 *
 * Averaging genuinely recovers sub-LSB information from 8-bit ISP output,
 * because sensor noise dithers the quantiser — this is why it works without
 * RAW. It only holds because the samples are of the same unmoving state; see
 * [AveragingPlan] for how many are worth taking and why.
 *
 * Accumulation happens in sensor space, before rotation, straight off each
 * sample's own row and pixel stride — never assuming a tightly-packed
 * buffer, because a Camera2 plane is not guaranteed to be one. Every sample
 * must share the same width, height, row stride and pixel stride, which they
 * do here because they all come from one locked session's [android.media.ImageReader].
 *
 * Pure JVM: no Android or Camera2 types, so it is unit-testable directly.
 */
internal class LumaAccumulator(first: GrayPngEncoder.Luma) {
    private val width = first.width
    private val height = first.height
    private val rotationDegrees = first.rotationDegrees
    private val sums = IntArray(width * height)
    private var count = 0

    init {
        add(first)
    }

    /** Adds one more sample of the same scene. Must match the first sample's size. */
    fun add(luma: GrayPngEncoder.Luma) {
        require(luma.width == width && luma.height == height) {
            "luma size ${luma.width}x${luma.height} does not match ${width}x$height"
        }
        var i = 0
        for (y in 0 until height) {
            var p = y * luma.rowStride
            repeat(width) {
                sums[i] += luma.bytes[p].toInt() and BYTE_MASK
                p += luma.pixelStride
                i++
            }
        }
        count++
    }

    /** How many samples have gone in so far. */
    val frames: Int get() = count

    /**
     * The rounded mean of every sample added, as a tightly-packed
     * [GrayPngEncoder.Luma] ready to encode straight to PNG.
     */
    fun average(): GrayPngEncoder.Luma {
        val n = count.coerceAtLeast(1)
        val out = ByteArray(sums.size) { idx -> ((sums[idx] + n / 2) / n).toByte() }
        return GrayPngEncoder.Luma(
            bytes = out,
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            rotationDegrees = rotationDegrees,
        )
    }

    private companion object {
        const val BYTE_MASK = 0xFF
    }
}
