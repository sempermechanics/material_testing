package com.indicvision.semper.report

import com.indicvision.semper.report.LabReportFormat.frameCell
import com.indicvision.semper.report.LabReportFormat.num
import com.indicvision.semper.report.LabReportFormat.sci
import java.util.Locale

/**
 * The student lab report: the journal write-up a first-semester lab asks for,
 * section by section in the order the handwritten reports use, filled with
 * this session's numbers. Fixed wording is in [LabReportText]; values the app
 * cannot know (a final diameter measured after fracture, a gauge length in
 * mm) keep their place as blank lines for the student to fill in by hand.
 *
 * Pure — a [Document] is an ordered list of [Block]s that [LabReportPdf]
 * draws and the unit tests read. Graph blocks carry their series; the caller
 * renders them (the same off-screen plot the full report uses) and hands the
 * bitmaps to the PDF.
 */
object LabReport {

    data class Document(val experiment: String, val title: String, val blocks: List<Block>) {
        /** Section headings in order — what the layout tests compare against the originals. */
        val headings: List<String> get() = blocks.filterIsInstance<Block.Heading>().map { it.text }

        val graphs: List<Block.Graph> get() = blocks.filterIsInstance<Block.Graph>()
    }

    sealed class Block {
        /** A section heading, e.g. "Aim". */
        data class Heading(val text: String) : Block()

        data class Paragraph(val text: String) : Block()

        /** Numbered steps, e.g. a procedure (a), (b), … */
        data class Steps(val items: List<String>) : Block()

        /** "Label = value"; a null value is a blank line to fill in by hand. */
        data class Field(val label: String, val value: String?) : Block()

        /** The reference photo, where the original has its setup sketch. */
        data class Figure(val caption: String) : Block()

        /**
         * The observation table. [brackets] label runs of rows in the right
         * margin (Elastic, Plastic, Break point), as the handwritten table does.
         */
        data class Table(
            val headers: List<String>,
            val rows: List<List<String>>,
            val weights: List<Float>,
            val brackets: List<Bracket> = emptyList(),
        ) : Block()

        /** A worked calculation, one line per step. */
        data class Calculation(val lines: List<String>) : Block()

        data class Graph(
            val id: String,
            val title: String,
            val xLabel: String,
            val yLabel: String,
            val series: List<Series>,
            /** Boxed on the plot, e.g. "E = 194.0 GPa". */
            val annotation: String?,
        ) : Block()

        /** Ruled blank lines, e.g. for the student's own conclusion. */
        data class RuledLines(val count: Int) : Block()
    }

    /** Rows [first]..[last] (0-based, inclusive) share the margin label [label]. */
    data class Bracket(val first: Int, val last: Int, val label: String)

    data class Series(val points: List<Pair<Float, Float>>, val isFit: Boolean)

    const val GRAPH_ELASTIC = "elastic"
    const val GRAPH_FULL = "full"

    /**
     * The document for a session's curve, or null when there is nothing to
     * report: an empty curve, or bending without the load point tapped (no
     * deflection, so none of the lab's numbers).
     */
    fun of(curve: StressStrain.Curve, modulus: ElasticModulus.Fit?): Document? {
        if (curve.isEmpty) return null
        return when (val model = curve.model) {
            is StressStrain.Model.Axial -> tensile(curve, model, modulus)
            is StressStrain.Model.Flexural ->
                BeamDeflection.summarize(curve)?.let { LabReportBending.document(model, it) }
        }
    }

    // ── tensile: Experiment "Measurement of tensile strains and modulus of elasticity" ──

    private fun tensile(
        curve: StressStrain.Curve,
        model: StressStrain.Model.Axial,
        modulus: ElasticModulus.Fit?,
    ): Document {
        val t = LabReportText.Tensile
        val first = curve.points.first()
        return Document(
            experiment = LabReportText.EXPERIMENT,
            title = t.TITLE,
            blocks = buildList {
                add(Block.Heading(LabReportText.AIM))
                add(Block.Paragraph(t.AIM))
                add(Block.Heading(t.MATERIALS_HEADING))
                add(Block.Paragraph(t.MATERIALS))
                add(Block.Heading(LabReportText.THEORY))
                t.THEORY.forEach { add(Block.Paragraph(it)) }
                add(Block.Figure(t.FIGURE))
                add(Block.Heading(LabReportText.OBSERVATIONS))
                add(Block.Field(t.TOTAL_LENGTH, null))
                add(Block.Field(t.GAUGE_LENGTH, null))
                add(Block.Field(t.DIAMETER, null))
                add(Block.Field(t.AREA, num(model.areaMm2, 2)))
                add(Block.Field(t.STRAIN_BY, t.strainBy(model.strainName)))
                curve.gauge?.let { add(Block.Field(t.dicGauge(axisName(model)), t.dicGaugeValue(num(it.lengthPx, 0)))) }
                add(Block.Field(t.FINAL_DIAMETER, null))
                add(Block.Field(t.FINAL_GAUGE_LENGTH, null))
                add(tensileTable(curve, modulus))
                add(Block.Heading(LabReportText.CALCULATION))
                add(
                    Block.Calculation(
                        listOf(
                            t.stressLine(
                                num(first.loadN / NEWTONS_PER_KN, LOAD_DECIMALS),
                                num(model.areaMm2, 2),
                                num(first.stressMPa, 2),
                            ),
                            t.strainLine(sci(first.strainMilli / MILLI)),
                        ) + extensionLine(curve, first),
                    ),
                )
                addAll(tensileGraphs(curve, modulus))
                add(Block.Heading(LabReportText.RESULTS))
                add(Block.Field(t.RESULT_E, modulus?.let { modulusSummary(it) } ?: t.NO_FIT))
                curve.peak?.let { add(Block.Field(t.RESULT_PEAK, "${num(it.stressMPa, 2)} MPa")) }
                add(Block.Paragraph(LabReportText.APPROXIMATE_NOTE))
                add(Block.Heading(LabReportText.CONCLUSIONS))
                add(Block.RuledLines(CONCLUSION_LINES))
            },
        )
    }

