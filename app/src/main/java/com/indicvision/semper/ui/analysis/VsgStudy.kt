package com.indicvision.semper.ui.analysis

import com.indicvision.semper.DicResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Parameter sweep for a virtual strain gauge study — §5.4.5 of *A Good
 * Practices Guide for Digital Image Correlation* (iDICs, 2018).
 *
 * Strain is a derived quantity, so its spatial resolution is set by the
 * *virtual strain gauge*: the footprint the strain calculation averages over.
 * Too large a VSG over-smooths and biases the peak strain low; too small a VSG
 * lets displacement noise through. The guide's remedy is to re-analyse the same
 * image pair over a ladder of VSG sizes and compare the strain along a line cut
 * through the specimen — as the VSG shrinks the peak strain rises, and once it
 * stops rising the real amplitude has been resolved.
 *
 * ## Sweep space
 *
 * The three dominant variables are subset size, step size and strain window
 * (guide, Tip 5.4). The user gives a subset *range* and a ceiling on the VSG;
 * this object derives the rest:
 *
 *  - subset sizes are the odd values across the requested range;
 *  - step size is an integer between 1/6 and 1/3 of each subset, the usual
 *    overlap band — below 1/6 neighbouring subsets are so redundant that the
 *    extra runtime buys nothing, above 1/3 the field is under-sampled.
 *    The sweep asks for that fraction as `subset ÷ N`; single analysis asks
 *    for a pixel step plus the linked overlap `1 − step/subset`;
 *  - the strain window then follows from the VSG relation below.
 *  - the strain window then follows from the VSG relation below.
 *
 * Every surviving combination is solved in its own right and lands in the
 * result viewer as its own frame, so the comparison is made on the real fields
 * rather than on a summary statistic.
 */
@Suppress("TooManyFunctions") // planning and line-cut extraction belong together
object VsgStudy {

    /**
     * VSG footprint of a (step, strain window) pair, in pixels.
     *
     * Note this is the form requested for this app — the guide's own expression
     * carries the subset size rather than the 1 px here, i.e.
     * `(strainWindow - 1) * step + subset`, which reports a footprint larger by
     * `subset - 1`. Both orderings of the sweep are identical; only the printed
     * number differs. Everything downstream goes through this one function.
     */
    fun vsgFor(step: Int, strainWindow: Int): Int = (strainWindow - 1) * step + 1

    /** Strain window is odd and matches the range of the settings slider. */
    const val MIN_STRAIN_WINDOW = 5
    const val MAX_STRAIN_WINDOW = 101

    /** Step size range of the settings slider. */
    const val MIN_STEP = 1
    const val MAX_STEP = 30

    /** Subset sizes are odd, so consecutive sweep values differ by this much. */
    const val SUBSET_INCREMENT = 2

    /**
     * Defensive ceiling on how wide a subset range the planner will enumerate.
     * The per-axis sample counts bound the real cost of a sweep, so this only
     * guards against a pathological range; the UI never offers subsets outside
     * 15..101 anyway.
     */
    const val MAX_SUBSET_SPAN = 200

    /**
     * Step fraction for a sweep. One denominator for every subset:
     * `step = round(subset / N)`. The user picks N — `2` (half the subset,
     * the coarsest) through `9` (the finest).
     */
    const val STEP_DENOM_MIN = 2
    const val STEP_DENOM_MAX = 9
    const val DEFAULT_STEP_DENOM = 3

    /**
     * Overlap after a step stride: `1 − step/subset`. Single analysis links
     * pixel step to this ratio. The iDICs guide keeps this at least half
     * (step ≤ subset/2) and strictly below 1.0 (step ≥ 1 px). Typical
     * practice is about 0.50–0.75.
     */
    const val MIN_OVERLAP = 0.5

    /** Inclusive slider ceiling; overlap never reaches 1.0 because step ≥ 1. */
    const val MAX_OVERLAP = 0.99
    const val DEFAULT_OVERLAP = 0.8

    /** Samples the user may take along each axis (subset, VSG). */
    const val MIN_SAMPLES = 1
    const val MAX_SAMPLES = 8
    const val DEFAULT_SUBSET_SAMPLES = 3
    const val DEFAULT_VSG_SAMPLES = 3

