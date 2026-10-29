// PDF assembly: literal DPI/page dimensions are inherent to layout, so
// MagicNumber is suppressed for this file.
@file:Suppress("MagicNumber")

package com.sempermechanics.semper.report

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.CoroutineContext

/**
 * Renders a [ReportData] into the multi-page PDF report (cover, field
 * statistics + heatmaps, engine telemetry), emitting progress as a Flow.
 * The flow completes when the document is written and throws when it cannot
 * be: a failure is never a progress event. Page drawing primitives live in
 * [PdfLayoutEngine].
 */
object PdfReportGenerator {

    /** What a [Progress.Status] is on. The caller words it; nothing here is drawn. */
    enum class Stage { FRAME, COVER, MAPS, STRESS_STRAIN, TELEMETRY, FINISHING }

    sealed class Progress {
        /**
         * At [percent] of the document, on [stage]. A [Stage.FRAME] status
         * names the frame being drawn, 1-based, as [frame] of [frameCount];
         * both are 0 for the other stages.
         */
        data class Status(
            val stage: Stage,
            val percent: Int,
            val frame: Int = 0,
            val frameCount: Int = 0,
        ) : Progress()
    }

    /** Field visualisations are laid out strictly two to a page. */
    private const val FIELD_BLOCK_HEIGHT = 1604f

    // Progress budget: the frames share the middle of the bar, leaving a little
    // at each end for setup and the closing telemetry page.
    private const val FRAMES_PROGRESS_START = 2
    private const val FRAMES_PROGRESS_SPAN = 92
    private const val TELEMETRY_PROGRESS = 96
    private const val FINALIZING_PROGRESS = 98

    /** Raster width for the vector wordmark; PDF draws it at [PdfLayoutEngine.BRAND_LOGO_WIDTH]. */
    private const val BRAND_LOGO_RASTER_WIDTH = 1040

    /**
     * The closing stress–strain page(s) of an all-frames report: the curve as
     * rendered by the caller (null when it could not be drawn), its points and,
     * for tensile, the elastic modulus fit.
     */
    class StressStrainPage(
        val curve: StressStrain.Curve,
        val plot: Bitmap?,
        val modulus: ElasticModulus.Fit? = null,
    )

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
     *
     * The pages render on [renderContext]. A failure there cancels the
     * collector, which drops the statuses still buffered between the two
     * contexts, so a test that reads the statuses of a failing render passes
     * [kotlin.coroutines.EmptyCoroutineContext] and renders on its own thread.
     */
    @Suppress("LongParameterList") // one call site; the report's full config, all named and defaulted
    fun generateBatch(
        frameCount: Int,
        dataAt: (Int) -> ReportData?,
        outputStream: OutputStream,
        frameTitle: (Int) -> String = { "${ReportBuilder.REPORT_NAME} — Frame ${it + 1}" },
        resources: Resources? = null,
        stressStrain: StressStrainPage? = null,
        renderContext: CoroutineContext = Dispatchers.Default,
    ): Flow<Progress> = renderPdf(outputStream, resources) { layout ->
        // The session's engine stats are its first frame's, so one page
        // closes the document; its ZNSSD is pooled over every frame drawn.
        // Holds no bitmaps, so it survives the recycling below.
        var telemetrySource: ReportData? = null
        val znssdFrames = mutableListOf<ZnssdFrame>()

        for (index in 0 until frameCount) {
            currentCoroutineContext().ensureActive()
            val percent = FRAMES_PROGRESS_START + (index * FRAMES_PROGRESS_SPAN / frameCount)
            emit(Progress.Status(Stage.FRAME, percent, frame = index + 1, frameCount = frameCount))
            val data = dataAt(index) ?: continue
            if (telemetrySource == null) telemetrySource = data
            znssdFrames += ZnssdFrame(data.globalAvgZnssd, data.znssdAcceptedPoints)

            try {
                drawCoverPage(layout, data, frameTitle(index), frameCount)
                drawFieldPages(layout, data)
            } finally {
                // A page that fails to draw still frees this frame's images.
                recycleImages(data)
            }
        }

        if (stressStrain != null && !stressStrain.curve.isEmpty) {
            emit(Progress.Status(Stage.STRESS_STRAIN, TELEMETRY_PROGRESS))
            MechanicalPdfPages.drawStressStrainPages(layout, stressStrain)
        }
        telemetrySource?.let {
            emit(Progress.Status(Stage.TELEMETRY, TELEMETRY_PROGRESS))
            drawTelemetryPage(layout, it, TelemetrySummary.batch(znssdFrames))
        }
    }.flowOn(renderContext)

    fun generate(
        data: ReportData,
        outputStream: OutputStream,
        resources: Resources? = null,
    ): Flow<Progress> = renderPdf(outputStream, resources) { layout ->
        currentCoroutineContext().ensureActive()
        emit(Progress.Status(Stage.COVER, 10))
        drawCoverPage(layout, data, ReportBuilder.REPORT_NAME, frameCount = null)

        currentCoroutineContext().ensureActive()
        emit(Progress.Status(Stage.MAPS, 30))
        drawFieldPages(layout, data)

        currentCoroutineContext().ensureActive()
        emit(Progress.Status(Stage.TELEMETRY, 90))
        drawTelemetryPage(layout, data, TelemetrySummary.single(data))
    }.flowOn(Dispatchers.IO)

