package com.indicvision.semper.ui.capture

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Writes an 8-bit grayscale PNG straight from a Camera2 YUV_420_888 luma (Y)
 * plane.
 *
 * The Y plane already *is* the luminance the DIC engine correlates on, so the
 * old path — convert Y+U+V to ARGB, then PNG-encode 32 bits per pixel, only
 * for every consumer to weight it back down to gray — spent ~5.5s a frame
 * producing data it then threw away. Writing Y directly is both far faster (a
 * quarter of the pixel bytes, and no per-pixel colour math) and strictly more
 * faithful: it skips the BT.601 float conversion and rounding that the round
 * trip introduced.
 *
 * [android.graphics.Bitmap.compress] cannot emit true 8-bit grayscale PNG —
 * it always writes RGBA or RGB — hence encoding the container here. The
 * output is a standard, fully lossless PNG (colour type 0), readable by
 * OpenCV's `imdecode` on the native side and by `BitmapFactory`.
 *
 * Pure JVM: no Android or Camera2 types, so it is unit-testable directly.
 */
internal object GrayPngEncoder {

    /**
     * The luma plane to encode. [rowStride] and [pixelStride] come straight
     * from the Camera2 plane: neither is guaranteed to be tight, so rows can
     * carry trailing padding and samples can be interleaved.
     *
     * [width] and [height] describe the plane as the sensor delivered it.
     * [rotationDegrees] is applied on the way out, so the *file* is
     * [outWidth] × [outHeight] — see [CaptureOrientation].
     */
    class Luma(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int = 1,
        rotationDegrees: Int = 0,
    ) {
        /** Clockwise, and always a quarter turn: see [CaptureOrientation.quarterTurn]. */
        val rotationDegrees: Int = CaptureOrientation.quarterTurn(rotationDegrees)

        private val swapsAxes: Boolean = CaptureOrientation.swapsAxes(this.rotationDegrees)

        val outWidth: Int get() = if (swapsAxes) height else width
        val outHeight: Int get() = if (swapsAxes) width else height

        /** Byte distance between two horizontally adjacent output samples. */
        val columnStep: Int
            get() = when (rotationDegrees) {
                CaptureOrientation.ROTATE_90 -> -rowStride
                CaptureOrientation.ROTATE_180 -> -pixelStride
                else -> rowStride
            }

        /** Byte offset of output row [dy]'s first sample. */
        fun rowStart(dy: Int): Int = when (rotationDegrees) {
            CaptureOrientation.ROTATE_90 ->
                (height - 1) * rowStride + dy * pixelStride
            CaptureOrientation.ROTATE_180 ->
                (height - 1 - dy) * rowStride + (width - 1) * pixelStride
            else ->
                (width - 1 - dy) * pixelStride
        }
    }

    /** Encodes [luma] as a grayscale PNG to [out]. */
    fun encode(out: OutputStream, luma: Luma) {
        require(luma.width > 0 && luma.height > 0) {
            "bad size ${luma.width}x${luma.height}"
        }
        out.write(SIGNATURE)
        writeChunk(out, TYPE_IHDR, ihdr(luma.outWidth, luma.outHeight))
        writeChunk(out, TYPE_IDAT, deflate(rawScanlines(luma)))
        writeChunk(out, TYPE_IEND, ByteArray(0))
        out.flush()
    }

    private fun ihdr(width: Int, height: Int): ByteArray = ByteArray(IHDR_LEN).apply {
        putInt(this, 0, width)
        putInt(this, INT_BYTES, height)
        this[BIT_DEPTH_OFFSET] = BIT_DEPTH_8
        this[COLOR_TYPE_OFFSET] = COLOR_TYPE_GRAY
        // Remaining bytes (compression, filter, interlace) stay 0 — the only
        // values the PNG spec defines.
    }

    /**
     * Filter byte + one row of samples per scanline. Filter 0 (None) is
     * deliberate: speckle texture is noise-like, so the adaptive filters cost
     * a full extra pass over every pixel while buying almost no size back.
     */
    private fun rawScanlines(luma: Luma): ByteArray =
        if (luma.rotationDegrees == 0) packedScanlines(luma) else rotatedScanlines(luma)

    private fun packedScanlines(luma: Luma): ByteArray {
        val width = luma.width
        val raw = ByteArray((width + 1) * luma.height)
        var dst = 0
        for (row in 0 until luma.height) {
            raw[dst++] = FILTER_NONE
            val src = row * luma.rowStride
            if (luma.pixelStride == 1) {
                copyPacked(luma, src, raw, dst, width)
            } else {
                copyStrided(luma, src, raw, dst, width)
            }
            dst += width
        }
        return raw
    }