    /** Default VSG ceiling, as a multiple of the subset size. */
    const val DEFAULT_VSG_MAX_FACTOR = 3

    /** Strain components a sweep reports, as indices into a `.dat` point. */
    val STRAIN_COMPONENTS = listOf(DicResult.IDX_EXX, DicResult.IDX_EYY, DicResult.IDX_EXY)

    // ------------------------------------------------------------------
    // Sweep planning
    //
    // The sweep is a grid: the user picks how many subset sizes (x) and VSG
    // sizes (y) to sample, plus one step fraction shared by the whole sweep.
    // The number of analyses is x · y — one full solve per grid cell — set
    // explicitly rather than through a single opaque "runs" budget.
    // ------------------------------------------------------------------

    /** One analysis of the sweep: a full set of engine parameters. */
    data class Point(
        val subset: Int,
        val step: Int,
        val strainWindow: Int,
    ) {
        /** Footprint the strain calculation averages over, in pixels. */
        val vsg: Int get() = vsgFor(step, strainWindow)
    }

    /** The single step size for [subset] at the chosen [denominator]: `subset/N`, in pixels. */
    fun stepSizeFor(subset: Int, denominator: Int): Int {
        val d = denominator.coerceIn(STEP_DENOM_MIN, STEP_DENOM_MAX)
        return (subset.toDouble() / d).roundToInt().coerceIn(MIN_STEP, MAX_STEP)
    }

    /** Largest step that still keeps overlap ≥ [MIN_OVERLAP] and within [MAX_STEP]. */
    fun maxStepFor(subset: Int): Int {
        val half = (subset * (1.0 - MIN_OVERLAP)).toInt().coerceAtLeast(MIN_STEP)
        return minOf(MAX_STEP, half)
    }

    fun clampOverlap(overlap: Double): Double = overlap.coerceIn(MIN_OVERLAP, MAX_OVERLAP)

    /** Overlap implied by [step] on [subset], clamped into the good-practice band. */
    fun overlapFor(subset: Int, step: Int): Double {
        if (subset <= 0) return DEFAULT_OVERLAP
        val s = step.coerceIn(MIN_STEP, maxStepFor(subset))
        return clampOverlap(1.0 - s.toDouble() / subset)
    }

    /** Overlap implied by sweep denominator N: `1 − 1/N`. */
    fun overlapForDenominator(denominator: Int): Double {
        val d = denominator.coerceIn(STEP_DENOM_MIN, STEP_DENOM_MAX)
        return clampOverlap(1.0 - 1.0 / d)
    }

    /** Sweep denominator nearest [overlap]: `round(1 / (1 − overlap))`. */
    fun denominatorForOverlap(overlap: Double): Int {
        val o = clampOverlap(overlap)
        val denom = (1.0 / (1.0 - o)).roundToInt()
        return denom.coerceIn(STEP_DENOM_MIN, STEP_DENOM_MAX)
    }

    /** Pixel step for [subset] at the chosen [overlap]. */
    fun stepSizeFor(subset: Int, overlap: Double): Int {
        val o = clampOverlap(overlap)
        return (subset * (1.0 - o)).roundToInt().coerceIn(MIN_STEP, maxStepFor(subset))
    }

    /** The strain window whose VSG is nearest [vsg] at [step]; odd and in range. */
    fun windowForVsg(step: Int, vsg: Int): Int {
        val raw = ((vsg - 1).toDouble() / step).roundToInt() + 1
        return raw.coerceIn(MIN_STRAIN_WINDOW, MAX_STRAIN_WINDOW) or 1
    }

    /** Smallest VSG reachable for [subset]: its finest step, narrowest window. */
    fun minVsg(subset: Int): Int =
        vsgFor(stepSizeFor(subset, STEP_DENOM_MAX), MIN_STRAIN_WINDOW)

    /** Largest VSG reachable for [subset]: its coarsest step, widest window. */
    fun maxVsg(subset: Int): Int =
        vsgFor(stepSizeFor(subset, STEP_DENOM_MIN), MAX_STRAIN_WINDOW)

    /** Default VSG ceiling offered for [subset], inside the reachable range. */
    fun defaultVsgMax(subset: Int): Int =
        (DEFAULT_VSG_MAX_FACTOR * subset).coerceIn(minVsg(subset), maxVsg(subset))

