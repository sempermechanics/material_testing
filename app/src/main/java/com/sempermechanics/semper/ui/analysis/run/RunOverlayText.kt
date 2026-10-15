package com.sempermechanics.semper.ui.analysis.run

import android.content.res.Resources
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner.NodeOutcome
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import java.text.NumberFormat
import java.util.Locale

/** What the run overlay says for a batch run's or a sweep's tick, and once a run has solved. */
object RunOverlayText {

    private const val MS_PER_SECOND = 1000
    private const val SECONDS_PER_MINUTE = 60

    /** "57.0%": the header's one-decimal percentage. */
    fun percent(value: Float): String = String.format(Locale.US, "%.1f%%", value)

    /**
     * The line under the header: the run's [reference] name without its
     * extension, then [eta]: "steel_00 · About 2 s left". Just [eta] with no
     * reference (an import, a sweep).
     */
    fun subline(res: Resources, reference: String?, eta: String): String {
        if (reference.isNullOrBlank()) return eta
        return res.getString(R.string.run_subline_fmt, reference.substringBeforeLast('.').ifBlank { reference }, eta)
    }

    /**
     * The header: the engine's line while the reference is cached, "Frame 23
     * of 40" while frames are solved, then completed while the run saves.
     */
    fun header(res: Resources, tick: BatchProgressUpdate): String {
        val planned = tick.plannedFrames
        return when {
            planned > 0 && tick.frameIndex >= planned ->
                res.getQuantityString(R.plurals.run_completed_fmt, planned, planned, planned)
            tick.frameIndex < 0 || planned <= 0 -> tick.status
            else -> res.getString(R.string.run_frame_of_fmt, tick.frameIndex + 1, planned)
        }
    }

    /**
     * A sweep's header: "Analysis 7 of 20", then, once every combination has
     * an outcome, how many solved while it saves.
     */
    fun sweepHeader(res: Resources, tick: SweepStudyRunner.Progress): String {
        if (sweepEnded(tick)) {
            val solved = tick.outcomes.count { it == NodeOutcome.SOLVED }
            return res.getQuantityString(R.plurals.sweep_completed_fmt, tick.totalRuns, solved, tick.totalRuns)
        }
        return res.getString(R.string.run_analysis_of_fmt, minOf(tick.runIndex + 1, tick.totalRuns), tick.totalRuns)
    }

    /**
     * The line under the sweep lattice: the combination being solved, "Subset
     * 25 · window 41 px" (the window as its VSG), or null once all have ended.
     */
    fun sweepStatus(res: Resources, tick: SweepStudyRunner.Progress): String? {
        if (sweepEnded(tick)) return null
        return res.getString(R.string.run_sweep_now_fmt, tick.point.subset, tick.point.vsg)
    }

    /** How the last combination to end went: its words, and whether it was skipped. */
    data class SweepLast(val text: String, val skipped: Boolean)

    /**
     * The second line under the sweep lattice, for the latest combination in
     * [tick] that has ended: "Last: 96% converged · 30,968 points" from
     * [solved], the tick that ended it when the overlay saw one, "Last:
     * skipped", or null before any has ended. A solved combination whose own
     * tick was dropped says only that it solved.
     */
    fun sweepLast(res: Resources, tick: SweepStudyRunner.Progress, solved: SweepStudyRunner.Progress?): SweepLast? {
        val last = tick.outcomes.indexOfLast { it != NodeOutcome.PENDING }
        return when {
            last < 0 -> null
            tick.outcomes[last] == NodeOutcome.SKIPPED ->
                SweepLast(res.getString(R.string.run_sweep_last_skipped), skipped = true)
            solved != null && solved.runIndex == last -> SweepLast(
                res.getQuantityString(
                    R.plurals.run_sweep_last_fmt,
                    solved.pointsSolved,
                    solved.convergencePercent.coerceAtLeast(0f).toInt(),
                    NumberFormat.getIntegerInstance().format(solved.pointsSolved),
                ),
                skipped = false,
            )
            else -> SweepLast(res.getString(R.string.run_sweep_last_solved), skipped = false)
        }
    }

    /** The one-strike warning, or null when no frame leaves the run one low frame from stopping. */
    fun strikeWarning(res: Resources, values: FloatArray): String? =
        ConvergenceTrace.pendingStrike(values)?.let {
            res.getString(R.string.run_strike_warn_fmt, it.frameNumber, it.convergencePercent)
        }

    /** The header once a run has solved: "5 frames solved". */
    fun doneHeader(res: Resources, frames: Int): String =
        res.getQuantityString(R.plurals.run_done_frames_fmt, frames, frames)

    /**
     * The line under it: how long the run took, "7 s" or "2 min 5 s", and the
     * lowest of [convergence] when any frame has a value ("7 s · lowest 94%
     * converged"), truncated as the bins label it.
     */
    fun doneDetail(res: Resources, executionTimeMs: Int, convergence: FloatArray?): String {
        val seconds = (executionTimeMs.coerceAtLeast(0) + MS_PER_SECOND / 2) / MS_PER_SECOND
        val time = if (seconds < SECONDS_PER_MINUTE) {
            res.getString(R.string.run_done_seconds_fmt, seconds)
        } else {
            res.getString(R.string.run_done_minutes_fmt, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
        }
        val lowest = convergence?.filter { !it.isNaN() && it >= 0f }?.minOrNull() ?: return time
        return res.getString(R.string.run_done_detail_fmt, time, lowest.toInt())
    }

    private fun sweepEnded(tick: SweepStudyRunner.Progress): Boolean =
        tick.outcomes.isNotEmpty() && NodeOutcome.PENDING !in tick.outcomes
}
