package com.indicvision.semper.data

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** How the log's rows were matched to the deformed frames; stored on the session. */
enum class LoadMapping {
    /** One row per deformed frame, in order. */
    ONE_TO_ONE,

    /** One row per frame plus a leading row for the unloaded reference, which was dropped. */
    ONE_TO_ONE_DROP_FIRST,

    /**
     * Video frames matched by time to a logged row no more than
     * [MachineLoadMapper.MATCH_TOLERANCE_MS] away; a frame with none has no load.
     */
    TIME_NEAREST,

    /** Rows linearly resampled onto the frames; reference ↔ first row, last frame ↔ last row. */
    RESAMPLED,
}

/** Something to tell the user about the match; informational, never blocking. */
enum class LoadMapWarning {
    FIRST_ROW_DROPPED,
    RESAMPLED,
    TIME_ALIGNED,

    /** Some frames had no log row within the tolerance and carry no load. */
    UNMATCHED_FRAMES,

    /** Every non-zero load has the sign the chosen test would not produce. */
    SIGN_UNEXPECTED,
}

/**
 * One load per deformed frame, in newtons, signed as logged. NaN marks a
 * frame the time match found no row for: it has no load and is left off the
 * curve, and every writer stores it as absent rather than as a number.
 */
data class MachineLoadTable(
    val loadsN: List<Float>,
    val mapping: LoadMapping,
    val warnings: List<LoadMapWarning>,
    val sourceRows: Int,
) {
    /** Frames that carry a load. */
    val matchedFrames: Int get() = loadsN.count { it.isFinite() }
}

/** The load of frame [index], or null when there is none (NaN: no log row matched it). */
fun List<Float>.loadOfFrame(index: Int): Float? = getOrNull(index)?.takeIf { it.isFinite() }

/** The load of frame [index], or null when there is none (NaN: no log row matched it). */
fun FloatArray.loadOfFrame(index: Int): Float? = getOrNull(index)?.takeIf { it.isFinite() }

/**
 * Matches a parsed load log to the deformed frames. Pure; re-run on the cached
 * [ParsedLoadCsv] whenever the frames change rather than re-reading the file.
 *
 * Loads are never made absolute: a log that is negative stays so, which is
 * what puts its stress–strain curve in the third quadrant.
 */
object MachineLoadMapper {

    private const val MS_PER_S = 1000.0

    /**
     * The furthest a log row may be from a frame, in time, and still give it
     * its load. Beyond this the load was not measured when the frame was taken.
     */
    const val MATCH_TOLERANCE_MS = 100.0

    /**
     * Log times are stored as Float, which at a few hundred seconds is only
     * good to about 0.03 ms; a row exactly 100 ms away must still match.
     */
    private const val FLOAT_SLACK_MS = 0.5

    /**
     * Whenever both sides carry times (a timed log and video frames) the
     * match is by time alone, even when the row count equals the frame count:
     * equal counts say nothing about which row was logged when a frame was
     * taken. Only an untimed log or a photo batch falls back to row order.
     *
     * @param frameTimesMs time of each deformed frame relative to the
     *   reference, index-aligned with the frames, or empty when unknown
     *   (image batches). Only video extraction knows these.
     * @param logStartS when the log's first row was taken, in seconds after
     *   the reference frame; negative when the log started first. Used only
     *   by the time match.
     */
    fun map(
        parsed: ParsedLoadCsv,
        frameCount: Int,
        frameTimesMs: List<Long>,
        testType: TestType,
        logStartS: Float = 0f,
    ): MachineLoadTable? {
        if (frameCount <= 0 || parsed.rows == 0) return null
        val warnings = mutableListOf<LoadMapWarning>()
        val rows = parsed.loadsN
        val table = when {
            parsed.timesS != null && frameTimesMs.size == frameCount -> {
                warnings += LoadMapWarning.TIME_ALIGNED
                val loads = matchInTime(rows, parsed.timesS, frameTimesMs, logStartS)
                if (loads.any { it.isNaN() }) warnings += LoadMapWarning.UNMATCHED_FRAMES
                MachineLoadTable(loads, LoadMapping.TIME_NEAREST, warnings, rows.size)
            }
            rows.size == frameCount -> MachineLoadTable(rows, LoadMapping.ONE_TO_ONE, warnings, rows.size)
            rows.size == frameCount + 1 && isUnloadedFirstRow(rows) -> {
                warnings += LoadMapWarning.FIRST_ROW_DROPPED
                MachineLoadTable(rows.drop(1), LoadMapping.ONE_TO_ONE_DROP_FIRST, warnings, rows.size)
            }
            else -> {
                warnings += LoadMapWarning.RESAMPLED
                MachineLoadTable(resample(rows, frameCount), LoadMapping.RESAMPLED, warnings, rows.size)
            }
        }
        if (signUnexpected(table.loadsN, testType)) warnings += LoadMapWarning.SIGN_UNEXPECTED
        return table
    }

