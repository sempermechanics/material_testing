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
 * buffer, because a Camera2 plane is not guaranteed to be one. Strides may
 * therefore differ between samples; only width and height must match, and
 * they do here because every sample comes from one locked session's
 * [android.media.ImageReader].
 *
 * A sample that does not match is a caller error, not a recoverable one — see
 * [accepts], which is how the capture path asks *before* committing, so a
 * surprise from a vendor HAL costs one group rather than the whole run.
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

    /**
     * Whether [luma] can be added: same frame size, and a buffer long enough
     * for its own declared strides.
     *
     * Asked by the capture path before every [add], because the alternative —
     * letting [add] throw out of a capture loop — turns one odd frame from a
     * vendor HAL into an abandoned run. Here the group falls back to a single
     * still and the run continues, which is what the averaging contract
     * promises.
     */
    fun accepts(luma: GrayPngEncoder.Luma): Boolean =
        luma.width == width &&
            luma.height == height &&
            (width == 0 || height == 0 || lastIndexOf(luma) < luma.bytes.size)

    /** Buffer index of the bottom-right pixel under [luma]'s own strides. */
    private fun lastIndexOf(luma: GrayPngEncoder.Luma): Long =
        (height - 1).toLong() * luma.rowStride + (width - 1).toLong() * luma.pixelStride

    /** Adds one more sample of the same scene. Must match the first sample's size. */
    fun add(luma: GrayPngEncoder.Luma) {
        require(accepts(luma)) {
            "luma ${luma.width}x${luma.height} stride=${luma.rowStride}/${luma.pixelStride} " +
                "bytes=${luma.bytes.size} does not fit ${width}x$height"
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
