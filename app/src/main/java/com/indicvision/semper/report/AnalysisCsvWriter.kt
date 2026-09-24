// CSV row writers take many columns by design; return-count guards stay local.
@file:Suppress("LongParameterList", "TooManyFunctions", "ReturnCount")

package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.ui.analysis.VsgStudy
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
 * the three rigid-body motion columns, then the frame's machine load and
 * engineering stress. All six trail every session — the motion fit is read off
 * the solved field itself, and the two mechanical columns are simply empty for
 * a session without a load log — so a reader never has to guess which value
 * went missing from a short row.
 *
 * A typed session with loads may end with a `# mechanical_results` trailer
 * after the last point row: the results the lab report quotes (Young's
 * modulus for tensile). It is a trailer because the upload bundler writes the
 * file in one pass, so only the end of the file has seen every frame.
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
        /** Wire name of the mechanical test, or blank for a plain DIC session. */
        val testType: String = "",
        val crossSectionMm2: Float = 0f,
        val loadAxisX: Boolean = true,
        val geometry: SpecimenGeometry = SpecimenGeometry.NONE,
    ) {
        /** How this session's loads become the `stress_MPa` column. */
        val stressModel: StressStrain.Model
            get() = StressStrain.Model.of(testType, crossSectionMm2, loadAxisX, geometry)
    }

    /** One frame: its identity columns plus a lazy provider of its decoded field. */
    class Frame(
        val image: String,
        val subset: Int,
        val step: Int,
        val strainWindow: Int,
        val data: () -> FloatArray?,
        /** The machine load logged for this frame, or null without a load log. */
        val loadN: Float? = null,
    )

    /**
     * 2: `load_N,stress_MPa` trail every point row; typed sessions add `# test_type…`.
     * A bending session adds `# stress_model` and its dimensions to the
     * preamble without a version bump: `stress_MPa` is the model's stress
     * either way, and a reader that ignores unknown `#` lines is unaffected.
     * The `# mechanical_results` trailer is added on the same terms.
     */
    private const val CSV_VERSION = 2

    // One definition, shared with the row formatter's tests (TD-39): a second
    // copy here let the header and the rows drift apart unnoticed.
    private const val POINT_HEADER_BASE = DicResult.CSV_POINT_HEADER
    private const val MOTION_SUFFIX_HEADER = "shift_u_px,shift_v_px,shift_rot_deg"
    private const val MECHANICAL_SUFFIX_HEADER = "load_N,stress_MPa"
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
     * Streaming writer. Two ways to drive it, both giving [write]'s layout:
     *
     *  - Stats first: [Appender.appendFieldStats] for every frame, then
     *    [Appender.startPointSection], then [Appender.append] per frame. Point
     *    rows go straight to [out].
     *  - One pass: [Appender.appendFieldStats] and [Appender.append] per frame,
     *    so each frame is decoded once (the upload bundle). Stats rows are held
     *    in memory and point rows staged in a sibling `.points.tmp` file;
     *    [Appender.close] writes the stats, then the point section, and deletes
     *    the staging file.
     */
    fun open(out: File, sweep: Boolean, metadata: Metadata): Appender {
        val w = out.bufferedWriter(bufferSize = DicResult.CSV_BUFFER_BYTES)
        writeGlobalPreamble(w, metadata)
        w.append("# field_stats\n")
        w.append("# image,subset_px,step_px,strain_window_px,field,max,min,mean,unit\n")
        return Appender(w, File(out.path + POINTS_STAGING_SUFFIX), sweep, metadata)
    }

    private const val POINTS_STAGING_SUFFIX = ".points.tmp"

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

    /**
     * The frame's load and stress, without the leading comma. Empty cells when
     * the session has no load log, so the column count never changes.
     */
    internal fun mechanicalSuffixColumns(loadN: Float?, model: StressStrain.Model): String {
        if (loadN == null) return ","
        val stress = model.stressMPa(loadN)
        val stressText = if (stress.isNaN()) "" else String.format(Locale.US, "%.4f", stress)
        return String.format(Locale.US, "%.3f,", loadN) + stressText
    }

    internal fun pointHeader(sweep: Boolean): String {
        val base = if (sweep) {
            "image,$SWEEP_SETTINGS_HEADER$POINT_HEADER_BASE"
        } else {
            "image,$POINT_HEADER_BASE"
        }
        return "$base,$MOTION_SUFFIX_HEADER,$MECHANICAL_SUFFIX_HEADER"
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
        if (metadata.testType.isNotBlank()) {
            val model = metadata.stressModel
            w.append("# test_type,").append(escape(metadata.testType)).append('\n')
            w.append("# stress_model,").append(model.wireName).append('\n')
            // Cross-section is written even at 0, as the first version-2 files
            // did; the bending dimensions only once entered.
            model.dimensions.forEach { (dimension, value) ->
                if (dimension == StressStrain.Dimension.CROSS_SECTION || value > 0f) {
                    w.append("# ").append(dimension.csvKey).append(',')
                        .append(String.format(Locale.US, "%.4f", value)).append('\n')
                }
            }
            w.append("# load_axis,").append(if (metadata.loadAxisX) "x" else "y").append('\n')
            w.append("# load_unit,N\n")
            (model as? StressStrain.Model.Flexural)?.probe?.let { writeLoadPoint(w, it) }
        }
    }

    /** Bending's tapped edges (reference px) and the scale they give. */
    private fun writeLoadPoint(w: Writer, probe: BeamDeflection.Probe) {
        val taps = probe.taps
        w.append("# load_point_top_px,").append(px(taps.topX)).append(',').append(px(taps.topY)).append('\n')
        w.append("# load_point_bottom_px,").append(px(taps.bottomX)).append(',').append(px(taps.bottomY)).append('\n')
        w.append("# mm_per_px,").append(String.format(Locale.US, "%.6f", probe.mmPerPx)).append('\n')
    }

    private fun px(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun writeFieldStatsRow(
        w: Appendable,
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

    /**
     * One open CSV file; see [open] for the two call orders. Every  stats row
     * lands before the blank line and the point header, whichever order is used,
     * and the  trailer after the last point row.
     */
    class Appender internal constructor(
        private val writer: Writer,
        private val stagingFile: File,
        private val sweep: Boolean,
        private val metadata: Metadata,
    ) : AutoCloseable {
        private val row = StringBuffer(DicResult.CSV_ROW_CAPACITY)
        private val formatter = DicResult.CsvPointFormatter()

        // Stats rows wait here until the point section starts: five short rows
        // a frame, never the point data.
        private val pendingStats = StringBuilder()
        private var pointSectionStarted = false

        // Point rows appended before the section started (the one-pass order).
        private var staged: Writer? = null

        /** 0-based index of the next [append]ed frame — frames arrive in order. */
        private var frameIndex = 0

        /** The curve's points, collected as frames stream past; null when there is no trailer. */
        private val mechanical: MutableList<StressStrain.Point>? =
            if (!sweep && metadata.testType.isNotBlank()) mutableListOf() else null

        fun appendFieldStats(frame: Frame, data: FloatArray) {
            check(!pointSectionStarted) { "field stats after the point section started" }
            FIELD_STATS.forEach { (fieldKey, dataIndex) ->
                val stats = DicResult.fieldStats(data, dataIndex) ?: return@forEach
                val unit = if (DicResult.isStrainFieldIndex(dataIndex)) "mε" else "px"
                writeFieldStatsRow(
                    pendingStats,
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
            writer.append(pendingStats)
            pendingStats.setLength(0)
            writer.append('\n')
            writer.append(pointHeader(sweep)).append('\n')
            pointSectionStarted = true
            staged?.let { points ->
                points.close()
                staged = null
                stagingFile.bufferedReader().use { it.copyTo(writer, DicResult.CSV_BUFFER_BYTES) }
                stagingFile.delete()
            }
        }

        fun append(frame: Frame) {
            val index = frameIndex++
            val data = frame.data() ?: return
            writeFrame(
                if (pointSectionStarted) writer else stagedWriter(),
                data,
                prefix(frame, sweep),
                row,
                formatter,
                mechanicalSuffixColumns(frame.loadN, metadata.stressModel),
            )
            collect(index, frame.loadN, data)
        }

        private fun collect(index: Int, loadN: Float?, data: FloatArray) {
            val points = mechanical ?: return
            if (loadN == null) return
            val model = metadata.stressModel
            val strain = model.strainMilli(data) ?: return
            points += StressStrain.Point(index, loadN, model.stressMPa(loadN), strain, model.deflectionMm(data))
        }

        private fun stagedWriter(): Writer =
            staged ?: stagingFile.bufferedWriter(bufferSize = DicResult.CSV_BUFFER_BYTES).also { staged = it }

        override fun close() {
            try {
                startPointSection()
                val points = mechanical
                if (!points.isNullOrEmpty()) {
                    writeMechanicalResults(writer, StressStrain.Curve(metadata.stressModel, frameIndex, points))
                }
            } finally {
                staged?.close()
                stagingFile.delete()
                writer.close()
            }
        }
    }

    /**
     * The trailer: what the lab report's Results section quotes. Tensile gets
     * Young's modulus from [ElasticModulus]; an empty value means no straight
     * run was found. Bending with a load point gets the lab's observation
     * table and both of its E values ([BeamDeflection]). 1-based frame
     * numbers, like the viewer and the report.
     */
    internal fun writeMechanicalResults(w: Writer, curve: StressStrain.Curve) {
        when {
            curve.model is StressStrain.Model.Axial -> writeTensileResults(w, curve)
            curve.model.plotsLoadDeflection -> BeamDeflection.summarize(curve)?.let { writeBendingResults(w, it) }
        }
    }

    private fun writeTensileResults(w: Writer, curve: StressStrain.Curve) {
        val fit = ElasticModulus.fit(curve)
        w.append("# mechanical_results\n")
        w.append("# elastic_modulus_gpa,")
        if (fit != null) {
            w.append(String.format(Locale.US, "%.4f", fit.modulusGPa)).append('\n')
            w.append("# elastic_fit_frames,${fit.firstFrame + 1},${fit.lastFrame + 1}\n")
            w.append("# elastic_fit_r2,").append(String.format(Locale.US, "%.6f", fit.r2)).append('\n')
        } else {
            w.append('\n')
        }
    }

    private fun writeBendingResults(w: Writer, summary: BeamDeflection.Summary) {
        w.append("# mechanical_results\n")
        w.append("# bending_step,frame,load_N,deflection_mm,flexural_stress_MPa,e_GPa\n")
        summary.steps.forEach { step ->
            w.append("# bending_step,${step.frame + 1},")
                .append(String.format(Locale.US, "%.4f", step.loadN)).append(',')
                .append(String.format(Locale.US, "%.5f", step.deflectionMm)).append(',')
                .append(String.format(Locale.US, "%.4f", step.stressMPa)).append(',')
                .append(gpaOrEmpty(step.modulusGPa)).append('\n')
        }
        w.append("# e_mean_gpa,").append(gpaOrEmpty(summary.meanModulusGPa)).append('\n')
        summary.slope?.let { line ->
            w.append("# load_deflection_slope_N_per_mm,").append(String.format(Locale.US, "%.4f", line.slope))
                .append('\n')
            w.append("# load_deflection_r2,").append(String.format(Locale.US, "%.6f", line.r2)).append('\n')
        }
        w.append("# e_slope_gpa,").append(gpaOrEmpty(summary.slopeModulusGPa)).append('\n')
    }

    private fun gpaOrEmpty(value: Float?): String = value?.let { String.format(Locale.US, "%.4f", it) }.orEmpty()

    /** Appends one frame's solved points, each row led by [prefix]. */
    private fun writeFrame(
        w: Writer,
        data: FloatArray,
        prefix: String,
        row: StringBuffer,
        formatter: DicResult.CsvPointFormatter,
        mechanical: String,
    ) {
        val suffix = motionSuffixColumns(RigidBodyFit.fit(data)) + "," + mechanical
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

    /**
     * The constant leading columns for one frame (image name or sweep settings).
     * A sweep's `strain_window` is in data points, empty for a sweep stored
     * before the window was counted in points; `vsg_px` is the stored window.
     */
    private fun prefix(frame: Frame, sweep: Boolean): String {
        val image = escape(frame.image)
        if (!sweep) return "$image,"
        val points = VsgStudy.windowPointsFor(frame.strainWindow, frame.step)?.toString().orEmpty()
        return "$image,${frame.subset},${frame.step},$points,${frame.strainWindow},"
    }

    /** RFC-4180 quoting, only when the value needs it (image names rarely do). */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
