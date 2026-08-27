package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.CaptureNoiseFloor
import java.io.File
import java.io.Writer
import java.util.Locale

/**
 * The analysis CSV, shared by the share-sheet export and the cloud upload so the
 * two never drift: one file covering every frame's solved points. A sweep leads
 * each row with its settings (subset/step/strain window/VSG); an ordinary
 * analysis leads with the image name. The point columns and all number
 * formatting come from [DicResult.CsvPointFormatter].
 *
 * Every row also carries the noise floor the frames were captured at. Repeating
 * three constant columns across ~90k rows is deliberate: the alternative is a
 * `#` preamble, which every naive reader — a spreadsheet, a plain `read_csv` —
 * silently mistakes for data or a header. A reader who filters this file down to
 * the points they care about must not be able to lose the one number that says
 * which of those strain values are real, so it travels on the row.
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

    /**
     * The floor columns, present whether or not a floor was measured — a header
     * that changes shape between exports is a worse problem than three empty
     * fields, because it breaks any script written against a previous file.
     */
    private const val FLOOR_HEADER = "noise_floor_ue,noise_floor_vsg_px,noise_floor_exceeded"

    /**
     * What the whole scene did between the two frames, per frame.
     *
     * Repeated on every row for the same reason the floor columns are: a
     * reader who filters this file down to a handful of interesting points
     * must not lose the number that says whether the frame moved underneath
     * them. See [RigidBodyFit] for why it is reported and not subtracted.
     */
    private const val MOTION_HEADER = "shift_u_px,shift_v_px,shift_rot_deg,nonrigid_rms_px"

    private val HEADER_SWEEP = "image,subset_px,step_px,strain_window,vsg_px," +
        "$FLOOR_HEADER,$MOTION_HEADER," + DicResult.CSV_POINT_HEADER
    private val HEADER_SINGLE = "image,$FLOOR_HEADER,$MOTION_HEADER," + DicResult.CSV_POINT_HEADER

    fun write(out: File, sweep: Boolean, frames: List<Frame>, floor: CaptureNoiseFloor? = null) {
        open(out, sweep, floor).use { appender ->
            frames.forEach { appender.append(it) }
        }
    }

    /**
     * Streaming writer so a caller that already decoded a frame's `.dat` can
     * append CSV rows without holding every frame in memory (upload staging
     * shares one decode pass with report bake).
     */
    fun open(out: File, sweep: Boolean, floor: CaptureNoiseFloor? = null): Appender {
        val w = out.bufferedWriter(bufferSize = DicResult.CSV_BUFFER_BYTES)
        w.append(if (sweep) HEADER_SWEEP else HEADER_SINGLE).append('\n')
        return Appender(w, sweep, floorColumns(floor))
    }

    /**
     * The three floor fields as one ready-to-append fragment.
     *
     * An unmeasured floor writes empty fields rather than zeros: an imported
     * analysis has no burst behind it, and a `0` there would read as a perfect
     * camera to anyone who did not know to look for the distinction.
     */
    internal fun floorColumns(floor: CaptureNoiseFloor?): String {
        if (floor == null) return ",,,"
        return String.format(
            Locale.US,
            "%.0f,%.0f,%d,",
            floor.microstrain,
            floor.vsgPx,
            if (floor.exceeded) 1 else 0,
        )
    }

    /** One open CSV file; call [append] per frame then [close]. */
    class Appender internal constructor(
        private val writer: Writer,
        private val sweep: Boolean,
        private val floorColumns: String,
    ) : AutoCloseable {
        private val row = StringBuffer(DicResult.CSV_ROW_CAPACITY)
        private val formatter = DicResult.CsvPointFormatter()

        fun append(frame: Frame) {
            writeFrame(writer, frame, prefix(frame, sweep) + floorColumns, row, formatter)
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
        val fullPrefix = prefix + motionColumns(RigidBodyFit.fit(data))
        var i = 0
        while (i < data.size) {
            if (DicResult.isSolvedPoint(data[i + DicResult.IDX_ZNSSD])) {
                row.setLength(0)
                row.append(fullPrefix)
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
        // The strain window is a diameter in pixels, so it *is* the gauge
        // length; see VsgStudy.vsgFor for the measurements that settled that.
        // Kept as its own column because a reader should not have to know.
        return "$image,${frame.subset},${frame.step},${frame.strainWindow},${frame.strainWindow},"
    }

    /**
     * The four scene-motion columns, or four empty fields when too few points
     * converged to fit them. Empty rather than zero: zero movement is a claim,
     * and this is the absence of one.
     */
    internal fun motionColumns(fit: RigidBodyFit.Fit?): String {
        if (fit == null) return ",,,,"
        return String.format(
            Locale.US,
            "%.4f,%.4f,%.5f,%.4f,",
            fit.uPx,
            fit.vPx,
            fit.rotationDeg,
            fit.residualPx,
        )
    }

    /** RFC-4180 quoting, only when the value needs it (image names rarely do). */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
