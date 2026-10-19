package com.sempermechanics.semper.report

import kotlin.math.abs

/**
 * Young's modulus E from a stress–strain [StressStrain.Curve]: the slope of
 * the straight, early part of the curve. Stress is in MPa and strain in mε,
 * so the slope is E in GPa with no conversion.
 *
 * Two passes. First, the longest *leading* run of solved frames, up to the
 * peak, whose least-squares line keeps R² at or above [MIN_R2] — the frames a
 * student would rule a line through on graph paper. Every run length is
 * tried, not only until the first one fails: the first few frames often span
 * only a few MPa, where camera noise alone drops R² (real steel reads 0.91 on
 * its first three frames and 0.9955 on its first 26 — see
 * docs/app/REAL_WORLD_VALIDATION.md). On a metal that yields gradually that
 * run reaches into the knee and reads low (185 GPa on a 200 GPa
 * Ramberg–Osgood curve), so, second, as in ISO 6892-1 Annex G, the line is
 * fitted again over the frames between [WINDOW_LOW] and [WINDOW_HIGH] of the
 * Rp0.2 the first line gives ([YieldStrength]). The refit is kept only with
 * [MIN_POINTS] frames and R² ≥ [MIN_R2] in that window; otherwise, and on a
 * curve with no Rp0.2 (it never leaves the line by 0.2 %, so the run has no
 * knee in it), the first line stands.
 *
 * The line has a free intercept and the unloaded reference is left out by
 * default: a lab usually zeroes its gauge under a small preload, and forcing
 * the line through the origin then bends it (the tensile lab data reads 218
 * GPa that way instead of 194). Signs are kept, as everywhere in
 * [StressStrain]; a negative E usually means the wrong strain axis.
 *
 * Camera strain is noisier than an extensometer, so this E is approximate and
 * every surface that shows it says so.
 */
object ElasticModulus {

    const val MIN_R2 = 0.995
    const val MIN_POINTS = 3

    /** The refit window's ends, as shares of |Rp0.2| (ISO 6892-1 Annex G). */
    const val WINDOW_LOW = 0.10f
    const val WINDOW_HIGH = 0.40f

    /**
     * The fit, in curve terms. [firstFrame] and [lastFrame] are 0-based
     * deformed-frame indices of the first and last point used.
     */
    data class Fit(
        val modulusGPa: Float,
        val interceptMPa: Float,
        val firstFrame: Int,
        val lastFrame: Int,
        val points: Int,
        val r2: Float,
    ) {
        /** Whether a solved frame lies inside the fitted run. */
        fun covers(frame: Int): Boolean = frame in firstFrame..lastFrame

        /** Stress on the fitted line at [strainMilli], MPa. */
        fun stressAt(strainMilli: Float): Float = modulusGPa * strainMilli + interceptMPa
    }

    /**
     * The fit, or null when fewer than [minPoints] frames precede the peak,
     * strain does not move, or no leading run is straight enough.
     */
    fun fit(
        curve: StressStrain.Curve,
        minR2: Double = MIN_R2,
        minPoints: Int = MIN_POINTS,
        includeOrigin: Boolean = false,
    ): Fit? {
        val candidates = candidates(curve)
        val first = longestRun(candidates, minR2, minPoints, includeOrigin)
        val rp02 = first?.let { YieldStrength.offset(curve, it) }
        return rp02?.let { refit(candidates, it.stressMPa, minR2, minPoints, includeOrigin) } ?: first
    }

    /** Solved points in frame order up to and including the peak. */
    private fun candidates(curve: StressStrain.Curve): List<StressStrain.Point> {
        val peak = curve.peak ?: return emptyList()
        val end = curve.points.indexOf(peak)
        return curve.points.subList(0, end + 1)
    }

    /** The longest leading run that is straight enough; shorter runs may fail on noise. */
    private fun longestRun(
        candidates: List<StressStrain.Point>,
        minR2: Double,
        minPoints: Int,
        includeOrigin: Boolean,
    ): Fit? = (candidates.size downTo minPoints).asSequence()
        .mapNotNull { count -> fitOf(candidates.subList(0, count), includeOrigin)?.takeIf { it.r2 >= minR2 } }
        .firstOrNull()

    /**
     * The line over the frames whose |stress| lies between [WINDOW_LOW] and
     * [WINDOW_HIGH] of |[rp02MPa]|; null when too few or not straight.
     */
    private fun refit(
        candidates: List<StressStrain.Point>,
        rp02MPa: Float,
        minR2: Double,
        minPoints: Int,
        includeOrigin: Boolean,
    ): Fit? {
        val window = (abs(rp02MPa) * WINDOW_LOW)..(abs(rp02MPa) * WINDOW_HIGH)
        val inWindow = candidates.filter { abs(it.stressMPa) in window }
        return if (inWindow.size < minPoints) null else fitOf(inWindow, includeOrigin)?.takeIf { it.r2 >= minR2 }
    }

    private fun fitOf(points: List<StressStrain.Point>, includeOrigin: Boolean): Fit? {
        val lead = if (includeOrigin) 1 else 0
        val xs = DoubleArray(points.size + lead)
        val ys = DoubleArray(points.size + lead)
        points.forEachIndexed { i, p ->
            xs[i + lead] = p.strainMilli.toDouble()
            ys[i + lead] = p.stressMPa.toDouble()
        }
        val line = LinearFit.fit(xs, ys) ?: return null
        return Fit(
            modulusGPa = line.slope.toFloat(),
            interceptMPa = line.intercept.toFloat(),
            firstFrame = points.first().frame,
            lastFrame = points.last().frame,
            points = points.size,
            r2 = line.r2.toFloat(),
        )
    }
}
