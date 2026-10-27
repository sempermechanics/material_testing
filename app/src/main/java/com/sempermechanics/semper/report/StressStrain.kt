package com.sempermechanics.semper.report

import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.field.DicResult
import kotlin.math.abs

/**
 * Stress–strain from a machine load per frame and the DIC field. Which
 * stress a load becomes, and which strain component pairs with it, is the
 * test's [Model]: load over cross-section for tensile, three-point flexural
 * stress for bending. Tensile strain is the virtual extensometer's ΔL / L₀
 * ([Extensometer], ADR-012); bending's is the outer fibre's, from the strain
 * gradient through the thickness under the tapped load point
 * ([BeamDeflection.Probe.outerFibreStrainMilli]), and none without the taps. Both in
 * millistrain. Pure — the viewer, the CSV and the PDF all read one [Curve].
 *
 * Loads keep the sign they were logged with, so a test logged negative plots
 * in the third quadrant. Nothing here takes an absolute value.
 */
object StressStrain {

    const val UNIT_STRESS = "MPa"
    const val UNIT_STRAIN = "mε"

    /** A specimen dimension a [Model] is built from, with its report label. */
    enum class Dimension(val label: String, val unit: String, val csvKey: String) {
        CROSS_SECTION("Cross-section", "mm²", "cross_section_mm2"),
        SPAN("Support span", "mm", "span_mm"),
        WIDTH("Width", "mm", "width_mm"),
        THICKNESS("Thickness", "mm", "thickness_mm"),
    }

    /**
     * How a logged load becomes a stress, and which strain goes with it.
     * [isComplete] is false while a dimension is still 0; [stressMPa] is then
     * NaN, and the wizard will not go on.
     */
    sealed class Model(
        /** Wire name in the CSV preamble: `axial` or `flexural`. */
        val wireName: String,
        /** Report wording, e.g. "Engineering stress". */
        val stressName: String,
        /** Report wording for the strain, e.g. "outer-fibre Exx". */
        val strainName: String,
    ) {
        abstract val isComplete: Boolean
        abstract fun stressMPa(loadN: Float): Float

        /**
         * The frame's strain in millistrain, or null when it has none and is
         * left off the curve. NaN keeps the frame on the curve (its stress and
         * deflection stand) without a strain: bending without its edge taps,
         * or a section too sparse to read. [gauge] is the curve's
         * [Extensometer] gauge; only [Axial] reads it.
         */
        abstract fun strainMilli(data: FloatArray, gauge: Extensometer.Gauge?): Float?

        /**
         * Whether this model reads a strain at all. False for bending without
         * its edge taps: every point's strain is NaN, and the plot and the
         * report's strain column are left out.
         */
        open val measuresStrain: Boolean get() = true

        /** Report wording for where the strain is read, after [strainName]. */
        open val strainBasis: String get() = "over accepted points"

        /** The dimensions this model was built from, entered or not, in display order. */
        abstract val dimensions: List<Pair<Dimension, Float>>

        /**
         * Whether the curve is load on deflection rather than stress on
         * strain: bending with the load point tapped.
         */
        open val plotsLoadDeflection: Boolean get() = false

        /** Report heading for the curve page. */
        open val curveTitle: String get() = "Stress–Strain Curve"

        /** Report caption for the plot. */
        open val plotTitle: String get() = "$stressName vs. $strainName"

        /** Deflection at the load point in mm, or null when this model reads none. */
        open fun deflectionMm(data: FloatArray): Float? = null

        /**
         * Tensile: σ = P / A, and engineering strain ΔL / L₀ along the load
         * axis from the virtual extensometer, as a clip-on extensometer reads
         * it (ADR-012). No gauge, or a band with no point, is no strain.
         * [correction] is the session's hand-entered scale and bias, applied
         * to both here so every reader of the curve sees them.
         */
        data class Axial(
            val areaMm2: Float,
            val axisX: Boolean,
            val correction: CurveCorrection = CurveCorrection.NONE,
        ) : Model(
            wireName = "axial",
            stressName = "Engineering stress",
            strainName = "ΔL/L₀ along ${if (axisX) "x" else "y"}",
        ) {
            override val isComplete: Boolean get() = areaMm2 > 0f
            override fun stressMPa(loadN: Float): Float =
                if (isComplete) correction.stress(loadN / areaMm2) else Float.NaN
            override fun strainMilli(data: FloatArray, gauge: Extensometer.Gauge?): Float? {
                val extension = gauge?.extensionPx(data) ?: return null
                return correction.strain(extension / gauge.lengthPx * DicResult.STRAIN_TO_MILLISTRAIN)
            }
            override val strainBasis: String get() = "between the end bands of the analysed region"
            override val dimensions get() = listOf(Dimension.CROSS_SECTION to areaMm2)
        }

        /**
         * Three-point bending: outer-fibre stress σ = 3 P L / (2 b h²). The
         * camera films the beam's side face, where the strain along the beam
         * runs linearly through the thickness from compression at the top to
         * tension at the bottom, so its mean is about zero. With a [probe] —
         * the tapped load point — the outer fibre's strain is read from that
         * gradient across the tapped thickness ([BeamDeflection.Probe.outerFibreStrainMilli]),
         * signed with the load like δ, and each frame also reads the deflection
         * there: the curve is load on deflection ([BeamDeflection]). Without
         * the taps there is no thickness in pixels and no neutral axis, so no
         * strain ([measuresStrain] is false). [isComplete] stays
         * dimensions-only, so a session from before the tap keeps its stress.
         */
        data class Flexural(
            val spanMm: Float,
            val widthMm: Float,
            val thicknessMm: Float,
            val axisX: Boolean,
            val probe: BeamDeflection.Probe? = null,
        ) : Model("flexural", "Flexural stress", flexuralStrainName(probe)) {
            override val isComplete: Boolean get() = spanMm > 0f && widthMm > 0f && thicknessMm > 0f
            override fun stressMPa(loadN: Float): Float =
                if (isComplete) THREE * loadN * spanMm / (2f * widthMm * thicknessMm * thicknessMm) else Float.NaN

            // A frame with no accepted point is left off, as before; one with
            // points keeps its stress and δ even when its strain cannot be read.
            override fun strainMilli(data: FloatArray, gauge: Extensometer.Gauge?): Float? = when {
                axisStrainMilli(data, axisX) == null -> null
                probe == null -> Float.NaN
                else -> probe.outerFibreStrainMilli(data) ?: Float.NaN
            }
            override val measuresStrain: Boolean get() = probe != null
            override val strainBasis: String
                get() = if (probe != null) {
                    "on the tension face, from its gradient through the tapped thickness at the load point"
                } else {
                    "(the beam's edges were not tapped, so there is no thickness to read it across)"
                }
            override val dimensions get() = listOf(
                Dimension.SPAN to spanMm,
                Dimension.WIDTH to widthMm,
                Dimension.THICKNESS to thicknessMm,
            )
            override val plotsLoadDeflection: Boolean get() = probe != null
            override val curveTitle: String
                get() = if (probe != null) "Load–Deflection Curve" else super.curveTitle
            override val plotTitle: String
                get() = if (probe != null) "Load vs. deflection at the load point" else super.plotTitle
            override fun deflectionMm(data: FloatArray): Float? = probe?.let { BeamDeflection.deflectionMm(data, it) }
        }

        companion object {
            /**
             * The model for a stored session. A blank or unknown type reads
             * as axial, which is what every session before bending had inputs
             * for — including one stored as a test this build no longer offers.
             * [correction] applies to axial only; bending ignores it.
             */
            fun of(
                testType: String,
                crossSectionMm2: Float,
                loadAxisX: Boolean,
                geometry: SpecimenGeometry,
                correction: CurveCorrection = CurveCorrection.NONE,
            ): Model = when (TestType.fromWire(testType)) {
                TestType.BENDING -> Flexural(
                    geometry.spanMm,
                    geometry.widthMm,
                    geometry.thicknessMm,
                    loadAxisX,
                    BeamDeflection.Probe.of(geometry.loadPoint, geometry.thicknessMm, geometry.deflectionCorrection),
                )
                TestType.TENSILE, TestType.DIC_2D, null -> Axial(crossSectionMm2, loadAxisX, correction)
            }
        }
    }

