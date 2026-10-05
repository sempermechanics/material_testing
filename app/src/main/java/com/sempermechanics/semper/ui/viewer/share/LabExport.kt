package com.sempermechanics.semper.ui.viewer.share

import android.content.Context
import android.graphics.RectF
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.CurveCorrection
import com.sempermechanics.semper.data.SpecimenGeometry
import com.sempermechanics.semper.data.loadOfFrame
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.report.MechanicalCover
import com.sempermechanics.semper.report.PdfReportGenerator
import com.sempermechanics.semper.report.StressStrain
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.analysis.sweep.VsgPlotView
import com.sempermechanics.semper.ui.viewer.LabReportExporter
import com.sempermechanics.semper.ui.viewer.ViewerStressStrainHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The mechanical test behind a viewer's exports, captured on the main thread
 * with the rest of [ViewerReportFactory.Source]: blank / empty on a plain DIC
 * session. [geometry] and [curveCorrection] are the ones on screen (a
 * correction typed in Results wins over the session's); [stressStrain] is the
 * viewer's already-built curve, when its Results have been opened.
 */
data class LabSnapshot(
    val testType: String = "",
    val crossSectionMm2: Float = 0f,
    val loadAxisX: Boolean = true,
    val loadsN: FloatArray = FloatArray(0),
    val geometry: SpecimenGeometry = SpecimenGeometry.NONE,
    val curveCorrection: CurveCorrection = CurveCorrection.NONE,
    val stressStrain: StressStrain.Curve? = null,
) {
    /** How this session's loads become stress; axial for a plain DIC session. */
    val stressModel: StressStrain.Model
        get() = StressStrain.Model.of(testType, crossSectionMm2, loadAxisX, geometry, curveCorrection)

    /** The machine load behind planned frame [planned], or null without one (TD-91). */
    fun loadAt(planned: Int): Float? = loadsN.loadOfFrame(planned)

    /** The report cover's mechanical block for planned frame [planned], or null on a plain DIC session. */
    fun coverAt(planned: Int): MechanicalCover? {
        val type = testType.ifBlank { return null }
        return MechanicalCover(testType = type, model = stressModel, loadN = loadAt(planned))
    }

    // FloatArray compares by identity; a snapshot is compared as the values it holds.
    override fun equals(other: Any?): Boolean = other is LabSnapshot &&
        testType == other.testType &&
        crossSectionMm2 == other.crossSectionMm2 &&
        loadAxisX == other.loadAxisX &&
        loadsN.contentEquals(other.loadsN) &&
        geometry == other.geometry &&
        curveCorrection == other.curveCorrection &&
        stressStrain == other.stressStrain

    override fun hashCode(): Int = arrayOf(
        testType,
        crossSectionMm2,
        loadAxisX,
        loadsN.contentHashCode(),
        geometry,
        curveCorrection,
        stressStrain,
    ).contentHashCode()

    companion object {
        val NONE = LabSnapshot()
    }
}

/**
 * The lab parts of a [ShareExportBuilder] job: the full report's closing
 * stress–strain page and the student lab report ([ShareKind.LAB_PDF]). The
 * plots are [VsgPlotView]s drawn off screen with the application [context],
 * never the viewer, so the job keeps no Activity alive.
 */
internal class LabExport(
    private val s: ShareCenter.Snapshot,
    private val context: Context,
    private val outDir: File,
    private val images: FieldImageExport,
) {
    private val lab: LabSnapshot get() = s.reportSource.lab

    /**
     * The report's closing stress–strain page, or null for a session without
     * loads. Reuses the curve the viewer already built when it has one;
     * otherwise walks the batch here (one decode per frame, before the report
     * starts its own). The plot is a [VsgPlotView] drawn off screen, which
     * must happen on the main thread.
     */
    suspend fun stressStrainPage(report: (Int, String) -> Unit): PdfReportGenerator.StressStrainPage? {
        if (lab.loadsN.isEmpty() || s.isSweep) return null
        val curve = sessionCurve(report)
        if (curve.isEmpty) return null
        val axisLabels = ViewerStressStrainHelper.axisLabels(context, curve.model)
        val modulus = ViewerStressStrainHelper.modulusOf(curve)
        val plot = withContext(Dispatchers.Main) {
            val print = ViewerStressStrainHelper.printContext(context)
            VsgPlotView(print).run {
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
        val roi = s.reportSource.roi.takeIf { it.w > 0 && it.h > 0 }?.let {
            RectF(it.x.toFloat(), it.y.toFloat(), (it.x + it.w).toFloat(), (it.y + it.h).toFloat())
        }
        val f = File(outDir, labReportName(s))
        LabReportExporter(context).write(curve, reference, size.width to size.height, roi, f)
        return f
    }

    /**
     * The session's stress–strain curve: the viewer's when it has built one,
     * otherwise a walk over the batch here (one decode per frame).
     */
    private suspend fun sessionCurve(report: (Int, String) -> Unit): StressStrain.Curve =
        lab.stressStrain ?: withContext(Dispatchers.Default) {
            val byFrame = SessionPaths.datByPlannedFrame(s.batchFiles)
            StressStrain.build(
                loadsN = lab.loadsN.toList(),
                model = lab.stressModel,
                frameData = { frame -> byFrame[frame]?.let { DicResult.decodeDatFile(it) } },
                onProgress = { done -> report(0, "Stress–strain $done / ${lab.loadsN.size}…") },
            )
        }

    companion object {
        /** Off-screen render size of the report's stress–strain plot (3:2, downscaled onto the page). */
        private const val STRESS_STRAIN_PLOT_W = 1800
        private const val STRESS_STRAIN_PLOT_H = 1200
        private const val LAB_REPORT_PROGRESS = 60

        /** The lab report's file name, as the save-as picker suggests it too. */
        fun labReportName(s: ShareCenter.Snapshot): String = "${s.baseName}_lab_report.pdf"
    }
}
