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
 * around the midpoint, along the line between the taps, is the deflection
 * δ — positive the way the load pushes ([alongLoad]), whichever way the phone
 * was held and whichever edge was tapped first.
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
 *
 * A [Correction] the TA gives for the camera, lighting and tripod setup —
 * δ = scale × δ_camera + bias — is applied once, after the sign
 * ([Correction.applyTo]), so every table, graph, E and export reads it.
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
     * the unit direction from the first tap to the second, and a radius
     * around the midpoint (half the thickness, at least
     * [MIN_PROBE_RADIUS_PX]) inside which points are averaged. The direction
     * is only the tap order, so one frame's δ can come out with either sign;
     * a curve's points take theirs from the load ([alongLoad]).
     */
    data class Probe(
        val taps: BeamEdgeTaps,
        val thicknessMm: Float,
        val correction: Correction = Correction.NONE,
    ) {
        val mmPerPx: Float = thicknessMm / taps.thicknessPx
        val downX: Float = (taps.bottomX - taps.topX) / taps.thicknessPx
        val downY: Float = (taps.bottomY - taps.topY) / taps.thicknessPx
        val radiusPx: Float = max(taps.thicknessPx / 2f, MIN_PROBE_RADIUS_PX)

        companion object {
            /** A probe when the taps are set and the thickness entered; else null. */
            fun of(taps: BeamEdgeTaps, thicknessMm: Float, correction: Correction = Correction.NONE): Probe? =
                if (taps.isSet && thicknessMm > 0f) Probe(taps, thicknessMm, correction) else null
        }
    }

    /**
     * The setup's deflection correction, δ = [scale] × δ_camera + [biasMm].
     * [scale] changes both E values (E ∝ 1 / scale); [biasMm] changes the
     * per-step E but not the slope E, whose fit has a free intercept.
     */
    data class Correction(val scale: Float = 1f, val biasMm: Float = 0f) {
        val isNone: Boolean get() = scale == 1f && biasMm == 0f

        fun apply(deflectionMm: Float): Float = scale * deflectionMm + biasMm

        /**
         * [points] with every δ corrected. Call it on [alongLoad]'s output:
         * the bias goes on after the sign, so a session with its taps
         * reversed shifts δ the same way as one tapped top first.
         */
        fun applyTo(points: List<StressStrain.Point>): List<StressStrain.Point> =
            if (isNone) points else points.map { p -> p.copy(deflectionMm = p.deflectionMm?.let(::apply)) }

        /** The camera's δ back from a corrected one: the inverse of [apply]. */
        fun remove(deflectionMm: Float): Float = (deflectionMm - biasMm) / scale

        companion object {
            val NONE = Correction()

            /** The [model]'s correction; none unless it is bending with a probe. */
            fun of(model: StressStrain.Model): Correction =
                (model as? StressStrain.Model.Flexural)?.probe?.correction ?: NONE

            /** A usable correction: a scale that is not positive, or a non-finite value, is none. */
            fun of(scale: Float, biasMm: Float): Correction =
                if (scale > 0f && scale.isFinite() && biasMm.isFinite()) Correction(scale, biasMm) else NONE

            /** A typed scale: blank is 1, a decimal comma is accepted; null unless a positive number. */
            fun parseScale(text: String): Float? = typed(text, blank = 1f)?.takeIf { it > 0f }

            /** A typed bias in mm: blank is 0, a decimal comma is accepted; null unless a number. */
            fun parseBias(text: String): Float? = typed(text, blank = 0f)

            private fun typed(text: String, blank: Float): Float? {
                val trimmed = text.trim()
                if (trimmed.isEmpty()) return blank
                return trimmed.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
            }

            /**
             * [curve] under [model]'s correction instead of its own: each δ taken
             * back to the camera's and corrected again, so the viewer can change
             * the correction without decoding every frame. The sign
             * ([alongLoad]) was set on the camera's δ and stays. [curve] itself
             * when the correction is the same.
             */
            fun recorrect(curve: StressStrain.Curve, model: StressStrain.Model): StressStrain.Curve {
                val old = of(curve.model)
                val new = of(model)
                if (old == new) return curve.copy(model = model)
                val points = curve.points.map { p ->
                    p.copy(deflectionMm = p.deflectionMm?.let { new.apply(old.remove(it)) })
                }
                return curve.copy(model = model, points = points)
            }
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

    /**
     * [points] with δ measured the way the load pushes: every δ negated when
     * load and δ run opposite ways (Σ W·δ < 0, the sign of the slope through
     * the origin). The probe's direction is the first tap to the second, so a
     * student who taps the bottom edge first — or a camera mounted upside
     * down — would otherwise flip δ, the slope and both E. A central load
     * bends the beam its own way, so the sign comes from the test, not the
     * taps, and a session saved with its taps reversed reads right. Points
     * without a δ, and a curve with none (tensile), come back as they are.
     */
    fun alongLoad(points: List<StressStrain.Point>): List<StressStrain.Point> {
        val work = points.sumOf { p ->
            val d = p.deflectionMm
            if (d == null || !p.loadN.isFinite()) 0.0 else p.loadN.toDouble() * d
        }
        return if (work < 0.0) points.map { p -> p.copy(deflectionMm = p.deflectionMm?.let { -it }) } else points
    }

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

    /**
     * One load step, as a row of the lab's observation table. [modulusGPa] is null
     * when δ is too small. A load held over several frames runs [frame] to [lastFrame].
     */
    data class Step(
        val frame: Int,
        val loadN: Float,
        val deflectionMm: Float,
        val stressMPa: Float,
        val modulusGPa: Float?,
        val lastFrame: Int = frame,
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
        val correction: Correction,
        val secondMomentMm4: Float,
        val meanModulusGPa: Float?,
        val slope: LinearFit.Line?,
        val slopeModulusGPa: Float?,
    ) {
        /**
         * The [slope] line's two ends as (δ mm, W N), across the load steps it
         * was fitted to: first measured δ to last. Drawn from δ = 0 instead, a
         * free intercept below zero pulled the graph's load axis negative
         * (TD-94). Null without a slope.
         */
        fun slopeLine(): List<Pair<Float, Float>>? = slope?.takeIf { loadSteps.isNotEmpty() }?.let { line ->
            listOf(loadSteps.minOf { it.deflectionMm }, loadSteps.maxOf { it.deflectionMm })
                .map { d -> d to line.at(d.toDouble()).toFloat() }
        }
    }

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
            val stress = run.map { it.stressMPa }.average().toFloat()
            Step(run.first().frame, w, d, stress, stepE(w, d), lastFrame = run.last().frame)
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
            correction = probe.correction,
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