    /**
     * One solved frame on the curve. [frame] is the 0-based deformed index.
     * [deflectionMm] is set only when the model reads one and the probe
     * found points; [extensionPx] only for tensile, when both [Extensometer]
     * bands still have points.
     */
    data class Point(
        val frame: Int,
        val loadN: Float,
        val stressMPa: Float,
        val strainMilli: Float,
        val deflectionMm: Float? = null,
        val extensionPx: Float? = null,
    )

    /**
     * The highest |stress| over the frames with a load and a field. [onCurve]
     * is false when that frame had no strain, so it is not one of the points.
     */
    data class Peak(val frame: Int, val loadN: Float, val stressMPa: Float, val onCurve: Boolean)

    /**
     * The curve for a session. Frames whose field could not be read or had no
     * accepted point are absent from [points] rather than plotted at zero.
     * [loadPeak] is the [Collector]'s peak over every frame it was given.
     */
    data class Curve(
        val model: Model,
        val frameCount: Int,
        val points: List<Point>,
        val gauge: Extensometer.Gauge? = null,
        val loadPeak: Peak? = null,
    ) {
        val isEmpty: Boolean get() = points.isEmpty()

        fun at(frame: Int): Point? = points.firstOrNull { it.frame == frame }

        /**
         * Largest |stress| on the curve, or null when empty. The E fit and the
         * elastic-region view stop here; the stress to report is [peakStress].
         */
        val peak: Point? get() = points.maxByOrNull { abs(it.stressMPa) }

        /**
         * The peak (ultimate) stress to report: the highest over every frame
         * with a load, including one left off the curve because a band had
         * left the picture (TD-148). A curve made from points alone falls
         * back to [peak].
         */
        val peakStress: Peak?
            get() = loadPeak ?: peak?.let { Peak(it.frame, it.loadN, it.stressMPa, onCurve = true) }

        /**
         * Where the unloaded reference plots. Its strain, stress and δ are 0
         * to the camera and the load cell, so under a correction each is its
         * bias: (strain bias, stress bias) for tensile, (δ bias, 0) for bending
         * on load–deflection; (0, 0) without one.
         */
        val origin: Pair<Float, Float>
            get() = when {
                model.plotsLoadDeflection -> BeamDeflection.Correction.of(model).apply(0f) to 0f
                model is Model.Axial -> model.correction.strain(0f) to model.correction.stress(0f)
                else -> 0f to 0f
            }

        /**
         * (strain, stress) pairs for plotting — (deflection mm, load N) when
         * the model [plots load on deflection][Model.plotsLoadDeflection] —
         * led by the unloaded reference at [origin] so the elastic line reads
         * from it. A point without a strain (NaN) is not drawn. Display only —
         * the reference is not a solved frame and is not exported.
         */
        fun plotPoints(): List<Pair<Float, Float>> =
            if (model.plotsLoadDeflection) {
                listOf(origin) + points.mapNotNull { p -> p.deflectionMm?.let { it to p.loadN } }
            } else {
                listOf(origin) + points.filter { it.strainMilli.isFinite() }.map { it.strainMilli to it.stressMPa }
            }
    }

