package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import java.io.File
import java.io.Writer

/**
 * The analysis CSV, shared by the share-sheet export and the cloud upload so the
 * two never drift: one file covering every frame's solved points. A sweep leads
 * each row with its settings (subset/step/strain window/VSG); an ordinary
 * analysis leads with the image name. The point columns and all number
 * formatting come from [DicResult.CsvPointFormatter].
 *
 * The writer reuses one buffer and formatter and appends straight through, so a
 * large (~10 MB, ~90k-point) export allocates almost nothing per point.
 */
object AnalysisCsvWriter {

    /** One frame: its identity columns plus a lazy provider of its decoded field. */
    class Frame(
        val image: String,
        val subset: Int,
        val step: Int,
        val strainWindow: Int,
        val data: () -> FloatArray?,
    )

    private val HEADER_SWEEP = "image,subset_px,step_px,strain_window,vsg_px," + DicResult.CSV_POINT_HEADER
    private val HEADER_SINGLE = "image," + DicResult.CSV_POINT_HEADER

    fun write(out: File, sweep: Boolean, frames: List<Frame>) {
        open(out, sweep).use { appender ->
            frames.forEach { appender.append(it) }
        }
    }

    /**
     * Streaming writer so a caller that already decoded a frame's `.dat` can
     * append CSV rows without holding every frame in memory (upload staging
     * shares one decode pass with report bake).
     */
    fun open(out: File, sweep: Boolean): Appender {
        val w = out.bufferedWriter(bufferSize = DicResult.CSV_BUFFER_BYTES)
        w.append(if (sweep) HEADER_SWEEP else HEADER_SINGLE).append('\n')
        return Appender(w, sweep)
    }

    /** One open CSV file; call [append] per frame then [close]. */
    class Appender internal constructor(
        private val writer: Writer,
        private val sweep: Boolean,
    ) : AutoCloseable {
        private val row = StringBuffer(DicResult.CSV_ROW_CAPACITY)
        private val formatter = DicResult.CsvPointFormatter()

        fun append(frame: Frame) {
            writeFrame(writer, frame, sweep, row, formatter)
        }

        override fun close() {
            writer.close()
        }
    }

    /** Appends one frame's solved points, each row led by [prefix]. */
    @Suppress("LongParameterList") // the reused row buffer and formatter, threaded in to avoid re-allocating
    private fun writeFrame(
        w: Writer,
        frame: Frame,
        sweep: Boolean,
        row: StringBuffer,
        formatter: DicResult.CsvPointFormatter,
    ) {
        val data = frame.data() ?: return
        val prefix = prefix(frame, sweep)
        var i = 0
        while (i < data.size) {
            if (DicResult.isSolvedPoint(data[i + DicResult.IDX_ZNSSD])) {
                row.setLength(0)
                row.append(prefix)
                formatter.appendPoint(row, data, i)
                row.append('\n')
                w.append(row)
            }
            i += DicResult.STRIDE
        }
    }

    /** The constant leading columns for one frame. */
    private fun prefix(frame: Frame, sweep: Boolean): String {
        val image = escape(frame.image)
        if (!sweep) return "$image,"
        val vsg = if (frame.step > 0) (frame.strainWindow - 1) * frame.step + 1 else 0
        return "$image,${frame.subset},${frame.step},${frame.strainWindow},$vsg,"
    }

    /** RFC-4180 quoting, only when the value needs it (image names rarely do). */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
