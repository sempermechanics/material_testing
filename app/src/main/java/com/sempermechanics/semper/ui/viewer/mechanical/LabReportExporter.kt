package com.sempermechanics.semper.ui.viewer.mechanical

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.report.LabReport
import com.sempermechanics.semper.report.LabReportPdf
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.ui.analysis.sweep.VsgPlotView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The share sheet's "Lab report (PDF)": the student write-up [LabReport]
 * lays out, with its graphs drawn by the same off-screen [VsgPlotView] the
 * full report uses and the reference photo as the setup figure — for bending
 * with the two thickness taps and the ring deflection is read in.
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
        val probe = (curve.model as? StressStrain.Model.Flexural)?.probe
        val marks = probe?.taps?.let { listOf(PointF(it.topX, it.topY), PointF(it.bottomX, it.bottomY)) }.orEmpty()
        val ring = probe?.let { PointF(it.taps.midX, it.taps.midY) to it.radiusPx }
        val figure = reference?.let {
            LabReportPdf.FigureImage(it, imageSize.first, imageSize.second, roi, marks, ring)
        }
        withContext(Dispatchers.IO) {
            dest.outputStream().use { LabReportPdf(figure, graphs).write(document, it) }
        }
        graphs.values.forEach { it.recycle() }
    }

    private fun render(graph: LabReport.Block.Graph): Bitmap {
        val print = ViewerStressStrainHelper.printContext(context)
        return VsgPlotView(print).run {
            setData(
                graph.series.map { series ->
                    VsgPlotView.Series(
                        label = graph.title,
                        color = VsgPlotView.paletteColor(print, if (series.isFit) 1 else 0),
                        points = series.points,
                        markers = !series.isFit,
                        muted = series.isFit,
                    )
                },
                graph.xLabel,
                graph.yLabel,
                marks = graph.marks.map { VsgPlotView.Mark(it.x, it.y, it.label, VsgPlotView.paletteColor(print, 1)) },
            )
            renderToBitmap(PLOT_W, PLOT_H)
        }
    }

    companion object {
        private const val PLOT_W = 1800
        private const val PLOT_H = 1300

        /**
         * Whether a session gets the row: a typed test with a load per frame,
         * not a parameter sweep, whose test has a lab template. Bending's
         * template needs the thickness taps: without them there is no
         * deflection, so no observation table.
         */
        fun offered(testType: String, hasLoads: Boolean, isSweep: Boolean, hasLoadPoint: Boolean): Boolean =
            hasLoads &&
                !isSweep &&
                when (TestType.fromWire(testType)) {
                    TestType.TENSILE -> true
                    TestType.BENDING -> hasLoadPoint
                    TestType.DIC_2D, null -> false
                }
    }
}