    private fun axisName(model: StressStrain.Model.Axial): String = if (model.axisX) "x" else "y"

    private fun extensionLine(curve: StressStrain.Curve, first: StressStrain.Point): List<String> {
        val gauge = curve.gauge
        val extension = first.extensionPx
        return if (gauge == null || extension == null) {
            emptyList()
        } else {
            listOf(LabReportText.Tensile.extensionLine(num(extension, EXTENSION_DECIMALS), num(gauge.lengthPx, 0)))
        }
    }

    private fun tensileTable(curve: StressStrain.Curve, modulus: ElasticModulus.Fit?): Block.Table {
        val t = LabReportText.Tensile
        val rows = curve.points.mapIndexed { i, p ->
            listOf(
                "${i + 1}",
                frameCell(p.frame),
                num(p.loadN / NEWTONS_PER_KN, LOAD_DECIMALS),
                p.extensionPx?.let { num(it, EXTENSION_DECIMALS) } ?: BLANK_CELL,
                num(p.stressMPa, 2),
                sci(p.strainMilli / MILLI),
            )
        }
        return Block.Table(
            headers = t.TABLE_HEADERS,
            rows = rows,
            weights = t.TABLE_WEIGHTS,
            brackets = tensileBrackets(curve, modulus),
        )
    }

    /**
     * Elastic for the fitted run, Plastic from there to the peak, Break point
     * after it — the regions the handwritten table marks in its margin.
     */
    internal fun tensileBrackets(curve: StressStrain.Curve, modulus: ElasticModulus.Fit?): List<Bracket> {
        val points = curve.points
        val peakRow = curve.peak?.let { points.indexOf(it) } ?: return emptyList()
        val elasticEnd = modulus?.let { fit -> points.indexOfLast { fit.covers(it.frame) } } ?: -1
        return buildList {
            if (elasticEnd >= 0) add(Bracket(0, elasticEnd, LabReportText.Tensile.ELASTIC))
            if (peakRow > elasticEnd) add(Bracket(elasticEnd + 1, peakRow, LabReportText.Tensile.PLASTIC))
            if (peakRow < points.lastIndex) add(Bracket(peakRow + 1, points.lastIndex, LabReportText.Tensile.BREAK))
        }
    }

    private fun tensileGraphs(curve: StressStrain.Curve, modulus: ElasticModulus.Fit?): List<Block> {
        val t = LabReportText.Tensile
        val fitSeries = modulus?.let { fitLine(curve, it) }
        val annotation = modulus?.let { "E = ${num(it.modulusGPa, 1)} GPa" }
        return buildList {
            if (modulus != null && fitSeries != null) {
                val elastic = curve.points.filter { modulus.covers(it.frame) }.map { it.strainMilli to it.stressMPa }
                add(
                    Block.Graph(
                        GRAPH_ELASTIC,
                        t.GRAPH_ELASTIC,
                        LabReportText.AXIS_STRAIN,
                        LabReportText.AXIS_STRESS,
                        listOf(Series(elastic, isFit = false), fitSeries),
                        annotation,
                    ),
                )
            }
            add(
                Block.Graph(
                    GRAPH_FULL,
                    t.GRAPH_FULL,
                    LabReportText.AXIS_STRAIN,
                    LabReportText.AXIS_STRESS,
                    listOfNotNull(Series(curve.plotPoints(), isFit = false), fitSeries),
                    annotation,
                ),
            )
        }
    }

    private fun fitLine(curve: StressStrain.Curve, fit: ElasticModulus.Fit): Series? {
        val fitted = curve.points.filter { fit.covers(it.frame) }
        if (fitted.isEmpty()) return null
        val from = fitted.minOf { it.strainMilli }
        val to = fitted.maxOf { it.strainMilli }
        return Series(listOf(from to fit.stressAt(from), to to fit.stressAt(to)), isFit = true)
    }

    /** "194.0 GPa (frames 1–12, R² 0.9989)" — the one wording for E everywhere. */
    fun modulusSummary(fit: ElasticModulus.Fit): String =
        "${num(fit.modulusGPa, 1)} GPa (frames ${fit.firstFrame + 1}–${fit.lastFrame + 1}, " +
            "R² ${String.format(Locale.US, "%.4f", fit.r2)})"

    private const val NEWTONS_PER_KN = 1000f
    private const val MILLI = 1000f
    private const val CONCLUSION_LINES = 6
    private const val LOAD_DECIMALS = 3
    private const val EXTENSION_DECIMALS = 2
    const val BLANK_CELL = "—"
}
