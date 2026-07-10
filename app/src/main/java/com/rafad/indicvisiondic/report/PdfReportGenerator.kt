package com.rafad.indicvisiondic.report

import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.OutputStream

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

    /** One chapter of the all-frames report: heatmap + per-field stats. */
    data class FrameChapter(
        val title: String,
        val image: android.graphics.Bitmap?,
        // rows: field / max / min / mean
        val statRows: List<List<String>>,
    )

    /**
     * The whole-analysis PDF: cover with parameters, one chapter per frame
     * (annotated heatmap of the on-screen field + a five-field stats table),
     * and the engine telemetry of the first frame at the end. Chapters are
     * built lazily and their bitmaps recycled page-by-page, so a 50-frame
     * report never holds more than one frame's bitmap in memory.
     */
    fun generateBatch(
        cover: ReportData,
        frameCount: Int,
        chapterAt: (Int) -> FrameChapter,
        outputStream: OutputStream,
    ): Flow<Progress> = flow {
        val pdfDocument = PdfDocument()
        val layout = PdfLayoutEngine(pdfDocument)
        try {
            emit(Progress.Status("Building cover…", 2))
            layout.newPage()
            layout.drawTitle("DIC Analysis Report — All Frames")
            layout.drawSectionHeader("Session Details")
            layout.drawKeyValue("Specimen / Target:", cover.specimenName)
            layout.drawKeyValue("Date Generated:", cover.analysisDate)
            layout.drawKeyValue("Session ID:", cover.sessionId)
            layout.drawKeyValue("Frames:", frameCount.toString())
            cover.appBuild?.let { layout.drawKeyValue("App Build:", it) }
            layout.advanceY(40f)
            layout.drawSectionHeader("Algorithm Parameters")
            layout.drawKeyValue("Subset Size:", "${cover.subsetSize} px")
            layout.drawKeyValue("Step Size:", "${cover.stepSize} px")
            layout.drawKeyValue("Strain Method:", cover.strainMethod)
            layout.drawKeyValue("Strain Window:", "${cover.strainWindow} subsets")
            layout.advanceY(40f)
            layout.drawSectionHeader("Analysis Region (ROI)")
            layout.drawKeyValue("Origin (X, Y):", "(${cover.roiData.startX}, ${cover.roiData.startY})")
            layout.drawKeyValue("Dimensions:", "${cover.roiData.width} x ${cover.roiData.height} px")

            for (index in 0 until frameCount) {
                emit(Progress.Status("Frame ${index + 1} of $frameCount…", 5 + (index * 90 / frameCount)))
                val chapter = chapterAt(index)
                layout.newPage()
                layout.drawTitle(chapter.title)
                if (chapter.statRows.isNotEmpty()) {
                    layout.drawTable(
                        headers = listOf("Field", "Max", "Min", "Mean"),
                        rows = chapter.statRows,
                        colWeights = listOf(0.31f, 0.23f, 0.23f, 0.23f),
                    )
                }
                chapter.image?.let { bmp ->
                    layout.advanceY(30f)
                    layout.drawDiagnosticBlock("", bmp, 2100f)
                    bmp.recycle()
                }
            }

            emit(Progress.Status("Telemetry…", 96))
            drawTelemetryPage(layout, cover)

            pdfDocument.writeTo(outputStream)
            emit(Progress.Complete)
        } catch (e: Exception) {
            emit(Progress.Error(e))
        } finally {
            pdfDocument.close()
        }
    }.flowOn(Dispatchers.Default)

    fun generate(data: ReportData, outputStream: OutputStream): Flow<Progress> = flow {
        val pdfDocument = PdfDocument()
        val layout = PdfLayoutEngine(pdfDocument)

        try {
            // PAGE 1: METADATA & IMAGES
            emit(Progress.Status("Building Cover Page...", 10))
            layout.newPage()
            layout.drawTitle("Master DIC Analysis Report")

            layout.drawSectionHeader("Session Details")
            layout.drawKeyValue("Specimen / Target:", data.specimenName)
            layout.drawKeyValue("Date Generated:", data.analysisDate)
            layout.drawKeyValue("Session ID:", data.sessionId)
            data.appBuild?.let { layout.drawKeyValue("App Build:", it) }
            layout.advanceY(40f)

            layout.drawSectionHeader("Algorithm Parameters")
            layout.drawKeyValue("Subset Size:", "${data.subsetSize} px")
            layout.drawKeyValue("Step Size:", "${data.stepSize} px")
            layout.drawKeyValue("Strain Method:", data.strainMethod)
            layout.drawKeyValue("Strain Window:", "${data.strainWindow} subsets")
            layout.advanceY(40f)

            // Add the ROI information explicitly to the first page!
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

            // PAGES 2+: FIELD VISUALIZATIONS (Strictly 2 Per Page!)
            emit(Progress.Status("Rendering Visualization Maps...", 30))

            val blockHeight = 1604f

            // Chunking the 5 fields:
            // Chunk 1 = U, V (Page 2)
            // Chunk 2 = Exx, Eyy (Page 3)
            // Chunk 3 = Exy (Page 4, top slot)
            data.fieldResults.chunked(2).forEachIndexed { pageIndex, fieldsChunk ->
                layout.newPage()

                fieldsChunk.forEachIndexed { index, field ->
                    emit(Progress.Status("Rendering ${field.fieldName}...", 30 + (pageIndex * 2 + index) * 10))
                    layout.drawFieldBlock(field, blockHeight)
                }

                // Fill the empty slot! If this chunk only has 1 item (Exy Shear),
                // we have exactly enough space left on the page to print the ZNSSD Heatmap.
                if (fieldsChunk.size == 1) {
                    emit(Progress.Status("Rendering Diagnostic ZNSSD Map...", 85))
                    layout.drawDiagnosticBlock("ZNSSD Correlation Quality", data.znssdHeatmap, blockHeight)
                }
            }

            // FINAL PAGE: TELEMETRY & HARDWARE LOG
            emit(Progress.Status("Compiling Engine Telemetry...", 90))
            drawTelemetryPage(layout, data)

            emit(Progress.Status("Finalizing PDF...", 98))
            layout.finishCurrentPage()
            pdfDocument.writeTo(outputStream)

            emit(Progress.Complete)
        } catch (e: Exception) {
            emit(Progress.Error(e))
        } finally {
            pdfDocument.close()
        }
    }.flowOn(Dispatchers.IO)

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
}