    /**
     * One PDF document into [outputStream]: [draw] lays its pages out on a
     * [PdfLayoutEngine] (the wordmark from [resources] heads each page), then
     * the document is written under a [Stage.FINISHING] status at
     * [FINALIZING_PROGRESS] (one call over every page, so a batch is not left
     * under its telemetry status while every frame's pages are written). The
     * flow completes once the document is written; a failure, cancellation
     * included, is thrown to the collector, never emitted, so the flow stays
     * exception-transparent.
     */
    private fun renderPdf(
        outputStream: OutputStream,
        resources: Resources?,
        draw: suspend FlowCollector<Progress>.(PdfLayoutEngine) -> Unit,
    ): Flow<Progress> = flow {
        val pdfDocument = PdfDocument()
        val brandLogo = decodeBrandLogo(resources)
        val layout = PdfLayoutEngine(pdfDocument, brandLogo)
        try {
            draw(layout)
            emit(Progress.Status(Stage.FINISHING, FINALIZING_PROGRESS))
            // Finish the still-open page before writing — PdfDocument rejects
            // writeTo()/close() while any page is unfinished.
            layout.finishCurrentPage()
            writeChecked(pdfDocument, outputStream)
        } finally {
            // PdfDocument.close() throws IllegalStateException while a page is
            // unfinished, so a draw that failed mid-page would otherwise replace
            // its failure (or a cancellation) with that throw and skip the close.
            layout.finishCurrentPage()
            pdfDocument.close()
            recycleLogo(brandLogo)
        }
    }

    /**
     * Page 1 of a report: the analysis metadata, the parameters it was solved with,
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

        layout.drawSectionHeader("Analysis details")
        layout.drawKeyValue("Specimen / target:", data.specimenName)
        layout.drawKeyValue("Date generated:", data.analysisDate)
        layout.drawKeyValue("Analysis ID:", data.sessionId)
        frameCount?.let { layout.drawKeyValue("Frames:", it.toString()) }
        data.appBuild?.let { layout.drawKeyValue("App build:", it) }
        data.recordFingerprint?.let { layout.drawKeyValue("Record fingerprint:", it) }
        layout.advanceY(40f)

        layout.drawSectionHeader("Algorithm parameters")
        layout.drawKeyValue("Subset size:", "${data.subsetSize} px")
        layout.drawKeyValue("Step size:", "${data.stepSize} px")
        layout.drawKeyValue("Strain method:", data.strainMethod)
        // strainWindow is the VSG in px; sessions since the window was entered in points also get the count.
        val windowPoints = SweepStudy.windowPointsFor(data.strainWindow, data.stepSize)
        layout.drawKeyValue(
            "Strain window:",
            if (windowPoints != null) {
                "$windowPoints-point window (VSG ${data.strainWindow} px)"
            } else {
                "VSG ${data.strainWindow} px"
            },
        )
        layout.advanceY(40f)

        data.mechanical?.let { MechanicalPdfPages.drawCoverBlock(layout, it) }

        layout.drawSectionHeader("Analysis region (ROI)")
        layout.drawKeyValue("Origin (X, Y):", "(${data.roi.x}, ${data.roi.y})")
        layout.drawKeyValue("Dimensions:", "${data.roi.w} x ${data.roi.h} px")
        layout.advanceY(40f)

        layout.drawSectionHeader("Analysed images")
        layout.drawInputVerificationCard(
            data.referenceImage,
            data.referenceImageName,
            data.deformedImage,
            data.deformedImageName,
        )
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
                layout.drawDiagnosticBlock("ZNSSD correlation quality", data.znssdHeatmap, FIELD_BLOCK_HEIGHT)
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

    /**
     * Final page: engine telemetry (shared by single and batch reports).
     * [summary] says what the quality rows are over — one frame, or pooled.
     */
    private fun drawTelemetryPage(layout: PdfLayoutEngine, data: ReportData, summary: TelemetrySummary) {
        layout.newPage()
        layout.drawTitle(TelemetrySummary.TITLE)
        layout.drawNotice(TelemetrySummary.INTRO)
        summary.scopeNote?.let { layout.drawNotice(it) }

        val stats = data.engineStats
        val sections = TelemetrySummary.SECTIONS

        layout.drawSectionHeader(sections[0])
        layout.drawTable(
            headers = listOf("Step", "Points"),
            rows = TelemetrySummary.pipelineRows(stats),
            colWeights = listOf(0.7f, 0.3f),
        )

        layout.drawSectionHeader(sections[1])
        layout.drawTable(
            headers = listOf("Measure", "Value"),
            rows = summary.qualityRows(stats),
            colWeights = listOf(0.7f, 0.3f),
        )

        layout.drawSectionHeader(sections[2])
        layout.drawTable(
            headers = listOf("Recovery", "Tried", "Recovered"),
            rows = TelemetrySummary.rescueRows(stats),
            colWeights = listOf(0.5f, 0.25f, 0.25f),
        )

        layout.drawSectionHeader(sections[3])
        layout.drawTable(
            headers = listOf("Phase", "Time"),
            rows = TelemetrySummary.timingRows(stats),
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
 * [PdfDocument.writeTo] drops an IOException its stream throws: the native
 * writer stops, and the call returns normally. A full disk would then
 * complete over a truncated file, so the stream's first failure is
 * kept and rethrown here.
 */
private fun writeChecked(pdfDocument: PdfDocument, out: OutputStream) {
    val checked = CheckedStream(out)
    pdfDocument.writeTo(checked)
    checked.failure?.let { throw it }
}

private class CheckedStream(private val out: OutputStream) : OutputStream() {
    var failure: IOException? = null
        private set

    override fun write(b: Int) = guard { out.write(b) }

    override fun write(b: ByteArray, off: Int, len: Int) = guard { out.write(b, off, len) }

    override fun flush() = guard { out.flush() }

    private inline fun guard(block: () -> Unit) {
        failure?.let { throw it }
        try {
            block()
        } catch (e: IOException) {
            failure = e
            throw e
        }
    }
}
