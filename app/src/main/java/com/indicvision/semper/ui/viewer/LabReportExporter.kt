package com.indicvision.semper.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.indicvision.semper.data.TestType
import com.indicvision.semper.report.LabReport
import com.indicvision.semper.report.LabReportPdf
import com.indicvision.semper.report.StressStrain
import com.indicvision.semper.ui.analysis.VsgPlotView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The share sheet's "Lab report (PDF)": the student write-up [LabReport]
 * lays out, with its graphs drawn by the same off-screen [VsgPlotView] the
 * full report uses and the reference photo as the setup figure.
 */
class LabReportExporter(private val context: Context) {

    /**
     * Writes the report for [curve] to [dest]. [reference] is the display
     * reference photo (null leaves the figure out); [roi] is in image pixels
     * of an [imageWidth]×[imageHeight] frame.
     */
    suspend fun write(
        curve: StressStrain.Curve,
        reference: Bitmap?,
        imageSize: Pair<Int, Int>,
        roi: RectF?,
        dest: File,
    ) {
        val document = LabReport.of(curve, ViewerStressStrainHelper.modulusOf(curve))
            ?: error("No lab report for this session")
        val graphs = withContext(Dispatchers.Main) {
            document.graphs.associate { it.id to render(it) }
        }
        val figure = reference?.let {
            LabReportPdf.FigureImage(it, imageSize.first, imageSize.second, roi)
        }
        withContext(Dispatchers.IO) {
            dest.outputStream().use { LabReportPdf(figure, graphs).write(document, it) }
        }
        graphs.values.forEach { it.recycle() }
    }

    private fun render(graph: LabReport.Block.Graph): Bitmap = VsgPlotView(context).run {
        setData(
            graph.series.map { series ->
                VsgPlotView.Series(
                    label = graph.title,
                    color = VsgPlotView.paletteColor(context, if (series.isFit) 1 else 0),
                    points = series.points,
                    markers = !series.isFit,
                    muted = series.isFit,
                )
            },
            graph.xLabel,
            graph.yLabel,
        )
        renderToBitmap(PLOT_W, PLOT_H)
    }

    companion object {
        private const val PLOT_W = 1800
        private const val PLOT_H = 1300

        /**
         * Whether a session gets the row: a typed test with a load per frame,
         * not a parameter sweep, whose test has a lab template.
         */
        fun offered(testType: String, hasLoads: Boolean, isSweep: Boolean): Boolean =
            hasLoads && !isSweep && TestType.fromWire(testType) == TestType.TENSILE
    }
}
