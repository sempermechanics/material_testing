package com.sempermechanics.semper.report

/**
 * The 0.2 % offset yield strength Rp0.2 (ISO 6892-1, ASTM E8) of a tensile
 * [StressStrain.Curve]: where the curve meets the [ElasticModulus] line moved
 * 0.2 % (2 mε) along the strain axis. It is read between two photos, by
 * linear interpolation, so photos spaced far apart around yield make it coarse.
 *
 * Only the curve up to its own peak is searched, so a curve that drops before
 * it yields gives none. Signs are kept as in [StressStrain]: a test logged
 * negative moves the line the other way.
 */
object YieldStrength {

    /** 0.2 % strain, in the curve's millistrain. */
    const val OFFSET_MILLI = 2f

    /**
     * The yield point on the curve. [frameBefore] and [frameAfter] are the
     * 0-based frames of the two points it lies between.
     */
    data class Point(
        val strainMilli: Float,
        val stressMPa: Float,
        val frameBefore: Int,
        val frameAfter: Int,
    )

    /**
     * Rp0.2, or null for a curve that is not tensile, has no positive E, or
     * does not reach the offset line before its peak.
     */
    fun offset(curve: StressStrain.Curve, fit: ElasticModulus.Fit): Point? {
        val peak = curve.peak
        if (curve.model !is StressStrain.Model.Axial || fit.modulusGPa <= 0f || peak == null) return null
        val sign = if (peak.stressMPa < 0f) -1f else 1f
        val points = curve.points.subList(0, curve.points.indexOf(peak) + 1)

        // How far a point sits above the offset line, in the direction of the load.
        fun above(p: StressStrain.Point) = sign * (p.stressMPa - fit.stressAt(p.strainMilli - sign * OFFSET_MILLI))
        return points.zipWithNext().firstNotNullOfOrNull { (a, b) ->
            val da = above(a)
            val db = above(b)
            if (da > 0f && db <= 0f) {
                val t = da / (da - db)
                Point(
                    strainMilli = a.strainMilli + t * (b.strainMilli - a.strainMilli),
                    stressMPa = a.stressMPa + t * (b.stressMPa - a.stressMPa),
                    frameBefore = a.frame,
                    frameAfter = b.frame,
                )
            } else {
                null
            }
        }
    }
}
