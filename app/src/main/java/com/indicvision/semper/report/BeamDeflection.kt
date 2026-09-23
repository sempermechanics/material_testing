package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.BeamEdgeTaps
import kotlin.math.abs
import kotlin.math.max

/**
 * Bending by the lab's own formulas, with the camera in place of the dial
 * gauge. The student taps the beam's top and bottom edges under the load
 * point on the reference photo ([BeamEdgeTaps]); the thickness they measured
 * over the tapped pixels is the photo's scale, and the DIC displacement
 * around the midpoint, along the top-to-bottom direction, is the deflection
 * δ — positive downward whichever way the phone was held.
 *
 * Per step: σb = M·y / I with M = W L / 4, y = t / 2, I = b t³ / 12 (the same
 * number as [StressStrain.Model.Flexural]'s 3 P L / (2 b h²)), and
 * E = W L³ / (48 δ I). Over the test: the mean of the per-step E, and E from
 * the slope of load on deflection — the two values the lab's Results ask for.
 *
 * δ is the raw displacement at the load point, rigid movement of the whole
 * beam included (supports settling, the phone shifting). Removing rigid
 * motion would remove the bending with it, so the slope E — which a constant
 * offset does not change — is the headline number. Millimetres and newtons
 * in, MPa and GPa out. Pure.
 */
object BeamDeflection {

    /** Below this many pixels a step's δ is noise, and its E is left out. */
    const val MIN_DEFLECTION_PX = 1f

    /**
     * A step carrying less than this fraction of the largest load is unloaded
     * (the reference, or photos before the hanger went on) and stays out of the
     * slope fit, as in the report's graph. The lab zeroes the dial with the
     * hanger seated, so its readings need not pass through the origin.
     */
    const val UNLOADED_FRACTION = 0.01f

    /** Frames whose loads differ by less than this fraction of the largest are one held load. */
    const val SAME_LOAD_FRACTION = 0.005f

    /** Smallest probe radius, so a thin beam still averages some points. */
    const val MIN_PROBE_RADIUS_PX = 8f

    private const val MPA_PER_GPA = 1000f
    private const val TWELVE = 12f
    private const val FORTY_EIGHT = 48f
    private const val FOUR = 4f

    /**
     * The tapped load point, in a form the per-frame read needs: the scale,
     * the unit direction from the top edge to the bottom one, and a radius
     * around the midpoint (half the thickness, at least
     * [MIN_PROBE_RADIUS_PX]) inside which points are averaged.
     */
    data class Probe(val taps: BeamEdgeTaps, val thicknessMm: Float) {
        val mmPerPx: Float = thicknessMm / taps.thicknessPx
        val downX: Float = (taps.bottomX - taps.topX) / taps.thicknessPx
        val downY: Float = (taps.bottomY - taps.topY) / taps.thicknessPx
        val radiusPx: Float = max(taps.thicknessPx / 2f, MIN_PROBE_RADIUS_PX)

        companion object {
            /** A probe when the taps are set and the thickness entered; else null. */
            fun of(taps: BeamEdgeTaps, thicknessMm: Float): Probe? =
                if (taps.isSet && thicknessMm > 0f) Probe(taps, thicknessMm) else null
        }
    }

