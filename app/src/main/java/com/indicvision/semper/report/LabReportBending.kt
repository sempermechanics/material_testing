package com.indicvision.semper.report

import com.indicvision.semper.report.LabReport.Block
import com.indicvision.semper.report.LabReport.Document
import com.indicvision.semper.report.LabReport.Series
import com.indicvision.semper.report.LabReportFormat.frameCell
import com.indicvision.semper.report.LabReportFormat.num
import com.indicvision.semper.report.LabReportFormat.sci
import java.util.Locale

/**
 * The bending lab report — "Measurement of bending moment and deflection of
 * beam" — in the handwritten journal's order: aim, setup, theory, procedure,
 * the beam figure, observation, the row-1 calculation, the observation table,
 * results and the load–deflection graph. The dial gauge's column is the DIC
 * deflection at the tapped load point ([BeamDeflection]). The original's
 * "E provided / error" lines are left out: the app quotes no textbook value.
 */
internal object LabReportBending {

    const val GRAPH_LOAD_DEFLECTION = "load_deflection"

    fun document(model: StressStrain.Model.Flexural, summary: BeamDeflection.Summary): Document {
        val b = LabReportText.Bending
        return Document(
            experiment = LabReportText.EXPERIMENT,
            title = b.TITLE,
            blocks = buildList {
                add(Block.Heading(LabReportText.AIM))
                add(Block.Paragraph(b.AIM))
                add(Block.Heading(b.SETUP_HEADING))
                add(Block.Paragraph(b.SETUP))
                add(Block.Heading(LabReportText.THEORY))
                add(Block.Paragraph(b.THEORY))
                add(Block.Heading(b.PROCEDURE_HEADING))
                add(Block.Steps(b.PROCEDURE))
                add(Block.Figure(b.FIGURE))
                add(Block.Heading(LabReportText.OBSERVATIONS))
                add(Block.Field(b.SPAN, num(model.spanMm, 1)))
                add(Block.Field(b.WIDTH, num(model.widthMm, 1)))
                add(Block.Field(b.THICKNESS, num(model.thicknessMm, 2)))
                add(Block.Field(b.NO_LOAD, b.NO_LOAD_VALUE))
                add(Block.Field(b.SCALE, String.format(Locale.US, "%.4f", summary.mmPerPx)))
                add(Block.Heading(LabReportText.CALCULATION))
                add(Block.Calculation(calculation(model, summary)))
                add(table(summary))
                add(Block.Heading(LabReportText.RESULTS))
                add(Block.Field(b.RESULT_MEAN, summary.meanModulusGPa?.let { "${num(it, 2)} GPa" } ?: b.NONE))
                add(Block.Field(b.RESULT_GRAPH, summary.slopeModulusGPa?.let { "${num(it, 1)} GPa" } ?: b.NONE))
                add(Block.Paragraph(b.APPROXIMATE_NOTE))
                add(graph(summary))
            },
        )
    }

    /** Row 1 worked through in SI units, line by line, as the report does. */
    internal fun calculation(model: StressStrain.Model.Flexural, summary: BeamDeflection.Summary): List<String> {
        val b = LabReportText.Bending
        val first = summary.loadSteps.firstOrNull() ?: summary.steps.first()
        val spanM = model.spanMm / MM_PER_M
        val momentNm = BeamDeflection.momentNmm(first.loadN, model.spanMm) / MM_PER_M
        val yM = model.thicknessMm / 2f / MM_PER_M
        val inertiaM4 = summary.secondMomentMm4 / MM4_PER_M4
        return listOf(
            b.SIGMA_FORMULA,
            b.momentLine(num(first.loadN, 2), num(spanM, SPAN_M_DECIMALS), num(momentNm, 2)),
            b.yLine(sci(yM)),
            b.inertiaLine(sci(inertiaM4)),
            b.sigmaLine(num(first.stressMPa, STRESS_DECIMALS)),
            first.modulusGPa?.let {
                b.modulusLine(num(first.loadN, 2), num(first.deflectionMm, DEFLECTION_DECIMALS), num(it, 2))
            } ?: b.NO_STEP_E,
        )
    }

    private fun table(summary: BeamDeflection.Summary): Block.Table {
        val b = LabReportText.Bending
        val rows = summary.loadSteps.mapIndexed { i, step ->
            listOf(
                "${i + 1}",
                frameCell(step.frame, step.lastFrame),
                num(step.loadN, 2),
                num(step.deflectionMm, DEFLECTION_DECIMALS),
                num(step.stressMPa, STRESS_DECIMALS),
                step.modulusGPa?.let { num(it, 2) } ?: LabReport.BLANK_CELL,
            )
        }
        return Block.Table(b.TABLE_HEADERS, rows, b.TABLE_WEIGHTS)
    }

    private fun graph(summary: BeamDeflection.Summary): Block.Graph {
        val b = LabReportText.Bending
        val steps = summary.loadSteps
        val points = listOf(0f to 0f) + steps.map { it.deflectionMm to it.loadN }
        val line = summary.slope
        val fit = line?.let {
            val from = steps.minOf { s -> s.deflectionMm }.coerceAtMost(0f).toDouble()
            val to = steps.maxOf { s -> s.deflectionMm }.toDouble()
            Series(listOf(from.toFloat() to it.at(from).toFloat(), to.toFloat() to it.at(to).toFloat()), isFit = true)
        }
        val annotation = if (line != null && summary.slopeModulusGPa != null) {
            "Slope = ${num(line.slope.toFloat(), 2)} N/mm · E = ${num(summary.slopeModulusGPa, 1)} GPa"
        } else {
            null
        }
        return Block.Graph(
            GRAPH_LOAD_DEFLECTION,
            b.GRAPH,
            b.AXIS_DEFLECTION,
            b.AXIS_LOAD,
            listOfNotNull(Series(points, isFit = false), fit),
            annotation,
        )
    }

    private const val MM_PER_M = 1000f
    private const val SPAN_M_DECIMALS = 3
    private const val DEFLECTION_DECIMALS = 3
    private const val STRESS_DECIMALS = 3
    private const val MM4_PER_M4 = 1e12f
}
