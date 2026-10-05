// Page layout: literal offsets and sizes are the page's geometry, as in PdfReportGenerator.
@file:Suppress("MagicNumber")

package com.sempermechanics.semper.report

import com.sempermechanics.semper.data.mechanical.TypedLoads
import java.util.Locale

/**
 * The mechanical test's pages of [PdfReportGenerator]'s report
 * (material_testing): the cover's "Mechanical Test" block and the closing
 * stress–strain page(s).
 */
internal object PdfMechanicalPages {

    /** Height of the stress–strain plot block; the table starts under it. */
    private const val STRESS_STRAIN_PLOT_HEIGHT = 1500f

    /** Table rows per page, at [PdfLayoutEngine.drawTable]'s row pitch. */
    private const val STRESS_STRAIN_ROWS_FIRST_PAGE = 14
    private const val STRESS_STRAIN_ROWS_PER_PAGE = 34
    private val STRESS_STRAIN_TABLE_WEIGHTS = listOf(0.31f, 0.23f, 0.23f, 0.23f)
    private val DEFLECTION_TABLE_WEIGHTS = listOf(0.24f, 0.19f, 0.19f, 0.19f, 0.19f)

    /** The cover's "Mechanical Test" block, only on a typed session. */
    fun drawCoverBlock(layout: PdfLayoutEngine, m: MechanicalCover) {
        layout.drawSectionHeader("Mechanical Test")
        layout.drawKeyValue("Test Type:", m.label)
        layout.drawDimensions(m.model)
        layout.drawKeyValue("Strain:", m.model.strainName)
        m.loadN?.let { loadN ->
            if (m.model is StressStrain.Model.Flexural) {
                layout.drawKeyValue("Load:", "%.2f kg".format(Locale.US, TypedLoads.kg(loadN)))
            } else {
                layout.drawKeyValue("Machine Load:", "%.2f N".format(Locale.US, loadN))
            }
        }
        m.stressMPa?.let {
            layout.drawKeyValue("${m.model.stressName}:", "%.3f MPa".format(Locale.US, it))
        }
        layout.advanceY(40f)
    }

    /**
     * Stress–strain curve then the table behind it, after the last frame and
     * before telemetry. The plot shares its first page with the opening rows;
     * the rest of the table is chunked over following pages.
     */
    fun drawStressStrainPages(layout: PdfLayoutEngine, page: PdfReportGenerator.StressStrainPage) {
        val curve = page.curve
        layout.newPage()
        layout.drawTitle(curve.model.curveTitle)
        layout.drawDimensions(curve.model)
        layout.drawKeyValue("Strain:", "${curve.model.strainName} ${curve.model.strainBasis}")
        curve.peakStress?.let {
            val where = if (it.onCurve) "" else ", off the curve (no strain)"
            val value = "%.3f MPa at frame %d%s".format(Locale.US, it.stressMPa, it.frame + 1, where)
            layout.drawKeyValue("Peak Stress:", value)
        }
        page.modulus?.let { layout.drawKeyValue("Modulus E (approx.):", LabReport.modulusSummary(it)) }
        page.modulus?.let { YieldStrength.offset(curve, it) }?.let {
            val value = "%.3f MPa at %.3f mε (0.2%% offset)".format(Locale.US, it.stressMPa, it.strainMilli)
            layout.drawKeyValue("Yield Strength Rp0.2:", value)
        }
        layout.advanceY(20f)
        page.plot?.let {
            layout.drawDiagnosticBlock(
                curve.model.plotTitle,
                it,
                STRESS_STRAIN_PLOT_HEIGHT,
            )
        }

        val deflection = curve.model.plotsLoadDeflection
        val rows = curve.points.map {
            listOfNotNull(
                "Frame ${it.frame + 1}",
                "%.2f".format(Locale.US, if (deflection) TypedLoads.kg(it.loadN) else it.loadN),
                "%.3f".format(Locale.US, it.stressMPa),
                "%.3f".format(Locale.US, it.strainMilli),
                if (deflection) it.deflectionMm?.let { d -> "%.4f".format(Locale.US, d) } ?: "—" else null,
            )
        }
        val headers = listOfNotNull(
            "Frame",
            if (deflection) "Load (kg)" else "Load (N)",
            "Stress (MPa)",
            "Strain (mε)",
            "Deflection (mm)".takeIf { deflection },
        )
        val weights = if (deflection) DEFLECTION_TABLE_WEIGHTS else STRESS_STRAIN_TABLE_WEIGHTS
        val first = rows.take(STRESS_STRAIN_ROWS_FIRST_PAGE)
        layout.drawSectionHeader("Per-frame values")
        layout.drawTable(headers, first, weights)
        rows.drop(STRESS_STRAIN_ROWS_FIRST_PAGE).chunked(STRESS_STRAIN_ROWS_PER_PAGE).forEach { chunk ->
            layout.newPage()
            layout.drawTitle("Stress–Strain Curve (continued)")
            layout.drawTable(headers, chunk, weights)
        }
    }
}

/**
 * One key-value per entered dimension of a test's stress model. A file-level
 * extension, beside the two pages that print it.
 */
private fun PdfLayoutEngine.drawDimensions(model: StressStrain.Model) {
    model.dimensions.forEach { (dimension, value) ->
        if (value > 0f) {
            drawKeyValue("${dimension.label}:", "%.3f %s".format(Locale.US, value, dimension.unit))
        }
    }
}
