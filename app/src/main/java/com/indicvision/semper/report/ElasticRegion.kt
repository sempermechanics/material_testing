package com.indicvision.semper.report

import kotlin.math.max
import kotlin.math.min

/**
 * The elastic region of a stress–strain curve, zoomed in: the frames
 * [ElasticModulus] fitted E to, plus a margin of the curve just past them, so
 * the straight part and its line fill a plot. On the whole curve they are a
 * vertical stroke at x≈0 — real steel fits E over frames 1–26 below 2 mε on a
 * curve that runs to 336 mε (docs/app/REAL_WORLD_VALIDATION.md). The viewer's
 * counterpart to the lab report's elastic graph ([LabReport.GRAPH_ELASTIC]).
 *
 * The window runs from the unloaded origin through the fitted run, widened by
 * [MARGIN_FRACTION] of that span on each side; only frames up to the peak are
 * kept, so a fractured specimen springing back does not land in it. Signs are
 * kept as everywhere in [StressStrain], so a compressive curve zooms the same.
 */
object ElasticRegion {

    /** Strain margin each side of the fitted run, as a share of its span. */
    const val MARGIN_FRACTION = 0.5f

    /**
     * [points]: (strain mε, stress MPa) led by the origin, as [StressStrain.Curve.plotPoints].
     * [line]: the fitted line across the same strain range, so the student sees where the curve leaves it.
     */
    data class Plot(val points: List<Pair<Float, Float>>, val line: List<Pair<Float, Float>>)

    /** The zoomed plot, or null when no solved frame lies in the fitted run. */
    fun of(curve: StressStrain.Curve, fit: ElasticModulus.Fit, margin: Float = MARGIN_FRACTION): Plot? {
        val fitted = curve.points.filter { fit.covers(it.frame) }
        if (fitted.isEmpty()) return null
        val low = min(0f, fitted.minOf { it.strainMilli })
        val high = max(0f, fitted.maxOf { it.strainMilli })
        val pad = (high - low) * margin
        val window = (low - pad)..(high + pad)
        val peakRow = curve.peak?.let { curve.points.indexOf(it) } ?: curve.points.lastIndex
        val points = listOf(0f to 0f) + curve.points.take(peakRow + 1)
            .filter { it.strainMilli in window }
            .map { it.strainMilli to it.stressMPa }
        val from = points.minOf { it.first }
        val to = points.maxOf { it.first }
        return Plot(points, listOf(from to fit.stressAt(from), to to fit.stressAt(to)))
    }
}
