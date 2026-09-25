package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.data.TestType
import kotlin.math.abs

/**
 * Stress–strain from a machine load per frame and the DIC field. Which
 * stress a load becomes, and which strain component pairs with it, is the
 * test's [Model]: load over cross-section for tensile, three-point flexural
 * stress for bending. Strain is always the mean over the frame's accepted
 * points, in millistrain. Pure — the viewer, the CSV and the PDF all read one
 * [Curve].
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
        /** Report wording for the strain, e.g. "mean Exx". */
        val strainName: String,
    ) {
        abstract val isComplete: Boolean
        abstract fun stressMPa(loadN: Float): Float
        abstract fun strainMilli(data: FloatArray): Float?

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

        /** Tensile: σ = P / A, strain along the load axis. */
        data class Axial(val areaMm2: Float, val axisX: Boolean) :
            Model(
                wireName = "axial",
                stressName = "Engineering stress",
                strainName = axisStrainName(axisX),
            ) {
            override val isComplete: Boolean get() = areaMm2 > 0f
            override fun stressMPa(loadN: Float): Float = if (isComplete) loadN / areaMm2 else Float.NaN
            override fun strainMilli(data: FloatArray): Float? = axisStrainMilli(data, axisX)
            override val dimensions get() = listOf(Dimension.CROSS_SECTION to areaMm2)
        }

        /**
         * Three-point bending: outer-fibre stress σ = 3 P L / (2 b h²), with
         * strain along the beam. The surface the camera sees is the outer
         * fibre, so the DIC strain there is the flexural strain directly.
         * With a [probe] — the tapped load point — each frame also reads the
         * deflection there, and the curve is load on deflection
         * ([BeamDeflection]). [isComplete] stays dimensions-only, so a session
         * from before the tap keeps its stress.
         */
        data class Flexural(
            val spanMm: Float,
            val widthMm: Float,
            val thicknessMm: Float,
            val axisX: Boolean,
            val probe: BeamDeflection.Probe? = null,
        ) : Model("flexural", "Flexural stress", axisStrainName(axisX)) {
            override val isComplete: Boolean get() = spanMm > 0f && widthMm > 0f && thicknessMm > 0f
            override fun stressMPa(loadN: Float): Float =
                if (isComplete) THREE * loadN * spanMm / (2f * widthMm * thicknessMm * thicknessMm) else Float.NaN
            override fun strainMilli(data: FloatArray): Float? = axisStrainMilli(data, axisX)
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
             */
            fun of(
                testType: String,
                crossSectionMm2: Float,
                loadAxisX: Boolean,
                geometry: SpecimenGeometry,
            ): Model = when (TestType.fromWire(testType)) {
                TestType.BENDING -> Flexural(
                    geometry.spanMm,
                    geometry.widthMm,
                    geometry.thicknessMm,
                    loadAxisX,
                    BeamDeflection.Probe.of(geometry.loadPoint, geometry.thicknessMm),
                )
                TestType.TENSILE, TestType.DIC_2D, null -> Axial(crossSectionMm2, loadAxisX)
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
     * The curve for a session. Frames whose field could not be read or had no
     * accepted point are absent from [points] rather than plotted at zero.
     */
    data class Curve(
        val model: Model,
        val frameCount: Int,
        val points: List<Point>,
        val gauge: Extensometer.Gauge? = null,
    ) {
        val isEmpty: Boolean get() = points.isEmpty()

        fun at(frame: Int): Point? = points.firstOrNull { it.frame == frame }

        /** Largest |stress| on the curve, or null when empty. */
        val peak: Point? get() = points.maxByOrNull { abs(it.stressMPa) }

        /**
         * (strain, stress) pairs for plotting — (deflection mm, load N) when
         * the model [plots load on deflection][Model.plotsLoadDeflection] —
         * led by the unloaded reference at the origin so the elastic line
         * reads from zero. Display only — it is not a solved frame and is not
         * exported.
         */
        fun plotPoints(): List<Pair<Float, Float>> = listOf(0f to 0f) +
            if (model.plotsLoadDeflection) {
                points.mapNotNull { p -> p.deflectionMm?.let { it to p.loadN } }
            } else {
                points.map { it.strainMilli to it.stressMPa }
            }
    }

    /**
     * Builds the curve by asking [frameData] for each frame in turn; a null
     * field or one with no accepted point is skipped. [onProgress] gets the
     * 1-based count of frames visited. A tensile curve fixes its
     * [Extensometer] gauge on the first solved frame.
     */
    fun build(
        loadsN: List<Float>,
        model: Model,
        frameData: (Int) -> FloatArray?,
        onProgress: (Int) -> Unit = {},
    ): Curve {
        val points = ArrayList<Point>(loadsN.size)
        var gauge: Extensometer.Gauge? = null
        loadsN.forEachIndexed { index, loadN ->
            // NaN: the time match found no log row for this frame, so it has
            // no load and no point on the curve.
            val data = if (loadN.isFinite()) frameData(index) else null
            val strain = data?.let { model.strainMilli(it) }
            if (strain != null) {
                if (gauge == null && model is Model.Axial) gauge = Extensometer.gauge(data, model.axisX)
                val extension = gauge?.extensionPx(data)
                points += Point(index, loadN, model.stressMPa(loadN), strain, model.deflectionMm(data), extension)
            }
            onProgress(index + 1)
        }
        return Curve(model, loadsN.size, points, gauge)
    }

    private fun axisStrainMilli(data: FloatArray, axisX: Boolean): Float? =
        DicResult.fieldStats(data, if (axisX) DicResult.IDX_EXX else DicResult.IDX_EYY)?.get(MEAN)

    private fun axisStrainName(axisX: Boolean): String = if (axisX) "mean Exx" else "mean Eyy"

    private const val MEAN = 2
    private const val THREE = 3f
}