    /**
     * The odd subset sizes between [subsetMin] and [subsetMax] inclusive. Both
     * ends are snapped odd — the engine only accepts odd subsets — and the span
     * is bounded by [MAX_SUBSET_SPAN] as a safety net.
     */
    fun subsetSizes(subsetMin: Int, subsetMax: Int): List<Int> {
        val low = subsetMin.coerceAtLeast(1) or 1
        val high = (maxOf(subsetMax, low) or 1).coerceAtMost(low + MAX_SUBSET_SPAN)
        return (low..high step SUBSET_INCREMENT).toList()
    }

    /** [count] subset sizes sampled evenly across `[subsetMin, subsetMax]`. */
    fun sampledSubsets(subsetMin: Int, subsetMax: Int, count: Int): List<Int> =
        sampleEvenly(subsetSizes(subsetMin, subsetMax), count)

    /**
     * The odd strain windows between [winMin] and [winMax] inclusive, clamped to
     * what the engine accepts. The subset's twin: both ends snapped odd, both
     * ends the user's, so the axis says what it sweeps.
     */
    fun strainWindows(winMin: Int, winMax: Int): List<Int> {
        val low = winMin.coerceIn(MIN_STRAIN_WINDOW, MAX_STRAIN_WINDOW) or 1
        val high = (maxOf(winMax, low) or 1).coerceAtMost(MAX_STRAIN_WINDOW)
        return (low..high step SUBSET_INCREMENT).toList()
    }

    /** [count] strain windows sampled evenly across `[winMin, winMax]`. */
    fun sampledWindows(winMin: Int, winMax: Int, count: Int): List<Int> =
        sampleEvenly(strainWindows(winMin, winMax), count)

    /**
     * The sweep grid: for each sampled subset (x) and each of [strainWinSamples]
     * strain windows (y) sampled across `[strainWinMin, strainWinMax]`, one
     * analysis at the fixed step size `subset/[stepDenominator]`. Deduplicated,
     * ordered by subset then VSG — the order the frames are solved and scrubbed
     * through in.
     */
    @Suppress("LongParameterList") // the sweep's independent axes
    fun plan(
        subsetMin: Int,
        subsetMax: Int,
        subsetSamples: Int,
        strainWinMin: Int,
        strainWinMax: Int,
        strainWinSamples: Int,
        stepDenominator: Int,
    ): List<Point> {
        val windows = sampledWindows(strainWinMin, strainWinMax, strainWinSamples)
        val out = LinkedHashSet<Point>()
        for (subset in sampledSubsets(subsetMin, subsetMax, subsetSamples)) {
            val step = stepSizeFor(subset, stepDenominator)
            for (window in windows) {
                out.add(Point(subset, step, window))
            }
        }
        return out.sortedWith(compareBy({ it.subset }, { it.vsg }, { it.step }))
    }

    /** Same grid as [plan], with step taken from [overlap] via [denominatorForOverlap]. */
    @Suppress("LongParameterList") // overlap overload of the sweep planner
    fun plan(
        subsetMin: Int,
        subsetMax: Int,
        subsetSamples: Int,
        strainWinMin: Int,
        strainWinMax: Int,
        strainWinSamples: Int,
        overlap: Double,
    ): List<Point> = plan(
        subsetMin,
        subsetMax,
        subsetSamples,
        strainWinMin,
        strainWinMax,
        strainWinSamples,
        denominatorForOverlap(overlap),
    )

    /** [count] items of [items], evenly spaced, both ends included. */
    private fun <T> sampleEvenly(items: List<T>, count: Int): List<T> = when {
        items.isEmpty() || count <= 0 -> emptyList()
        items.size <= count -> items
        count == 1 -> listOf(items[items.size / 2])
        else -> (0 until count).map { items[it * (items.size - 1) / (count - 1)] }.distinct()
    }

    // ------------------------------------------------------------------
    // Line cut
    // ------------------------------------------------------------------

    /**
     * The line strain is read along: a cut through the centre of the region of
     * interest, running along the axis the user picked.
     *
     * A centre cut is deliberately fixed rather than hunted for. It is the same
     * physical line for every combination in the sweep, so the strain profiles
     * of different VSG sizes lie on top of each other and can be compared
     * directly — which is the whole point of guide step 4.
     *
     * @param horizontal true for a cut along x at constant y, false for a cut
     *   along y at constant x
     * @param position the constant coordinate: y when [horizontal], else x
     */
    data class StudyLine(
        val horizontal: Boolean,
        val position: Float,
    )