    /** The extra leading row is the reference only if nothing in the log is closer to zero. */
    private fun isUnloadedFirstRow(rows: List<Float>): Boolean {
        val first = abs(rows.first())
        return rows.all { abs(it) >= first }
    }

    /**
     * The log's first row is taken [logStartS] after the reference frame, so
     * row `i` is at `(timesS[i] - t0) + logStartS` seconds after the
     * reference. Each frame takes the nearest row (the earlier on a tie) when
     * it is no more than [MATCH_TOLERANCE_MS] away, and NaN otherwise: a
     * frame before the log began, after it ended, or between sparse rows.
     * Times are non-decreasing (the parser drops any that are not), so a
     * binary search finds the neighbours.
     */
    private fun matchInTime(
        rows: List<Float>,
        timesS: List<Float>,
        frameTimesMs: List<Long>,
        logStartS: Float,
    ): List<Float> {
        val t0 = timesS.first().toDouble()
        val rowMs = DoubleArray(timesS.size) { (timesS[it] - t0 + logStartS) * MS_PER_S }
        return frameTimesMs.map { frameMs ->
            val at = nearestRow(rowMs, frameMs.toDouble())
            if (abs(rowMs[at] - frameMs) <= MATCH_TOLERANCE_MS + FLOAT_SLACK_MS) rows[at] else Float.NaN
        }
    }

    private fun nearestRow(rowMs: DoubleArray, targetMs: Double): Int {
        val index = rowMs.asList().binarySearch(targetMs)
        if (index >= 0) return index
        val after = -(index + 1)
        val before = after - 1
        return when {
            before < 0 -> after
            after >= rowMs.size -> before
            targetMs - rowMs[before] <= rowMs[after] - targetMs -> before
            else -> after
        }
    }

    /**
     * Reference ↔ row 0 and the last frame ↔ the last row, with the frames
     * spaced evenly between: deformed frame `i` (0-based, of `n`) sits at row
     * position `(i + 1) · (rows − 1) / n`, linearly interpolated.
     */
    private fun resample(rows: List<Float>, frameCount: Int): List<Float> {
        if (rows.size == 1) return List(frameCount) { rows.single() }
        val last = rows.size - 1
        return List(frameCount) { i ->
            val p = (i + 1).toDouble() * last / frameCount
            val lo = floor(p).toInt().coerceIn(0, last)
            val hi = ceil(p).toInt().coerceIn(0, last)
            val frac = (p - lo).toFloat()
            rows[lo] + (rows[hi] - rows[lo]) * frac
        }
    }

    private fun signUnexpected(loads: List<Float>, testType: TestType): Boolean {
        val nonZero = loads.filter { it.isFinite() && it != 0f }
        if (nonZero.isEmpty()) return false
        return when (testType) {
            TestType.TENSILE -> nonZero.all { it < 0f }
            TestType.BENDING -> false
        }
    }
}
