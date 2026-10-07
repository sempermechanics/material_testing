package com.sempermechanics.semper.ui.viewer.share

import android.content.Context
import android.graphics.RectF
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.PdfReportGenerator
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.analysis.sweep.SweepPlotView
import com.sempermechanics.semper.ui.viewer.mechanical.LabReportExporter
import com.sempermechanics.semper.ui.viewer.mechanical.ViewerStressStrainHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The lab test's part of a [ShareExportBuilder] job: the stress–strain page
 * that closes the all-frames PDF, and the student lab report
 * ([ShareKind.LAB_PDF]). Like the rest of the job it holds data only — the
 * snapshot, the application [context] its plots are drawn with, and the job's
 * [outDir] — never the viewer.
 */
internal class LabExport(
    private val s: ShareCenter.Snapshot,
    private val context: Context,
    private val outDir: File,
    private val images: FieldImageExport,
) {

    /**
     * The report's closing stress–strain page, or null for a session without
     * loads. Reuses the curve the viewer already built when it has one;
     * otherwise walks the batch here (one decode per frame, before the report
     * starts its own). The plot is a [SweepPlotView] drawn off screen, which
     * must happen on the main thread.
     */
    suspend fun stressStrainPage(report: (Int, String) -> Unit): PdfReportGenerator.StressStrainPage? {
        if (s.loadsN.isEmpty() || s.isSweep) return null
        val curve = sessionCurve(report)
        if (curve.isEmpty) return null
        val axisLabels = ViewerStressStrainHelper.axisLabels(context, curve.model)
        val modulus = ViewerStressStrainHelper.modulusOf(curve)
        val plot = withContext(Dispatchers.Main) {
            val print = ViewerStressStrainHelper.printContext(context)
            SweepPlotView(print).run {
                setData(
                    ViewerStressStrainHelper.plotSeries(print, curve, modulus),
                    axisLabels.first,
                    axisLabels.second,
                    marks = ViewerStressStrainHelper.plotMarks(print, curve, modulus),
                )
                renderToBitmap(STRESS_STRAIN_PLOT_W, STRESS_STRAIN_PLOT_H)
            }
        }
        return PdfReportGenerator.StressStrainPage(curve, plot, modulus)
    }

    /** The student lab report: the handwritten journal layout, filled with this session. */
    suspend fun labReportPdf(report: (Int, String) -> Unit): File {
        val curve = sessionCurve(report)
        report(LAB_REPORT_PROGRESS, context.getString(R.string.share_generating_pdf))
        val size = s.imageSize
        val capped = VisualizationEngine.cappedDims(size.width, size.height, VisualizationEngine.REPORT_MAX_EDGE)
        val reference = withContext(Dispatchers.IO) {
            runCatching { images.loadCappedBase(capped.width, capped.height) }.getOrNull()
        }
        val roi = s.reportSource.roi
        val roiRect = if (roi.w > 0 && roi.h > 0) {
            RectF(roi.x.toFloat(), roi.y.toFloat(), (roi.x + roi.w).toFloat(), (roi.y + roi.h).toFloat())
        } else {
            null
        }
        val f = File(outDir, "${s.baseName}_lab_report.pdf")
        LabReportExporter(context).write(curve, reference, size.width to size.height, roiRect, f)
        return f
    }

    /**
     * The session's stress–strain curve: the viewer's when it has built one,
     * otherwise a walk over the batch here (one decode per frame).
     */
    private suspend fun sessionCurve(report: (Int, String) -> Unit): StressStrain.Curve =
        s.stressStrain ?: withContext(Dispatchers.Default) {
            val byFrame = SessionPaths.datByPlannedFrame(s.batchFiles)
            StressStrain.build(
                loadsN = s.loadsN.toList(),
                model = s.stressModel,
                frameData = { frame -> byFrame[frame]?.let { DicResult.decodeDatFile(it) } },
                onProgress = { done -> report(0, "Stress–strain $done / ${s.loadsN.size}…") },
            )
        }

    private companion object {
        /** Off-screen render size of the report's stress–strain plot (3:2, downscaled onto the page). */
        const val STRESS_STRAIN_PLOT_W = 1800
        const val STRESS_STRAIN_PLOT_H = 1200

        const val LAB_REPORT_PROGRESS = 60
    }
}
