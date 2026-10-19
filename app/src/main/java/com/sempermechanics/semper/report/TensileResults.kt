package com.sempermechanics.semper.report

/**
 * A tensile curve's Results, worked out once for every surface that quotes
 * them — the viewer, the DIC PDF's curve page, the CSV trailer and the lab
 * report — so none of them fits a line or finds the yield point its own way.
 *
 * @property modulus E and the frames it came from ([ElasticModulus]); null
 *   when no straight run was found.
 * @property yieldPoint Rp0.2 ([YieldStrength]); null without a positive E or
 *   when the curve peaks before the offset line.
 * @property peak the peak stress over every loaded frame ([StressStrain.Curve.peakStress]).
 * @property fitLine the fitted line's two ends, (strain mε, stress MPa), across
 *   the fitted frames on the curve; null without a fit.
 */
data class TensileResults(
    val modulus: ElasticModulus.Fit?,
    val yieldPoint: YieldStrength.Point?,
    val peak: StressStrain.Peak?,
    val fitLine: List<Pair<Float, Float>>?,
) {
    companion object {
        /** [curve]'s results with its own fit, or null when it is not tensile. */
        fun of(curve: StressStrain.Curve): TensileResults? =
            if (curve.model is StressStrain.Model.Axial) of(curve, ElasticModulus.fit(curve)) else null

        /** [curve]'s results given its [modulus] fit, made already or null. */
        fun of(curve: StressStrain.Curve, modulus: ElasticModulus.Fit?): TensileResults {
            val fitted = modulus?.let { fit -> curve.points.filter { fit.covers(it.frame) } }.orEmpty()
            val line = if (modulus == null || fitted.isEmpty()) {
                null
            } else {
                val from = fitted.minOf { it.strainMilli }
                val to = fitted.maxOf { it.strainMilli }
                listOf(from to modulus.stressAt(from), to to modulus.stressAt(to))
            }
            return TensileResults(
                modulus = modulus,
                yieldPoint = modulus?.let { YieldStrength.offset(curve, it) },
                peak = curve.peakStress,
                fitLine = line,
            )
        }
    }
}