    /**
     * Collects a curve's points one frame at a time, in frame order, for
     * [build] and the streaming CSV writer alike. A tensile curve fixes its
     * [Extensometer] gauge on the first frame that can set one, and a frame
     * with no strain is left off. Every frame counts towards the [Peak].
     */
    class Collector(private val model: Model) {
        private val points = ArrayList<Point>()
        private var gauge: Extensometer.Gauge? = null
        private var peak: Peak? = null

        fun add(frame: Int, loadN: Float, data: FloatArray) {
            if (gauge == null && model is Model.Axial) gauge = Extensometer.gauge(data, model.axisX)
            val stress = model.stressMPa(loadN)
            val strain = model.strainMilli(data, gauge)
            val best = peak
            if (stress.isFinite() && (best == null || abs(stress) > abs(best.stressMPa))) {
                peak = Peak(frame, loadN, stress, onCurve = strain != null)
            }
            if (strain == null) return
            val extension = if (model is Model.Axial) gauge?.extensionPx(data) else null
            points += Point(frame, loadN, stress, strain, model.deflectionMm(data), extension)
        }

        val isEmpty: Boolean get() = points.isEmpty()

        /**
         * The curve so far; a bending curve's δ is signed by its loads and
         * corrected ([BeamDeflection.alongLoad], [BeamDeflection.Correction]).
         */
        fun curve(frameCount: Int): Curve {
            val signed = BeamDeflection.Correction.of(model).applyTo(BeamDeflection.alongLoad(points))
            return Curve(model, frameCount, signed, gauge, peak)
        }
    }

    /**
     * Builds the curve by asking [frameData] for each frame in turn; a null
     * field or one with no strain is skipped. [onProgress] gets the 1-based
     * count of frames visited.
     */
    fun build(
        loadsN: List<Float>,
        model: Model,
        frameData: (Int) -> FloatArray?,
        onProgress: (Int) -> Unit = {},
    ): Curve {
        val collector = Collector(model)
        loadsN.forEachIndexed { index, loadN ->
            // NaN: the time match found no log row for this frame, so it has
            // no load and no point on the curve.
            val data = if (loadN.isFinite()) frameData(index) else null
            if (data != null) collector.add(index, loadN, data)
            onProgress(index + 1)
        }
        return collector.curve(loadsN.size)
    }

    /**
     * [curve] under [correction] instead of the one it was built with: each
     * strain and stress taken back to the camera's and the load's and corrected
     * again, so the viewer can change the correction without decoding every
     * frame. A curve that is not axial comes back as it was.
     */
    fun recorrect(curve: Curve, correction: CurveCorrection): Curve {
        val model = curve.model as? Model.Axial
        if (model == null || model.correction == correction) return curve
        val old = model.correction
        fun strain(v: Float) = correction.strain(old.removeStrain(v))
        fun stress(v: Float) = correction.stress(old.removeStress(v))
        return curve.copy(
            model = model.copy(correction = correction),
            points = curve.points.map { p ->
                p.copy(strainMilli = strain(p.strainMilli), stressMPa = stress(p.stressMPa))
            },
            loadPeak = curve.loadPeak?.let { it.copy(stressMPa = stress(it.stressMPa)) },
        )
    }

    private fun axisStrainMilli(data: FloatArray, axisX: Boolean): Float? =
        DicResult.fieldStats(data, if (axisX) DicResult.IDX_EXX else DicResult.IDX_EYY)?.get(MEAN)

    /** The component the taps read ([BeamDeflection.Probe.strainAlongBeam]); a tilted beam reads neither. */
    private fun flexuralStrainName(probe: BeamDeflection.Probe?): String = when {
        probe == null -> "no strain"
        probe.downX == 0f -> "outer-fibre Exx"
        probe.downY == 0f -> "outer-fibre Eyy"
        else -> "outer-fibre strain along the beam"
    }

    private const val MEAN = 2
    private const val THREE = 3f
}