    /**
     * Rotated output, one destination scanline at a time.
     *
     * Written straight into the scanline buffer rather than rotating the plane
     * first: a second full-size buffer per frame is real memory on the devices
     * [CaptureBudget] already worries about, and this way the rotation costs
     * one pass instead of two.
     *
     * The read walks the source with a fixed stride that is not 1 for the
     * quarter turns, which is slower than [copyPacked] — the cost of a
     * transpose. It lands inside the encode that [CaptureCalibration] measures
     * on a real still, so the rate offered on the setup screen already
     * accounts for it.
     */
    private fun rotatedScanlines(luma: Luma): ByteArray {
        val dstW = luma.outWidth
        val dstH = luma.outHeight
        val raw = ByteArray((dstW + 1) * dstH)
        val step = luma.columnStep
        // Hoisted: this inner loop runs once per output pixel — three million
        // times for a 2K frame — and a field read there is not free.
        val src = luma.bytes
        val end = src.size
        var dst = 0
        for (dy in 0 until dstH) {
            raw[dst++] = FILTER_NONE
            var at = luma.rowStart(dy)
            for (dx in 0 until dstW) {
                // Same defence as copyPacked: a plane that ends short of
                // rowStride x height leaves the tail black instead of throwing.
                raw[dst + dx] = if (at >= 0 && at < end) src[at] else 0
                at += step
            }
            dst += dstW
        }
        return raw
    }

    /**
     * Tightly packed row: one `arraycopy`.
     *
     * Vendors differ on whether the final row carries its full stride of
     * padding, so the plane can end a few bytes short of rowStride × height.
     * Copy what is there and leave the rest of the scanline black rather than
     * throwing mid-capture.
     */
    private fun copyPacked(luma: Luma, src: Int, raw: ByteArray, dst: Int, width: Int) {
        val available = (luma.bytes.size - src).coerceIn(0, width)
        if (available > 0) System.arraycopy(luma.bytes, src, raw, dst, available)
    }

    /** Semi-planar row: samples interleaved every [Luma.pixelStride] bytes. */
    private fun copyStrided(luma: Luma, src: Int, raw: ByteArray, dst: Int, width: Int) {
        for (col in 0 until width) {
            val at = src + col * luma.pixelStride
            raw[dst + col] = if (at < luma.bytes.size) luma.bytes[at] else 0
        }
    }

    /**
     * Fastest DEFLATE level: higher levels take markedly longer for a very
     * small gain on noise-like speckle content, and this sits on the capture
     * path where that time is frame rate. Compression level does not affect
     * losslessness.
     */
    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        return try {
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(raw.size + MIN_BUFFER)
            val buf = ByteArray(DEFLATE_CHUNK)
            while (!deflater.finished()) {
                out.write(buf, 0, deflater.deflate(buf))
            }
            out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** length + type + data + CRC32 over type and data. */
    private fun writeChunk(out: OutputStream, type: ByteArray, data: ByteArray) {
        out.write(ByteArray(INT_BYTES).also { putInt(it, 0, data.size) })
        out.write(type)
        out.write(data)
        val crc = CRC32().apply {
            update(type)
            update(data)
        }
        out.write(ByteArray(INT_BYTES).also { putInt(it, 0, crc.value.toInt()) })
    }

    /** Big-endian, as every PNG integer field is. */
    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        for (i in 0 until INT_BYTES) {
            target[offset + i] = (value ushr (BITS_PER_BYTE * (INT_BYTES - 1 - i))).toByte()
        }
    }

    // The fixed 8-byte PNG signature: a high-bit byte, "PNG", CRLF, EOF, LF.
    private val SIGNATURE = byteArrayOf(
        SIG_HIGH_BIT,
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        SIG_CR,
        SIG_LF,
        SIG_EOF,
        SIG_LF,
    )
    private val TYPE_IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
    private val TYPE_IDAT = "IDAT".toByteArray(Charsets.US_ASCII)
    private val TYPE_IEND = "IEND".toByteArray(Charsets.US_ASCII)

    private const val SIG_HIGH_BIT = 0x89.toByte()
    private const val SIG_CR = 0x0D.toByte()
    private const val SIG_LF = 0x0A.toByte()
    private const val SIG_EOF = 0x1A.toByte()

    private const val IHDR_LEN = 13
    private const val INT_BYTES = 4
    private const val BITS_PER_BYTE = 8
    private const val BIT_DEPTH_OFFSET = 8
    private const val COLOR_TYPE_OFFSET = 9
    private const val BIT_DEPTH_8: Byte = 8
    private const val COLOR_TYPE_GRAY: Byte = 0
    private const val FILTER_NONE: Byte = 0
    private const val DEFLATE_CHUNK = 64 * 1024
    private const val MIN_BUFFER = 1024
}
