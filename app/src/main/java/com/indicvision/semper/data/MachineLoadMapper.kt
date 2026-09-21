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

    /** Video frames matched to the nearest logged time, assuming both started together. */
    TIME_NEAREST,

    /** Rows linearly resampled onto the frames; reference ↔ first row, last frame ↔ last row. */
    RESAMPLED,
}

/** Something to tell the user about the match; informational, never blocking. */
enum class LoadMapWarning {
    FIRST_ROW_DROPPED,
    RESAMPLED,
    TIME_ALIGNED,

    /** Every non-zero load has the sign the chosen test would not produce. */
    SIGN_UNEXPECTED,
}

/** One load per deformed frame, in newtons, signed as logged. */
data class MachineLoadTable(
    val loadsN: List<Float>,
    val mapping: LoadMapping,
    val warnings: List<LoadMapWarning>,
    val sourceRows: Int,
)

/**
 * Matches a parsed load log to the deformed frames. Pure; re-run on the cached
 * [ParsedLoadCsv] whenever the frames change rather than re-reading the file.
 *
 * Loads are never made absolute: a compression log is negative and stays so,
 * which is what puts its stress–strain curve in the third quadrant.
 */
object MachineLoadMapper {

    private const val MS_PER_S = 1000f

    /**
     * @param frameTimesMs time of each deformed frame relative to the
     *   reference, index-aligned with the frames, or empty when unknown
     *   (image batches). Only video extraction knows these.
     */
    fun map(
        parsed: ParsedLoadCsv,
        frameCount: Int,
        frameTimesMs: List<Long>,
        testType: TestType,
    ): MachineLoadTable? {
        if (frameCount <= 0 || parsed.rows == 0) return null
        val warnings = mutableListOf<LoadMapWarning>()
        val rows = parsed.loadsN
        val table = when {
            rows.size == frameCount -> MachineLoadTable(rows, LoadMapping.ONE_TO_ONE, warnings, rows.size)
            rows.size == frameCount + 1 && isUnloadedFirstRow(rows) -> {
                warnings += LoadMapWarning.FIRST_ROW_DROPPED
                MachineLoadTable(rows.drop(1), LoadMapping.ONE_TO_ONE_DROP_FIRST, warnings, rows.size)
            }
            parsed.timesS != null && frameTimesMs.size == frameCount -> {
                warnings += LoadMapWarning.TIME_ALIGNED
                MachineLoadTable(
                    nearestInTime(rows, parsed.timesS, frameTimesMs),
                    LoadMapping.TIME_NEAREST,
                    warnings,
                    rows.size,
                )
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
     * The log's clock is taken to start at the reference frame, so frame time
     * `t` after the reference is log time `t0 + t`. Times are non-decreasing
     * (the parser drops any that are not), so a binary search finds the
     * nearest row.
     */
    private fun nearestInTime(rows: List<Float>, timesS: List<Float>, frameTimesMs: List<Long>): List<Float> {
        val t0 = timesS.first()
        return frameTimesMs.map { frameMs ->
            val target = t0 + frameMs / MS_PER_S
            val index = timesS.binarySearch(target)
            val at = if (index >= 0) {
                index
            } else {
                val after = -(index + 1)
                val before = after - 1
                when {
                    before < 0 -> after
                    after >= timesS.size -> before
                    target - timesS[before] <= timesS[after] - target -> before
                    else -> after
                }
            }
            rows[at]
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
        val nonZero = loads.filter { it != 0f }
        if (nonZero.isEmpty()) return false
        return when (testType) {
            TestType.TENSILE -> nonZero.all { it < 0f }
            TestType.COMPRESSION -> nonZero.all { it > 0f }
            TestType.BENDING, TestType.TORSION -> false
        }
    }
}
