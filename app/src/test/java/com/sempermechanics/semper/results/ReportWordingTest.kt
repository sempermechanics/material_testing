package com.sempermechanics.semper.results

import com.sempermechanics.semper.report.EMPTY
import com.sempermechanics.semper.report.EngineStats
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.report.TelemetrySummary
import com.sempermechanics.semper.report.ZnssdFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The PDF names itself once and its last page speaks in user terms
 * (readiness AE1-010): the footer said "Semper Metrology Report", the field
 * images "Semper Analysis Report" and the cover "Master DIC Analysis Report",
 * and the telemetry rows were engine internals ("Hessian Pre-Pass",
 * "TOTAL WALL TIME", "Average ICGN Iterations").
 */
class ReportWordingTest {

    private val stats = EngineStats.EMPTY

    /** Every label the telemetry page prints in its first column, single and all-frames. */
    private val labels: List<String> by lazy {
        val single = TelemetrySummary(
            avgZnssdLabel = "x",
            avgZnssd = 0f,
            convergenceLabel = "x",
            scopeNote = null,
        )
        val batch = TelemetrySummary.batch(listOf(ZnssdFrame(0.01f, 1), ZnssdFrame(0.02f, 1)))
        (
            TelemetrySummary.pipelineRows(stats) + single.qualityRows(stats) + batch.qualityRows(stats) +
                TelemetrySummary.rescueRows(stats) + TelemetrySummary.timingRows(stats)
            ).map { it.first() } + listOfNotNull(batch.scopeNote) + TelemetrySummary.SECTIONS
    }

    @Test
    fun `the telemetry page names no engine internals`() {
        val jargon = Regex("""ICGN|Hessian|Delaunay|RGDIC|Path [ABC]|WALL TIME|Pipeline|Phase \d|Dead Points""")
        assertEquals(emptyList<String>(), labels.filter { jargon.containsMatchIn(it) })
    }

    @Test
    fun `the iteration row says what it counts`() {
        assertTrue(labels.toString(), "Solver iterations per point (avg)" in labels)
    }

    @Test
    fun `the report has one name`() {
        // The product name is lower-case "semper", the report name sentence case (readiness ADDENDUM-4).
        assertEquals("semper analysis report", ReportBuilder.REPORT_NAME)
        val sources = File("src/main/java/com/sempermechanics/semper").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        val other = Regex(
            """"[^"\n]*(Metrology Report|Master DIC|DIC Analysis Report|semper analysis report)""",
            RegexOption.IGNORE_CASE,
        )
        val offenders = sources.filter { other.containsMatchIn(it.readText()) }.map { it.name }.toList()
        // The name is spelled once, as ReportBuilder.REPORT_NAME.
        assertEquals(listOf("ReportBuilder.kt"), offenders)
    }
}
