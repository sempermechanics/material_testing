// PDF assembly: literal DPI/page dimensions are inherent to layout, and the
// broad catches guard a whole document render (any failure aborts that page),
// so MagicNumber / TooGenericExceptionCaught are suppressed for this file.
@file:Suppress("MagicNumber", "TooGenericExceptionCaught")

package com.indicvision.semper.report

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.indicvision.semper.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.OutputStream
import java.util.Locale

/**
 * Renders a [ReportData] into the multi-page PDF report (cover, field
 * statistics + heatmaps, engine telemetry), emitting progress as a Flow.
 * Page drawing primitives live in [PdfLayoutEngine].
 */
object PdfReportGenerator {

    sealed class Progress {
        data class Status(val message: String, val percent: Int) : Progress()
        object Complete : Progress()
        data class Error(val ex: Exception) : Progress()
    }

    /** Field visualisations are laid out strictly two to a page. */
    private const val FIELD_BLOCK_HEIGHT = 1604f

    // Progress budget: the frames share the middle of the bar, leaving a little
    // at each end for setup and the closing telemetry page.
    private const val FRAMES_PROGRESS_START = 2
    private const val FRAMES_PROGRESS_SPAN = 92
    private const val TELEMETRY_PROGRESS = 96

    /** Raster width for the vector wordmark; PDF draws it at [PdfLayoutEngine.BRAND_LOGO_WIDTH]. */
    private const val BRAND_LOGO_RASTER_WIDTH = 1040

    /** Height of the stress–strain plot block; the table starts under it. */
    private const val STRESS_STRAIN_PLOT_HEIGHT = 1500f

    /** Table rows per page, at [PdfLayoutEngine.drawTable]'s row pitch. */
    private const val STRESS_STRAIN_ROWS_FIRST_PAGE = 14
    private const val STRESS_STRAIN_ROWS_PER_PAGE = 34

    /**
     * The closing stress–strain page(s) of an all-frames report: the curve as
     * rendered by the caller (null when it could not be drawn) and its points.
     */
    class StressStrainPage(val curve: StressStrain.Curve, val plot: Bitmap?)

