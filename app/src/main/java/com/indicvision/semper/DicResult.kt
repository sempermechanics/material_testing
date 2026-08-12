// Binary record layout: the literal byte offsets and strides ARE the on-disk
// format and read clearest inline, so MagicNumber is suppressed.

@file:Suppress("MagicNumber")

package com.indicvision.semper

import androidx.annotation.VisibleForTesting
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.FieldPosition
import java.util.Locale

/** Binary layout constants for native full-field `.dat` output (8 floats × 4 bytes per point). */
object DicResult {
    /** Floats per point — mirrors `SEMPER_FLOATS_PER_POINT` in the engine's C header. */
    const val STRIDE = 8
    const val BYTES_PER_POINT = 32

    const val IDX_X = 0
    const val IDX_Y = 1
    const val IDX_U = 2
    const val IDX_V = 3
    const val IDX_EXX = 4
    const val IDX_EYY = 5
    const val IDX_EXY = 6
    const val IDX_ZNSSD = 7

    const val MAX_ZNSSD = 0.15f
    const val STRAIN_TO_MILLISTRAIN = 1000f

    /** Read chunk for [decodeDatFile]: keeps a small scratch buffer, not a second full copy. */
    private const val DECODE_CHUNK_BYTES = BYTES_PER_POINT * 1024

    fun isValidDatBytes(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes.size % BYTES_PER_POINT == 0

    /**
     * In-memory decode. Production code reads `.dat` from disk with [decodeDatFile],
     * which memory-maps instead of holding a second full-size `ByteArray`; this
     * straightforward version is retained as the reference the decode parity tests
     * check that faster path against.
     */
    @VisibleForTesting
    fun decodeDatBytes(bytes: ByteArray): FloatArray? {
        if (!isValidDatBytes(bytes)) return null
        return FloatArray(bytes.size / 4).also { out ->
            ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(out)
        }
    }

    /**
     * Decode a `.dat` from disk without holding a second full-size [ByteArray].
     * Peak RAM is roughly the [FloatArray] plus a small read buffer — important
     * for heavy PLC frames on a 512 MB heap where `readBytes()` + decode OOM'd.
     */
    fun decodeDatFile(file: File): FloatArray? {
        val len = file.length()
        if (len <= 0L || len > Int.MAX_VALUE.toLong() || len % BYTES_PER_POINT != 0L) return null
        val floatCount = (len / 4L).toInt()
        val out = FloatArray(floatCount)
        val mapped = runCatching {
            FileInputStream(file).channel.use { channel ->
                val map = channel.map(FileChannel.MapMode.READ_ONLY, 0, len)
                map.order(ByteOrder.nativeOrder()).asFloatBuffer().get(out)
            }
            true
        }.getOrDefault(false)
        return if (mapped) out else decodeDatChunked(file, floatCount, out)
    }

    /**
     * Chunked-read fallback for [decodeDatFile]: reads the file through a small heap
     * buffer into [out]. Kept for filesystems/sizes that cannot be memory-mapped.
     */
    private fun decodeDatChunked(file: File, floatCount: Int, out: FloatArray): FloatArray? {
        val complete = FileInputStream(file).channel.use { channel ->
            val buf = ByteBuffer.allocate(DECODE_CHUNK_BYTES).order(ByteOrder.nativeOrder())
            var written = 0
            var intact = true
            while (written < floatCount && intact) {
                buf.clear()
                val n = channel.read(buf)
                if (n <= 0 || n % 4 != 0) {
                    intact = false
                } else {
                    buf.flip()
                    val floats = n / 4
                    buf.asFloatBuffer().get(out, written, floats)
                    written += floats
                }
            }
            intact && written == floatCount
        }
        return if (complete) out else null
    }

    /**
     * The native engine writes a negative ZNSSD sentinel (CORR_INVALID = -1) for
     * skipped/failed points; every real solve has ZNSSD >= 0. Testing `>= 0`
     * instead of `!= 0` keeps a genuinely perfect match (ZNSSD == 0.0) valid.
     */
    fun isSolvedPoint(corr: Float): Boolean = corr >= 0f

    fun isAcceptedPoint(corr: Float, includeCorrelationField: Boolean = false): Boolean =
        if (includeCorrelationField) {
            isSolvedPoint(corr)
        } else {
            isSolvedPoint(corr) && corr <= MAX_ZNSSD
        }

    fun isStrainFieldIndex(dataIndex: Int): Boolean = dataIndex in IDX_EXX..IDX_EXY

    fun strainMultiplier(dataIndex: Int): Float = if (isStrainFieldIndex(dataIndex)) STRAIN_TO_MILLISTRAIN else 1f

    /**
     * `[max, min, mean]` of one field over accepted points, unit-scaled
     * (millistrain for strain fields). Null when no point is accepted.
     */
    fun fieldStats(data: FloatArray, dataIndex: Int): FloatArray? {
        val multiplier = strainMultiplier(dataIndex)
        var maxV = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < data.size) {
            if (isAcceptedPoint(data[i + IDX_ZNSSD])) {
                val v = data[i + dataIndex] * multiplier
                if (v > maxV) maxV = v
                if (v < minV) minV = v
                sum += v
                n++
            }
            i += STRIDE
        }
        return if (n == 0) null else floatArrayOf(maxV, minV, (sum / n).toFloat())
    }

    // ------------------------------------------------------------------
    // CSV export — one format shared by every writer (share sheet, cloud
    // upload). Coordinates are grid integers, displacements/ZNSSD fixed to a
    // few decimals, strains in scientific form (they are ~1e-4).
    // ------------------------------------------------------------------

    /** Header for the per-point columns every CSV export ends with. */
    const val CSV_POINT_HEADER = "x_px,y_px,u_px,v_px,exx,eyy,exy,znssd"

    /** Buffer sizes a CSV writer should use for a large (~10 MB) export. */
    const val CSV_ROW_CAPACITY = 128
    const val CSV_BUFFER_BYTES = 64 * 1024

    /**
     * Formats DIC points into CSV columns, reusing its formatters and the
     * caller's buffer so a large export allocates almost nothing per point.
     * **Not thread-safe** — use one per writing loop. Output is always
     * [Locale.US], so the decimal separator is `.` on any device.
     */
    class CsvPointFormatter {
        private val fixed = DecimalFormat(FIXED_PATTERN, DecimalFormatSymbols(Locale.US))
        private val sci = DecimalFormat(SCI_PATTERN, DecimalFormatSymbols(Locale.US))
        private val pos = FieldPosition(0)

        /** Appends `x,y,u,v,exx,eyy,exy,znssd` for point [i] (no trailing newline). */
        fun appendPoint(row: StringBuffer, data: FloatArray, i: Int) {
            row.append(data[i + IDX_X].toInt()).append(',')
            row.append(data[i + IDX_Y].toInt()).append(',')
            fixed.format(data[i + IDX_U].toDouble(), row, pos).append(',')
            fixed.format(data[i + IDX_V].toDouble(), row, pos).append(',')
            sci.format(data[i + IDX_EXX].toDouble(), row, pos).append(',')
            sci.format(data[i + IDX_EYY].toDouble(), row, pos).append(',')
            sci.format(data[i + IDX_EXY].toDouble(), row, pos).append(',')
            fixed.format(data[i + IDX_ZNSSD].toDouble(), row, pos)
        }

        private companion object {
            /** Up to six decimals, trailing zeros trimmed (px displacements, ZNSSD). */
            const val FIXED_PATTERN = "0.######"

            /** Six significant figures in scientific form (the tiny strain values). */
            const val SCI_PATTERN = "0.######E0"
        }
    }
}
