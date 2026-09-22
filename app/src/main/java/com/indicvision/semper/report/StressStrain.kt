package com.indicvision.semper.report

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.data.TestType
import kotlin.math.PI
import kotlin.math.abs

/**
 * Stress–strain from a machine load per frame and the DIC field. Which
 * stress a load becomes, and which strain component pairs with it, is the
 * test's [Model]: load over cross-section for tensile and compression,
 * three-point flexural stress for bending, and torsional shear stress for
 * torsion. Strain is always the mean over the frame's accepted points, in
 * millistrain. Pure — the viewer, the CSV and the PDF all read one [Curve].
 *
 * Loads keep the sign they were logged with, so a compression test logged
 * negative plots in the third quadrant. Nothing here takes an absolute value.
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
        MOMENT_ARM("Moment arm", "mm", "moment_arm_mm"),
        DIAMETER("Diameter", "mm", "diameter_mm"),
    }

    /**
     * How a logged load becomes a stress, and which strain goes with it.
     * [isComplete] is false while a dimension is still 0; [stressMPa] is then
     * NaN, and the wizard will not go on.
     */
    sealed class Model(
        /** Wire name in the CSV preamble: `axial`, `flexural` or `torsional`. */
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

        /** Tensile / compression: σ = P / A, strain along the load axis. */
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
         */
        data class Flexural(
            val spanMm: Float,
            val widthMm: Float,
            val thicknessMm: Float,
            val axisX: Boolean,
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
        }

        /**
         * Torsion of a solid round bar: torque T = P · r, surface shear stress
         * τ = 16 T / (π d³), paired with the engineering shear strain
         * γ = 2 · Exy (the DIC field holds the tensor shear, half of γ).
         */
        data class Torsional(val momentArmMm: Float, val diameterMm: Float) :
            Model(
                wireName = "torsional",
                stressName = "Shear stress",
                strainName = "2 × mean Exy (engineering shear)",
            ) {
            override val isComplete: Boolean get() = momentArmMm > 0f && diameterMm > 0f

            /** Torque in N·mm for a logged load. */
            fun torqueNmm(loadN: Float): Float = loadN * momentArmMm

            override fun stressMPa(loadN: Float): Float =
                if (isComplete) {
                    (SIXTEEN * torqueNmm(loadN) / (PI * diameterMm * diameterMm * diameterMm)).toFloat()
                } else {
                    Float.NaN
                }
            override fun strainMilli(data: FloatArray): Float? =
                DicResult.fieldStats(data, DicResult.IDX_EXY)?.get(MEAN)?.let { it * 2f }
            override val dimensions get() = listOf(
                Dimension.MOMENT_ARM to momentArmMm,
                Dimension.DIAMETER to diameterMm,
            )
        }

        companion object {
            /**
             * The model for a stored session. A blank or unknown type reads
             * as axial, which is what every session before bending and torsion
             * had inputs for.
             */
            fun of(
                testType: String,
                crossSectionMm2: Float,
                loadAxisX: Boolean,
                geometry: SpecimenGeometry,
            ): Model = when (TestType.fromWire(testType)) {
                TestType.BENDING -> Flexural(geometry.spanMm, geometry.widthMm, geometry.thicknessMm, loadAxisX)
                TestType.TORSION -> Torsional(geometry.momentArmMm, geometry.diameterMm)
                TestType.TENSILE, TestType.COMPRESSION, null -> Axial(crossSectionMm2, loadAxisX)
            }
        }
    }

    /** One solved frame on the curve. [frame] is the 0-based deformed index. */
    data class Point(
        val frame: Int,
        val loadN: Float,
        val stressMPa: Float,
        val strainMilli: Float,
    )

    /**
     * The curve for a session. Frames whose field could not be read or had no
     * accepted point are absent from [points] rather than plotted at zero.
     */
    data class Curve(
        val model: Model,
        val frameCount: Int,
        val points: List<Point>,
    ) {
        val isEmpty: Boolean get() = points.isEmpty()

        fun at(frame: Int): Point? = points.firstOrNull { it.frame == frame }

        /** Largest |stress| on the curve, or null when empty. */
        val peak: Point? get() = points.maxByOrNull { abs(it.stressMPa) }

        /**
         * (strain, stress) pairs for plotting, led by the unloaded reference at
         * the origin so the elastic line reads from zero. Display only — it is
         * not a solved frame and is not exported.
         */
        fun plotPoints(): List<Pair<Float, Float>> =
            listOf(0f to 0f) + points.map { it.strainMilli to it.stressMPa }
    }

    /**
     * Builds the curve by asking [frameData] for each frame in turn; a null
     * field or one with no accepted point is skipped. [onProgress] gets the
     * 1-based count of frames visited.
     */
    fun build(
        loadsN: List<Float>,
        model: Model,
        frameData: (Int) -> FloatArray?,
        onProgress: (Int) -> Unit = {},
    ): Curve {
        val points = ArrayList<Point>(loadsN.size)
        loadsN.forEachIndexed { index, loadN ->
            val strain = frameData(index)?.let { model.strainMilli(it) }
            if (strain != null) {
                points += Point(index, loadN, model.stressMPa(loadN), strain)
            }
            onProgress(index + 1)
        }
        return Curve(model, loadsN.size, points)
    }

    private fun axisStrainMilli(data: FloatArray, axisX: Boolean): Float? =
        DicResult.fieldStats(data, if (axisX) DicResult.IDX_EXX else DicResult.IDX_EYY)?.get(MEAN)

    private fun axisStrainName(axisX: Boolean): String = if (axisX) "mean Exx" else "mean Eyy"

    private const val MEAN = 2
    private const val THREE = 3f
    private const val SIXTEEN = 16.0
}
