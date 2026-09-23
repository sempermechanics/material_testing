package com.indicvision.semper.report

/**
 * Young's modulus E from a stress–strain [StressStrain.Curve]: the slope of
 * the straight, early part of the curve. Stress is in MPa and strain in mε,
 * so the slope is E in GPa with no conversion.
 *
 * The straight part is the longest *leading* run of solved frames, up to the
 * peak, whose least-squares line keeps R² at or above [MIN_R2] — the frames a
 * student would rule a line through on graph paper. The line has a free
 * intercept and the unloaded reference is left out by default: a lab usually
 * zeroes its gauge under a small preload, and forcing the line through the
 * origin then bends it (the tensile lab data reads 218 GPa that way instead
 * of 194). Signs are kept, as everywhere in [StressStrain]; a negative E
 * usually means the wrong strain axis.
 *
 * Camera strain is noisier than an extensometer, so this E is approximate and
 * every surface that shows it says so.
 */
object ElasticModulus {

    const val MIN_R2 = 0.995
    const val MIN_POINTS = 3

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
     * strain does not move, or even the first [minPoints] are not straight
     * enough.
     */
    fun fit(
        curve: StressStrain.Curve,
        minR2: Double = MIN_R2,
        minPoints: Int = MIN_POINTS,
        includeOrigin: Boolean = false,
    ): Fit? {
        val candidates = candidates(curve)
        // Grow the run one frame at a time and keep the last one still straight enough.
        val best = (minPoints..candidates.size).asSequence()
            .map { count -> fitFirst(candidates, count, includeOrigin)?.takeIf { it.r2 >= minR2 }?.let { count to it } }
            .takeWhile { it != null }
            .lastOrNull()
        return best?.let { (count, line) ->
            Fit(
                modulusGPa = line.slope.toFloat(),
                interceptMPa = line.intercept.toFloat(),
                firstFrame = candidates.first().frame,
                lastFrame = candidates[count - 1].frame,
                points = count,
                r2 = line.r2.toFloat(),
            )
        }
    }

    /** Solved points in frame order up to and including the peak. */
    private fun candidates(curve: StressStrain.Curve): List<StressStrain.Point> {
        val peak = curve.peak ?: return emptyList()
        val end = curve.points.indexOf(peak)
        return curve.points.subList(0, end + 1)
    }

    private fun fitFirst(points: List<StressStrain.Point>, count: Int, includeOrigin: Boolean): LinearFit.Line? {
        val lead = if (includeOrigin) 1 else 0
        val xs = DoubleArray(count + lead)
        val ys = DoubleArray(count + lead)
        for (i in 0 until count) {
            xs[i + lead] = points[i].strainMilli.toDouble()
            ys[i + lead] = points[i].stressMPa.toDouble()
        }
        return LinearFit.fit(xs, ys)
    }
}
