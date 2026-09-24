// CSV row writers take many columns by design; return-count guards stay local.
@file:Suppress("LongParameterList", "TooManyFunctions", "ReturnCount")

package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import java.io.File
import java.io.Writer
import java.util.Locale

/**
 * The analysis CSV, shared by the share-sheet export and the cloud upload so the
 * two never drift: one file covering every frame's solved points.
 *
 * The file opens with `#`-comment metadata (session settings, ROI, and
 * per-frame field max/min/mean), then a blank line, then the point-data header
 * and rows. Naive readers that treat every line as data should skip lines
 * starting with `#` (e.g. `pandas.read_csv(..., comment='#')`).
 *
 * Point rows lead with `image` and the eight DIC columns (`x_px`…`znssd`), then
 * the three rigid-body motion columns. Those trail every session: the motion fit
 * is read off the solved field itself, so it is there whatever the frames came
 * from.
 */
object AnalysisCsvWriter {

    /** Session-level fields written into the `#` preamble. */
    data class Metadata(
        val referenceName: String,
        val strainMethod: String,
        val imgW: Int,
        val imgH: Int,
        val roiX: Int,
        val roiY: Int,
        val roiW: Int,
        val roiH: Int,
    )

    /** One frame: its identity columns plus a lazy provider of its decoded field. */
    class Frame(
        val image: String,
        val subset: Int,
        val step: Int,
        val strainWindow: Int,
        val data: () -> FloatArray?,
    )

    private const val CSV_VERSION = 1

    // One definition, shared with the row formatter's tests (TD-39): a second
    // copy here let the header and the rows drift apart unnoticed.
    private const val POINT_HEADER_BASE = DicResult.CSV_POINT_HEADER
    private const val MOTION_SUFFIX_HEADER = "shift_u_px,shift_v_px,shift_rot_deg"
    private const val SWEEP_SETTINGS_HEADER = "subset_px,step_px,strain_window,vsg_px,"

    private val FIELD_STATS = listOf(
        "U" to DicResult.IDX_U,
        "V" to DicResult.IDX_V,
        "Exx" to DicResult.IDX_EXX,
        "Eyy" to DicResult.IDX_EYY,
        "Exy" to DicResult.IDX_EXY,
    )

    fun write(out: File, sweep: Boolean, frames: List<Frame>, metadata: Metadata) {
        open(out, sweep, metadata).use { appender ->
            frames.forEach { frame ->
                frame.data()?.let { data -> appender.appendFieldStats(frame, data) }
            }
            appender.startPointSection()
            frames.forEach { appender.append(it) }
        }
    }

    /**
     * Streaming writer: call [Appender.appendFieldStats] per frame (or let
     * [write] do it), then [Appender.startPointSection], then [Appender.append]
     * for point rows.
     */
    fun open(out: File, sweep: Boolean, metadata: Metadata): Appender {
        val w = out.bufferedWriter(bufferSize = DicResult.CSV_BUFFER_BYTES)
        writeGlobalPreamble(w, metadata)
        w.append("# field_stats\n")
        w.append("# image,subset_px,step_px,strain_window_px,field,max,min,mean,unit\n")
        return Appender(w, sweep, metadata)
    }

    /**
     * The three rigid-body motion values a point row ends with, without the
     * comma that joins them to the DIC columns.
     *
     * Written for every session, because the fit is read off the solved field
     * itself and so exists whatever the frames came from. A frame whose field
     * admits no fit still yields three empty columns rather than a short row:
     * a reader counting columns must not have to guess which value went
     * missing.
     */
    internal fun motionSuffixColumns(fit: RigidBodyFit.Fit?): String {
        if (fit == null) return ",,"
        return String.format(
            Locale.US,
            "%.4f,%.4f,%.5f",
            fit.uPx,
            fit.vPx,
            fit.rotationDeg,
        )
    }

    internal fun pointHeader(sweep: Boolean): String {
        val base = if (sweep) {
            "image,$SWEEP_SETTINGS_HEADER$POINT_HEADER_BASE"
        } else {
            "image,$POINT_HEADER_BASE"
        }
        return "$base,$MOTION_SUFFIX_HEADER"
    }