    /**
     * The all-frames PDF: the single-frame report of [generate], repeated once
     * per frame and concatenated, with one engine-telemetry page at the end.
     *
     * Each frame therefore gets the full treatment — its own cover with the
     * parameters and input images it was solved with, then its five field
     * blocks two to a page and the ZNSSD diagnostic — rather than a condensed
     * summary. That is what makes the frames of a batch, and the parameter
     * combinations of a sweep, directly comparable page for page.
     *
     * [dataAt] is called one frame at a time and each frame's bitmaps are
     * recycled before the next is built, so a 50-frame report never holds more
     * than one frame's images in memory. It returns null for a frame that
     * cannot be read, which is skipped.
     */
    @Suppress("LongParameterList") // one call site; the report's full config, all named and defaulted
    fun generateBatch(
        frameCount: Int,
        dataAt: (Int) -> ReportData?,
        outputStream: OutputStream,
        frameTitle: (Int) -> String = { "DIC Analysis Report — Frame ${it + 1}" },
        resources: Resources? = null,
        stressStrain: StressStrainPage? = null,
    ): Flow<Progress> = flow {
        val pdfDocument = PdfDocument()
        val brandLogo = decodeBrandLogo(resources)
        val layout = PdfLayoutEngine(pdfDocument, brandLogo)
        try {
            // Telemetry is per-analysis, not per-frame, so one page closes the
            // document. Holds no bitmaps, so it survives the recycling below.
            var telemetrySource: ReportData? = null

            for (index in 0 until frameCount) {
                currentCoroutineContext().ensureActive()
                val percent = FRAMES_PROGRESS_START + (index * FRAMES_PROGRESS_SPAN / frameCount)
                emit(Progress.Status("Frame ${index + 1} of $frameCount…", percent))
                val data = dataAt(index) ?: continue
                if (telemetrySource == null) telemetrySource = data

                drawCoverPage(layout, data, frameTitle(index), frameCount)
                drawFieldPages(layout, data)
                recycleImages(data)
            }

            if (stressStrain != null && !stressStrain.curve.isEmpty) {
                emit(Progress.Status("Plotting stress–strain…", TELEMETRY_PROGRESS))
                drawStressStrainPages(layout, stressStrain)
            }
            telemetrySource?.let {
                emit(Progress.Status("Compiling Engine Telemetry...", TELEMETRY_PROGRESS))
                drawTelemetryPage(layout, it)
            }

            // Finish the still-open page before writing — PdfDocument rejects
            // writeTo()/close() while any page is unfinished.
            layout.finishCurrentPage()
            pdfDocument.writeTo(outputStream)
            emit(Progress.Complete)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Progress.Error(e))
        } finally {
            layout.finishCurrentPage()
            pdfDocument.close()
            recycleLogo(brandLogo)
        }
    }.flowOn(Dispatchers.Default)

    fun generate(
        data: ReportData,
        outputStream: OutputStream,
        resources: Resources? = null,
    ): Flow<Progress> = flow {
        val pdfDocument = PdfDocument()
        val brandLogo = decodeBrandLogo(resources)
        val layout = PdfLayoutEngine(pdfDocument, brandLogo)

        try {
            currentCoroutineContext().ensureActive()
            emit(Progress.Status("Building Cover Page...", 10))
            drawCoverPage(layout, data, "Master DIC Analysis Report", frameCount = null)

            currentCoroutineContext().ensureActive()
            emit(Progress.Status("Rendering Visualization Maps...", 30))
            drawFieldPages(layout, data)

            currentCoroutineContext().ensureActive()
            emit(Progress.Status("Compiling Engine Telemetry...", 90))
            drawTelemetryPage(layout, data)

            emit(Progress.Status("Finalizing PDF...", 98))
            layout.finishCurrentPage()
            pdfDocument.writeTo(outputStream)

            emit(Progress.Complete)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Progress.Error(e))
        } finally {
            pdfDocument.close()
            recycleLogo(brandLogo)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Page 1 of a report: session metadata, the parameters it was solved with,
     * the ROI, and the reference/deformed pair it was solved from.
     *
     * @param frameCount total frames, shown only in an all-frames report
     */
    private fun drawCoverPage(
        layout: PdfLayoutEngine,
        data: ReportData,
        title: String,
        frameCount: Int?,
    ) {
        layout.newPage()
        layout.drawTitle(title)

        layout.drawSectionHeader("Session Details")
        layout.drawKeyValue("Specimen / Target:", data.specimenName)
        layout.drawKeyValue("Date Generated:", data.analysisDate)
        layout.drawKeyValue("Session ID:", data.sessionId)
        frameCount?.let { layout.drawKeyValue("Frames:", it.toString()) }
        data.appBuild?.let { layout.drawKeyValue("App Build:", it) }
        layout.advanceY(40f)

        layout.drawSectionHeader("Algorithm Parameters")
        layout.drawKeyValue("Subset Size:", "${data.subsetSize} px")
        layout.drawKeyValue("Step Size:", "${data.stepSize} px")
        layout.drawKeyValue("Strain Method:", data.strainMethod)
        layout.drawKeyValue("Strain Window:", "${data.strainWindow} px")
        layout.advanceY(40f)

        data.mechanical?.let { drawMechanicalBlock(layout, it) }

        layout.drawSectionHeader("Analysis Region (ROI)")
        layout.drawKeyValue("Origin (X, Y):", "(${data.roiData.startX}, ${data.roiData.startY})")
        layout.drawKeyValue("Dimensions:", "${data.roiData.width} x ${data.roiData.height} px")
        layout.advanceY(40f)

        layout.drawSectionHeader("Analyzed Images")
        layout.drawInputVerificationCard(
            data.referenceImage,
            data.referenceImageName,
            data.deformedImage,
            data.deformedImageName,
        )
    }

    /** The cover's "Mechanical Test" block, only on a typed session. */
    private fun drawMechanicalBlock(layout: PdfLayoutEngine, m: MechanicalCover) {
        layout.drawSectionHeader("Mechanical Test")
        layout.drawKeyValue("Test Type:", m.label)
        layout.drawDimensions(m.model)
        layout.drawKeyValue("Strain:", m.model.strainName)
        m.loadN?.let { loadN ->
            layout.drawKeyValue("Machine Load:", "%.2f N".format(Locale.US, loadN))
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
    private fun drawStressStrainPages(layout: PdfLayoutEngine, page: StressStrainPage) {
        val curve = page.curve
        layout.newPage()
        layout.drawTitle("Stress–Strain Curve")
        layout.drawDimensions(curve.model)
        layout.drawKeyValue("Strain:", "${curve.model.strainName} over accepted points")
        curve.peak?.let {
            layout.drawKeyValue("Peak Stress:", "%.3f MPa at frame %d".format(Locale.US, it.stressMPa, it.frame + 1))
        }
        layout.advanceY(20f)
        page.plot?.let {
            layout.drawDiagnosticBlock(
                "${curve.model.stressName} vs. ${curve.model.strainName}",
                it,
                STRESS_STRAIN_PLOT_HEIGHT,
            )
        }

        val rows = curve.points.map {
            listOf(
                "Frame ${it.frame + 1}",
                "%.2f".format(Locale.US, it.loadN),
                "%.3f".format(Locale.US, it.stressMPa),
                "%.3f".format(Locale.US, it.strainMilli),
            )
        }
        val headers = listOf("Frame", "Load (N)", "Stress (MPa)", "Strain (mε)")
        val weights = listOf(0.31f, 0.23f, 0.23f, 0.23f)
        val first = rows.take(STRESS_STRAIN_ROWS_FIRST_PAGE)
        layout.drawSectionHeader("Per-frame values")
        layout.drawTable(headers, first, weights)
        rows.drop(STRESS_STRAIN_ROWS_FIRST_PAGE).chunked(STRESS_STRAIN_ROWS_PER_PAGE).forEach { chunk ->
            layout.newPage()
            layout.drawTitle("Stress–Strain Curve (continued)")
            layout.drawTable(headers, chunk, weights)
        }
    }

    /**
     * Pages 2+: the five field blocks, two to a page — U and V, then Exx and
     * Eyy, then Exy. Exy leaves a free slot, which the ZNSSD correlation-quality
     * map fills exactly.
     */
    private fun drawFieldPages(layout: PdfLayoutEngine, data: ReportData) {
        data.fieldResults.chunked(2).forEach { fieldsChunk ->
            layout.newPage()
            fieldsChunk.forEach { field -> layout.drawFieldBlock(field, FIELD_BLOCK_HEIGHT) }
            if (fieldsChunk.size == 1) {
                layout.drawDiagnosticBlock("ZNSSD Correlation Quality", data.znssdHeatmap, FIELD_BLOCK_HEIGHT)
            }
        }
    }

    /**
     * Frees one frame's page images once it has been drawn. Every bitmap in a
     * [ReportData] is a private downscaled copy made by ReportBuilder — none
     * alias the caller's originals — so all of them are ours to release. Without
     * this a 50-frame report would hold 50 frames' images at once.
     */
    private fun recycleImages(data: ReportData) {
        data.fieldResults.forEach { it.bakedHeatmap.recycle() }
        data.znssdHeatmap.recycle()
        data.solverPathMap.recycle()
        data.referenceImage.recycle()
        data.deformedImage.recycle()
    }

    /** Final page: engine telemetry (shared by single and batch reports). */
    private fun drawTelemetryPage(layout: PdfLayoutEngine, data: ReportData) {
        layout.newPage()
        layout.drawTitle("Engine Performance Log")

        val stats = data.engineStats

        layout.drawSectionHeader("1. Solver Pipeline (2-Pass Architecture)")
        layout.drawTable(
            headers = listOf("Pipeline Stage", "Points"),
            rows = listOf(
                listOf("Seeding Mode", stats.meshSeedingLabel()),
                listOf("Total Target Grid Points", "${stats.totalPointsAttempted}"),
                listOf("Phase 1: Solved by Delaunay Mesh", "${stats.pathAPoints}"),
                listOf("Phase 2: Saved by RGDIC Propagation", "${stats.pathBPoints}"),
                listOf("Final Unsolvable (Dead Points)", "${stats.totalPointsRejected}"),
            ),
            colWeights = listOf(0.7f, 0.3f),
        )

        layout.drawSectionHeader("2. Optimization & Quality")
        layout.drawTable(
            headers = listOf("Metric", "Value"),
            rows = listOf(
                listOf("Global Average ZNSSD (Correlation)", "%.5f".format(data.globalAvgZnssd)),
                listOf("Overall Convergence Rate", "%.2f %%".format(stats.convergencePercent)),
                listOf("Average ICGN Iterations", "%.2f".format(stats.avgIcgnIterations)),
            ),
            colWeights = listOf(0.7f, 0.3f),
        )

        layout.drawSectionHeader("3. Simplex Rescue Subsystem")
        layout.drawTable(
            headers = listOf("Intervention", "Triggered", "Saved"),
            rows = listOf(
                listOf("Simplex Interventions", "${stats.simplexCalls}", "${stats.simplexSaved}"),
            ),
            colWeights = listOf(0.5f, 0.25f, 0.25f),
        )

        layout.drawSectionHeader("4. Hardware Profiling (Wall Time)")
        layout.drawTable(
            headers = listOf("Execution Phase", "Time (ms)"),
            rows = listOf(
                listOf("AKAZE + RANSAC Phase", "%.1f ms".format(stats.akazeRansacMs)),
                listOf("Hessian Pre-Pass", "%.1f ms".format(stats.hessianPrepassMs)),
                listOf("Delaunay Mesh Phase", "%.1f ms".format(stats.delaunayMs)),
                listOf("Strain Calculation Phase", "%.1f ms".format(stats.strainMs)),
                listOf("TOTAL WALL TIME", "%.1f ms".format(stats.wallTimeMs)),
                listOf("Average Throughput", "%.2f pts/ms".format(stats.avgThroughputPtsPerMs)),
            ),
            colWeights = listOf(0.6f, 0.4f),
        )
    }

    private fun decodeBrandLogo(resources: Resources?): Bitmap? {
        val src = resources ?: return null
        // Reports are always printed on a light page, so ignore night fills.
        val lightConfig = Configuration(src.configuration)
        lightConfig.uiMode = (lightConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            Configuration.UI_MODE_NIGHT_NO
        @Suppress("DEPRECATION")
        val lightResources = Resources(src.assets, src.displayMetrics, lightConfig)
        val drawable = ResourcesCompat.getDrawable(lightResources, R.drawable.semper_wordmark, null)
        return if (drawable == null) {
            null
        } else {
            val intrinsicW = drawable.intrinsicWidth.coerceAtLeast(1)
            val intrinsicH = drawable.intrinsicHeight.coerceAtLeast(1)
            val width = BRAND_LOGO_RASTER_WIDTH
            val height = (width.toLong() * intrinsicH / intrinsicW).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(width, height)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(bitmap))
            bitmap
        }
    }

    private fun recycleLogo(logo: Bitmap?) {
        if (logo != null && !logo.isRecycled) logo.recycle()
    }
}

/**
 * One key-value per entered dimension of a test's stress model. A file-level
 * extension rather than a member: the object is at detekt's function cap.
 */
private fun PdfLayoutEngine.drawDimensions(model: StressStrain.Model) {
    model.dimensions.forEach { (dimension, value) ->
        if (value > 0f) {
            drawKeyValue("${dimension.label}:", "%.3f %s".format(Locale.US, value, dimension.unit))
        }
    }
}