    /**
     * Mean displacement along [Probe.downX], [Probe.downY] of the accepted
     * points within the probe radius, in pixels; null when none is inside.
     */
    fun deflectionPx(data: FloatArray, probe: Probe): Float? {
        val cx = probe.taps.midX
        val cy = probe.taps.midY
        val r2 = probe.radiusPx * probe.radiusPx
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + DicResult.STRIDE <= data.size) {
            val dx = data[i + DicResult.IDX_X] - cx
            val dy = data[i + DicResult.IDX_Y] - cy
            if (dx * dx + dy * dy <= r2 && DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                sum += data[i + DicResult.IDX_U] * probe.downX + data[i + DicResult.IDX_V] * probe.downY
                count++
            }
            i += DicResult.STRIDE
        }
        return if (count == 0) null else (sum / count).toFloat()
    }

    /** [deflectionPx] in millimetres. */
    fun deflectionMm(data: FloatArray, probe: Probe): Float? = deflectionPx(data, probe)?.let { it * probe.mmPerPx }

    /** I = b t³ / 12, mm⁴. */
    fun secondMomentMm4(widthMm: Float, thicknessMm: Float): Float =
        widthMm * thicknessMm * thicknessMm * thicknessMm / TWELVE

    /** M = W L / 4, N·mm. */
    fun momentNmm(loadN: Float, spanMm: Float): Float = loadN * spanMm / FOUR

    /** E = W L³ / (48 δ I), GPa; null when δ is 0. */
    fun modulusGPa(loadN: Float, spanMm: Float, deflectionMm: Float, secondMomentMm4: Float): Float? =
        if (deflectionMm == 0f || secondMomentMm4 <= 0f) {
            null
        } else {
            loadN * spanMm * spanMm * spanMm / (FORTY_EIGHT * deflectionMm * secondMomentMm4) / MPA_PER_GPA
        }

    /** E from a load-on-deflection slope in N/mm: the same formula with W/δ = slope. */
    fun modulusFromSlopeGPa(slopeNPerMm: Double, spanMm: Float, secondMomentMm4: Float): Float =
        modulusGPa(slopeNPerMm.toFloat(), spanMm, 1f, secondMomentMm4) ?: Float.NaN

    /** One load step, as a row of the lab's observation table. [modulusGPa] is null when δ is too small. */
    data class Step(
        val frame: Int,
        val loadN: Float,
        val deflectionMm: Float,
        val stressMPa: Float,
        val modulusGPa: Float?,
    )

    /**
     * The lab's Results. [steps] has one entry per frame; [loadSteps] one per
     * held load, the rows of the lab's table (averaged over their frames).
     * [meanModulusGPa] averages the load steps that have an E; [slope] is load
     * (N) on deflection (mm) over the load steps, free intercept, and
     * [slopeModulusGPa] the E it gives. Either is null when there is too
     * little to compute it from.
     */
    data class Summary(
        val steps: List<Step>,
        val loadSteps: List<Step>,
        val mmPerPx: Float,
        val secondMomentMm4: Float,
        val meanModulusGPa: Float?,
        val slope: LinearFit.Line?,
        val slopeModulusGPa: Float?,
    )

    /**
     * The summary for a bending curve, or null when the curve is not
     * bending, has no probe, or no point carries a deflection.
     */
    fun summarize(curve: StressStrain.Curve): Summary? {
        val model = curve.model as? StressStrain.Model.Flexural
        val probe = model?.probe
        return if (model == null || probe == null) null else summarize(curve, model, probe)
    }

    private fun summarize(curve: StressStrain.Curve, model: StressStrain.Model.Flexural, probe: Probe): Summary? {
        val inertia = secondMomentMm4(model.widthMm, model.thicknessMm)
        val minDeflectionMm = MIN_DEFLECTION_PX * probe.mmPerPx
        val stepE = { w: Float, d: Float ->
            if (abs(d) < minDeflectionMm) null else modulusGPa(w, model.spanMm, d, inertia)
        }
        val steps = curve.points.mapNotNull { p ->
            p.deflectionMm?.let { d -> Step(p.frame, p.loadN, d, p.stressMPa, stepE(p.loadN, d)) }
        }
        if (steps.isEmpty()) return null
        val held = loadSteps(steps).map { run ->
            val w = run.map { it.loadN }.average().toFloat()
            val d = run.map { it.deflectionMm }.average().toFloat()
            Step(run.first().frame, w, d, run.map { it.stressMPa }.average().toFloat(), stepE(w, d))
        }
        val moduli = held.mapNotNull { it.modulusGPa }
        val line = LinearFit.fit(
            held.map { it.deflectionMm.toDouble() }.toDoubleArray(),
            held.map { it.loadN.toDouble() }.toDoubleArray(),
        )
        return Summary(
            steps = steps,
            loadSteps = held,
            mmPerPx = probe.mmPerPx,
            secondMomentMm4 = inertia,
            meanModulusGPa = if (moduli.isEmpty()) null else moduli.average().toFloat(),
            slope = line,
            slopeModulusGPa = line?.let { modulusFromSlopeGPa(it.slope, model.spanMm, inertia) },
        )
    }

    /**
     * The loaded frames, in order, as runs held at one load: a video films each
     * hanger weight for several seconds, and each run is one row of the lab's
     * table. Unloaded frames ([UNLOADED_FRACTION]) are dropped. Photos, one per
     * load, give runs of one.
     */
    private fun loadSteps(steps: List<Step>): List<List<Step>> {
        val maxLoad = steps.maxOf { abs(it.loadN) }
        val runs = mutableListOf<MutableList<Step>>()
        steps.filter { abs(it.loadN) > UNLOADED_FRACTION * maxLoad }.forEach { step ->
            val run = runs.lastOrNull()
            if (run != null && abs(step.loadN - run.first().loadN) <= SAME_LOAD_FRACTION * maxLoad) {
                run += step
            } else {
                runs += mutableListOf(step)
            }
        }
        return runs
    }
}