    private fun writeGlobalPreamble(w: Writer, metadata: Metadata) {
        w.append("# semper_csv_version,$CSV_VERSION\n")
        w.append("# reference,").append(escape(metadata.referenceName)).append('\n')
        w.append("# strain_method,").append(escape(metadata.strainMethod)).append('\n')
        w.append("# image_width,${metadata.imgW}\n")
        w.append("# image_height,${metadata.imgH}\n")
        w.append("# roi_x,${metadata.roiX}\n")
        w.append("# roi_y,${metadata.roiY}\n")
        w.append("# roi_w,${metadata.roiW}\n")
        w.append("# roi_h,${metadata.roiH}\n")
    }

    private fun writeFieldStatsRow(
        w: Writer,
        frame: Frame,
        fieldKey: String,
        max: Float,
        min: Float,
        mean: Float,
        unit: String,
    ) {
        w.append("# ")
            .append(escape(frame.image)).append(',')
            .append(frame.subset.toString()).append(',')
            .append(frame.step.toString()).append(',')
            .append(frame.strainWindow.toString()).append(',')
            .append(fieldKey).append(',')
            .append(ReportBuilder.formatMetric(max)).append(',')
            .append(ReportBuilder.formatMetric(min)).append(',')
            .append(ReportBuilder.formatMetric(mean)).append(',')
            .append(unit)
            .append('\n')
    }

    /** One open CSV file; call [appendFieldStats] / [append] then [close]. */
    class Appender internal constructor(
        private val writer: Writer,
        private val sweep: Boolean,
        private val metadata: Metadata,
    ) : AutoCloseable {
        private val row = StringBuffer(DicResult.CSV_ROW_CAPACITY)
        private val formatter = DicResult.CsvPointFormatter()
        private var pointSectionStarted = false

        fun appendFieldStats(frame: Frame, data: FloatArray) {
            FIELD_STATS.forEach { (fieldKey, dataIndex) ->
                val stats = DicResult.fieldStats(data, dataIndex) ?: return@forEach
                val unit = if (DicResult.isStrainFieldIndex(dataIndex)) "mε" else "px"
                writeFieldStatsRow(
                    writer,
                    frame,
                    fieldKey,
                    stats[0],
                    stats[1],
                    stats[2],
                    unit,
                )
            }
        }

        fun startPointSection() {
            if (pointSectionStarted) return
            writer.append('\n')
            writer.append(pointHeader(sweep)).append('\n')
            pointSectionStarted = true
        }

        fun append(frame: Frame) {
            startPointSection()
            writeFrame(
                writer,
                frame,
                prefix(frame, sweep),
                row,
                formatter,
            )
        }

        override fun close() {
            writer.close()
        }
    }

    /** Appends one frame's solved points, each row led by [prefix]. */
    private fun writeFrame(
        w: Writer,
        frame: Frame,
        prefix: String,
        row: StringBuffer,
        formatter: DicResult.CsvPointFormatter,
    ) {
        val data = frame.data() ?: return
        val suffix = motionSuffixColumns(RigidBodyFit.fit(data))
        var i = 0
        while (i < data.size) {
            if (DicResult.isSolvedPoint(data[i + DicResult.IDX_ZNSSD])) {
                row.setLength(0)
                row.append(prefix)
                formatter.appendPoint(row, data, i)
                row.append(',')
                row.append(suffix)
                row.append('\n')
                w.append(row)
            }
            i += DicResult.STRIDE
        }
    }

    /** The constant leading columns for one frame (image name or sweep settings). */
    private fun prefix(frame: Frame, sweep: Boolean): String {
        val image = escape(frame.image)
        if (!sweep) return "$image,"
        return "$image,${frame.subset},${frame.step},${frame.strainWindow},${frame.strainWindow},"
    }

    /** RFC-4180 quoting, only when the value needs it (image names rarely do). */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
