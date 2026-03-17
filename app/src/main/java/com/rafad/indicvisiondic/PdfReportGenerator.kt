package com.rafad.indicvisiondic

import android.graphics.pdf.PdfDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.OutputStream

object PdfReportGenerator {

    sealed class Progress {
        data class Status(val message: String, val percent: Int) : Progress()
        object Complete : Progress()
        data class Error(val ex: Exception) : Progress()
    }

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
            layout.advanceY(40f)

            layout.drawSectionHeader("Algorithm Parameters")
            layout.drawKeyValue("Subset Size:", "${data.subsetSize} px")
            layout.drawKeyValue("Step Size:", "${data.stepSize} px")
            layout.drawKeyValue("Strain Method:", data.strainMethod)
            layout.drawKeyValue("Strain Window:", "${data.strainWindow} subsets")
            layout.advanceY(40f)

            layout.drawSectionHeader("Reference Image Baseline")
            data.referenceImage?.let { layout.drawImage(it) }

            // PAGE 2 to 6: FIELD VISUALIZATIONS
            emit(Progress.Status("Rendering Visualization Maps...", 30))
            data.fieldResults.forEachIndexed { index, field ->
                emit(Progress.Status("Rendering ${field.fieldName}...", 30 + (index * 10)))
                layout.newPage()
                layout.drawTitle(field.fieldName)

                layout.drawTable(
                    headers = listOf("Metric", "Peak Value", "Location (X,Y)"),
                    rows = listOf(
                        listOf("Maximum (+)", "%.5f %s".format(field.maxValue, field.unit), "(${field.maxCoordX}, ${field.maxCoordY})"),
                        listOf("Minimum (-)", "%.5f %s".format(field.minValue, field.unit), "(${field.minCoordX}, ${field.minCoordY})"),
                        listOf("Mean", "%.5f %s".format(field.meanValue, field.unit), "—"),
                        listOf("Standard Dev.", "%.5f %s".format(field.stdDevValue, field.unit), "—")
                    ),
                    colWeights = listOf(0.3f, 0.35f, 0.35f)
                )

                layout.drawSectionHeader("Visualization Map")
                layout.drawImage(field.bakedHeatmap)
            }

            // FINAL PAGE: TELEMETRY & HARDWARE LOG
            emit(Progress.Status("Compiling Engine Telemetry...", 90))
            layout.newPage()
            layout.drawTitle("Engine Performance Log")

            val stats = data.engineStats
            layout.drawSectionHeader("Optimization Metrics")
            layout.drawTable(
                headers = listOf("Metric", "Value"),
                rows = listOf(
                    listOf("Total Points Attempted", "${stats.totalPointsAttempted}"),
                    listOf("Points Solved (Mesh)", "${stats.pathAPoints}"),
                    listOf("Points Solved (Flood Fill)", "${stats.pathBPoints}"),
                    listOf("Points Rejected (Failed)", "${stats.totalPointsRejected}"),
                    listOf("Convergence Rate", "%.2f %%".format(stats.convergencePercent)),
                    listOf("Avg ICGN Iterations", "%.2f".format(stats.avgIcgnIterations))
                ),
                colWeights = listOf(0.6f, 0.4f)
            )

            layout.drawSectionHeader("Simplex Rescue Subsystem")
            layout.drawTable(
                headers = listOf("Intervention", "Count"),
                rows = listOf(
                    listOf("Total Rescues Attempted", "${stats.simplexRescueTotal}"),
                    listOf("Rescues Succeeded", "${stats.simplexSavedCount}"),
                    listOf("Rescues Failed", "${stats.simplexDeadCount}")
                ),
                colWeights = listOf(0.6f, 0.4f)
            )

            layout.drawSectionHeader("Hardware Profiling (Wall Time)")
            layout.drawTable(
                headers = listOf("Execution Phase", "Time (ms)"),
                rows = listOf(
                    listOf("AKAZE + RANSAC Phase", "%.1f ms".format(stats.akazeRansacMs)),
                    listOf("Hessian Pre-Pass", "%.1f ms".format(stats.hessianPrepassMs)),
                    listOf("Delaunay Mesh Phase", "%.1f ms".format(stats.delaunayMs)),
                    listOf("Strain Calculation Phase", "%.1f ms".format(stats.strainMs)),
                    listOf("TOTAL WALL TIME", "%.1f ms".format(stats.wallTimeMs)),
                    listOf("Peak Throughput", "%.2f pts/ms".format(stats.throughputPtsPerMs))
                ),
                colWeights = listOf(0.6f, 0.4f)
            )

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
}