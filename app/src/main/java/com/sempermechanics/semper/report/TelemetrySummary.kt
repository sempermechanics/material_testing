package com.sempermechanics.semper.report

import com.sempermechanics.semper.field.DicResult
import java.util.Locale

/** One frame's accepted-point ZNSSD: the mean, and how many points it is over. */
internal data class ZnssdFrame(val mean: Float, val points: Int) {
    companion object {
        /** Mean ZNSSD over [data]'s accepted points (0 when none were accepted). */
        fun of(data: FloatArray): ZnssdFrame {
            var total = 0f
            var count = 0
            for (i in data.indices step DicResult.STRIDE) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (DicResult.isAcceptedPoint(corr)) {
                    total += corr
                    count++
                }
            }
            return ZnssdFrame(if (count > 0) total / count else 0f, count)
        }
    }
}

/**
 * What the telemetry page's quality rows say, and over what.
 *
 * The engine statistics a session keeps (pipeline counts, convergence, ICGN
 * iterations, timings) are recorded on the run's first frame only, so an
 * all-frames report can aggregate the ZNSSD it decodes per frame but must say
 * whose the rest are, rather than present one frame's numbers as the batch's.
 */
internal data class TelemetrySummary(
    val avgZnssdLabel: String,
    val avgZnssd: Float,
    val convergenceLabel: String,
    /** A note above the tables when their numbers are not all the document's; null when they are. */
    val scopeNote: String?,
) {
    /**
     * The telemetry page's quality table. Formatted in [Locale.US], like every
     * other number in the report: the default locale printed "12,5" on a German
     * phone beside "0.00123" from [ReportBuilder.formatMetric].
     */
    fun qualityRows(stats: EngineStats): List<List<String>> = listOf(
        listOf(avgZnssdLabel, "%.5f".format(Locale.US, avgZnssd)),
        listOf(convergenceLabel, "%.2f %%".format(Locale.US, stats.convergencePercent)),
        listOf("Solver iterations per point (avg)", "%.2f".format(Locale.US, stats.avgIcgnIterations)),
    )

    companion object {
        /**
         * The last page's title and what it is for. Its rows are named in the
         * user's terms (readiness AE1-010); the engine's names for the same
         * steps are in docs/engine.
         */
        const val TITLE = "Engine details"
        const val INTRO = "How the solver reached these results, for support and method checks."

        /** The page's four section headings, in order. */
        val SECTIONS = listOf("1. Points solved", "2. Match quality", "3. Recovery attempts", "4. Time taken")

        /** How many grid points were tried, and how each was solved or not. */
        fun pipelineRows(stats: EngineStats): List<List<String>> = listOf(
            listOf("Starting points", stats.meshSeedingLabel()),
            listOf("Grid points tried", "${stats.totalPointsAttempted}"),
            listOf("Solved from the starting mesh", "${stats.pathAPoints}"),
            listOf("Solved by growing from neighbours", "${stats.pathBPoints}"),
            listOf("Not solved", "${stats.totalPointsRejected}"),
        )

        /** Points the main solve missed that a second, slower method was tried on. */
        fun rescueRows(stats: EngineStats): List<List<String>> = listOf(
            listOf("Second-method solves (Simplex)", "${stats.simplexCalls}", "${stats.simplexSaved}"),
        )

        /** The telemetry page's wall-time table, in [Locale.US] (see [qualityRows]). */
        fun timingRows(stats: EngineStats): List<List<String>> = listOf(
            listOf("Finding starting points", "%.1f ms".format(Locale.US, stats.seedingMs)),
            listOf("Preparing image gradients", "%.1f ms".format(Locale.US, stats.hessianPrepassMs)),
            listOf("Building the starting mesh", "%.1f ms".format(Locale.US, stats.delaunayMs)),
            listOf("Computing strain", "%.1f ms".format(Locale.US, stats.strainMs)),
            listOf("Total time", "%.1f ms".format(Locale.US, stats.wallTimeMs)),
            listOf("Solving speed (points per ms)", "%.2f pts/ms".format(Locale.US, stats.avgThroughputPtsPerMs)),
        )

        /** A single-frame report: every number is that frame's analysis. */
        fun single(data: ReportData) = TelemetrySummary(
            avgZnssdLabel = "Average match error (ZNSSD, lower is better)",
            avgZnssd = data.globalAvgZnssd,
            convergenceLabel = "Points that converged",
            scopeNote = null,
        )

        /**
         * An all-frames report over the readable [frames]: the ZNSSD is pooled
         * over every accepted point of every frame, so a frame with more points
         * weighs more, exactly as if the batch were one field.
         */
        fun batch(frames: List<ZnssdFrame>): TelemetrySummary {
            val points = frames.sumOf { it.points.toLong() }
            val pooled = if (points > 0) {
                (frames.sumOf { it.mean.toDouble() * it.points } / points).toFloat()
            } else {
                0f
            }
            val n = frames.size
            return TelemetrySummary(
                avgZnssdLabel = if (n == 1) {
                    "Average match error (ZNSSD), 1 frame"
                } else {
                    "Average match error (ZNSSD), all $n frames"
                },
                avgZnssd = pooled,
                convergenceLabel = "Points that converged (first frame)",
                scopeNote = "The average match error pools every accepted point of the $n " +
                    (if (n == 1) "frame" else "frames") +
                    " in this report. The point counts, convergence, solver iterations, " +
                    "recovery attempts and times below were recorded on the run's first frame.",
            )
        }
    }
}
