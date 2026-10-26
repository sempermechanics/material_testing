package com.sempermechanics.semper.results

import android.graphics.Bitmap
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.fixtures.ShadowPdfDocumentWrite
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.report.FieldResult
import com.sempermechanics.semper.report.PdfReportGenerator
import com.sempermechanics.semper.report.PdfReportGenerator.Progress
import com.sempermechanics.semper.report.PdfReportGenerator.Stage
import com.sempermechanics.semper.report.ReportData
import com.sempermechanics.semper.report.TelemetrySummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * The report's progress contract and failure handling: the share sheet drives
 * its bar from these percents, and a failed render is thrown to the collector
 * (the flow stays exception-transparent), never emitted as an event.
 *
 * Robolectric's PdfDocument has no native document (`startPage` throws
 * "document is closed"), so drawing pages is covered on a device by
 * `PdfReportDeviceTest`; these cases never start a page. Its `writeTo` throws
 * the same way, so [ShadowPdfDocumentWrite] makes the final write a no-op: a
 * throw there could drop statuses still in the `flowOn` channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowPdfDocumentWrite::class])
class PdfReportGeneratorTest {

    /** What a flow emitted, and what ended it (here, normally the final write). */
    private class Collected(
        val statuses: List<Progress.Status>,
        val failure: Throwable?,
    ) {
        val failureText: String get() = failure?.stackTraceToString() ?: "none"
    }

    private fun collect(flow: Flow<Progress>): Collected {
        val events = mutableListOf<Progress>()
        val failure = runCatching { runBlocking { flow.collect { events += it } } }.exceptionOrNull()
        return Collected(events.filterIsInstance<Progress.Status>(), failure)
    }

    private fun statuses(flow: Flow<Progress>): List<Progress.Status> {
        val collected = collect(flow)
        // Only the final write may fail here; a flow that failed before its
        // first status is a defect, so show its cause rather than an empty list.
        if (collected.statuses.isEmpty() && collected.failure != null) {
            throw AssertionError("the PDF flow emitted nothing", collected.failure)
        }
        return collected.statuses
    }

    @Test
    fun `batch progress spreads frames over the middle of the bar`() {
        val collected = collect(PdfReportGenerator.generateBatch(4, { null }, ByteArrayOutputStream()))
        val events = collected.statuses
        // The write, last, has its own status rather than the last frame's.
        // The failure goes in the message: CI once saw no status at all here,
        // so the flow threw before its first emit, and the cause was swallowed.
        assertEquals(
            "statuses; the flow ended with: ${collected.failureText}",
            listOf(2, 25, 48, 71, 98),
            events.map { it.percent },
        )
        assertEquals(Progress.Status(Stage.FRAME, 2, frame = 1, frameCount = 4), events.first())
        assertEquals(Stage.FINISHING, events.last().stage)
        // The shadowed write lets the flow finish, so no status can be lost to a late throw.
        assertEquals("the flow ended with: ${collected.failureText}", null, collected.failure)
    }

    @Test
    fun `batch with every frame unreadable has no telemetry page`() {
        val events = statuses(PdfReportGenerator.generateBatch(2, { null }, ByteArrayOutputStream()))
        assertTrue(events.none { it.stage == Stage.TELEMETRY })
    }

    @Test
    fun `a throwing frame source is thrown to the collector`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                PdfReportGenerator.generateBatch(2, { error("unreadable .dat") }, ByteArrayOutputStream()).collect {}
            }
        }
        assertEquals("unreadable .dat", thrown.message)
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private val stats = EngineStats(
        totalPointsAttempted = 100,
        totalPointsSolved = 90,
        totalPointsRejected = 10,
        pathAPoints = 80,
        pathBPoints = 10,
        simplexCalls = 3,
        simplexSaved = 2,
        finalDeadPoints = 10,
        avgIcgnIterations = 1.5f,
        wallTimeMs = 1234.5f,
        seedingMs = 12.5f,
        hessianPrepassMs = 3.25f,
        delaunayMs = 7.5f,
        strainMs = 2.5f,
        avgThroughputPtsPerMs = 0.75f,
        convergencePercent = 87.5f,
    )

    private fun reportData() = ReportData(
        sessionId = "s",
        specimenName = "dogbone",
        analysisDate = "2026-10-01 12:00:00",
        subsetSize = 41,
        stepSize = 5,
        strainWindow = 15,
        strainMethod = "VSG",
        roi = Roi(0, 0, 10, 10),
        referenceImage = bitmap(),
        deformedImage = bitmap(),
        referenceImageName = "ref.png",
        deformedImageName = "def.png",
        fieldResults = listOf(
            FieldResult("U Displacement", "U", "px", 0f, 1f, 0.5f, "Simple Mean", 0.1f, 0, 0, 1, 1, bitmap()),
        ),
        engineStats = stats,
        znssdHeatmap = bitmap(),
        solverPathMap = bitmap(),
        globalAvgZnssd = 0.01f,
    )

    @Test
    fun `a frame whose pages fail to draw still has its images freed`() {
        // No native document here, so the cover page's startPage throws.
        val data = reportData()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { PdfReportGenerator.generateBatch(1, { data }, ByteArrayOutputStream()).collect {} }
        }

        val images = listOf(data.referenceImage, data.deformedImage, data.znssdHeatmap, data.solverPathMap) +
            data.fieldResults.map { it.bakedHeatmap }
        assertTrue("every page image recycled", images.all { it.isRecycled })
    }

    @Test
    fun `telemetry numbers read the same on a German phone`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val summary = TelemetrySummary.single(reportData())

            assertEquals(
                listOf("0.01000", "87.50 %", "1.50"),
                summary.qualityRows(stats).map { it[1] },
            )
            assertEquals(
                listOf("12.5 ms", "3.3 ms", "7.5 ms", "2.5 ms", "1234.5 ms", "0.75 pts/ms"),
                TelemetrySummary.timingRows(stats).map { it[1] },
            )
        } finally {
            Locale.setDefault(before)
        }
    }
}