    /** The centre cut of the ROI `[roiX, roiY, roiW, roiH]` along the given axis. */
    fun centreLine(roiX: Int, roiY: Int, roiW: Int, roiH: Int, horizontal: Boolean): StudyLine =
        StudyLine(
            horizontal = horizontal,
            position = if (horizontal) roiY + roiH / 2f else roiX + roiW / 2f,
        )

    private fun isAccepted(data: FloatArray, i: Int): Boolean =
        DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])

    /** Largest |value| of [component] over accepted points, in millistrain. */
    fun fieldPeak(data: FloatArray, component: Int): Float {
        var peak = 0f
        var i = 0
        while (i < data.size) {
            if (isAccepted(data, i)) {
                val v = abs(data[i + component]) * DicResult.STRAIN_TO_MILLISTRAIN
                if (v > peak) peak = v
            }
            i += DicResult.STRIDE
        }
        return peak
    }

    /**
     * Strain profile along [line], as (distance-along-line, millistrain) pairs
     * sorted by distance. [tolerance] admits the grid row/column nearest the
     * line — half a step keeps exactly one row of points for any step size.
     */
    fun profileAlong(
        data: FloatArray,
        component: Int,
        line: StudyLine,
        tolerance: Float,
    ): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        var i = 0
        while (i < data.size) {
            pointOnLine(data, i, component, line, tolerance)?.let(out::add)
            i += DicResult.STRIDE
        }
        return out.sortedBy { it.first }
    }

    /**
     * Profiles several strain [components] along [line] in a single pass, instead of
     * one full array walk per component. The accept test and the along/across geometry
     * are computed once per point and shared, so this is N× less work for N components.
     * Each returned list is identical (same points, same ascending-distance order) to
     * the single-component [profileAlong] for that component.
     */
    fun profileAlong(
        data: FloatArray,
        components: IntArray,
        line: StudyLine,
        tolerance: Float,
    ): Map<Int, List<Pair<Float, Float>>> {
        val out = LinkedHashMap<Int, ArrayList<Pair<Float, Float>>>(components.size)
        for (c in components) out[c] = ArrayList()
        var i = 0
        while (i < data.size) {
            val along = alongIfOnLine(data, i, line, tolerance)
            if (along != null) {
                for (c in components) {
                    out.getValue(c).add(along to data[i + c] * DicResult.STRAIN_TO_MILLISTRAIN)
                }
            }
            i += DicResult.STRIDE
        }
        return out.mapValues { (_, points) -> points.sortedBy { it.first } }
    }

    /** Distance along [line] for accepted point [i], or null when it's off the line. */
    @Suppress("ReturnCount")
    private fun alongIfOnLine(data: FloatArray, i: Int, line: StudyLine, tolerance: Float): Float? {
        if (!isAccepted(data, i)) return null
        val x = data[i + DicResult.IDX_X]
        val y = data[i + DicResult.IDX_Y]
        val across = if (line.horizontal) y else x
        if (abs(across - line.position) > tolerance) return null
        return if (line.horizontal) x else y
    }

    /** The (position, millistrain) pair at point [i], or null when off the line. */
    @Suppress("ReturnCount")
    private fun pointOnLine(
        data: FloatArray,
        i: Int,
        component: Int,
        line: StudyLine,
        tolerance: Float,
    ): Pair<Float, Float>? {
        if (!isAccepted(data, i)) return null
        val x = data[i + DicResult.IDX_X]
        val y = data[i + DicResult.IDX_Y]
        val across = if (line.horizontal) y else x
        if (abs(across - line.position) > tolerance) return null
        val along = if (line.horizontal) x else y
        return along to data[i + component] * DicResult.STRAIN_TO_MILLISTRAIN
    }

    /** Max |strain| along [line]; falls back to the field peak on an empty cut. */
    fun linePeak(data: FloatArray, component: Int, line: StudyLine, tolerance: Float): Float {
        val profile = profileAlong(data, component, line, tolerance)
        if (profile.isEmpty()) return fieldPeak(data, component)
        return profile.maxOf { abs(it.second) }
    }
}
