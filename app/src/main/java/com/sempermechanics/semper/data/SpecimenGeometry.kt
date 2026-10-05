package com.indicvision.semper.data

import com.indicvision.semper.report.BeamDeflection
import kotlinx.serialization.Serializable

/**
 * The specimen dimensions a bending test needs to turn the machine's load
 * into a stress. Tensile needs only the cross-section, which predates this
 * class and stays its own field on [SessionRecord]; everything here is 0
 * ("not entered") on a tensile session.
 *
 * Bending is three-point: [spanMm] between the supports, [widthMm] across the
 * beam and [thicknessMm] in the direction of loading. All in millimetres, so
 * stresses come out in MPa. [loadPoint] is where the student tapped the
 * beam's edges on the reference photo — the scale and the deflection probe.
 * [deflectionScale] and [deflectionBiasMm] are the TA's correction for the
 * camera setup (δ = scale × δ_camera + bias); 1 and 0 leave δ as measured.
 */
@Serializable
data class SpecimenGeometry(
    val spanMm: Float = 0f,
    val widthMm: Float = 0f,
    val thicknessMm: Float = 0f,
    val loadPoint: BeamEdgeTaps = BeamEdgeTaps.NONE,
    val deflectionScale: Float = 1f,
    val deflectionBiasMm: Float = 0f,
) {
    val isNone: Boolean get() = this == NONE

    val deflectionCorrection: BeamDeflection.Correction
        get() = BeamDeflection.Correction.of(deflectionScale, deflectionBiasMm)

    /** This geometry with [correction] as its deflection scale and bias. */
    fun withCorrection(correction: BeamDeflection.Correction): SpecimenGeometry =
        copy(deflectionScale = correction.scale, deflectionBiasMm = correction.biasMm)

    /** Intent-extra form; the order is fixed by [fromArray]. */
    fun toArray(): FloatArray = floatArrayOf(
        spanMm,
        widthMm,
        thicknessMm,
        loadPoint.topX,
        loadPoint.topY,
        loadPoint.bottomX,
        loadPoint.bottomY,
        deflectionScale,
        deflectionBiasMm,
    )

    companion object {
        val NONE = SpecimenGeometry()

        // Positions in [toArray]; changing one changes the Intent extra.
        private const val SPAN = 0
        private const val WIDTH = 1
        private const val THICKNESS = 2
        private const val TOP_X = 3
        private const val TOP_Y = 4
        private const val BOTTOM_X = 5
        private const val BOTTOM_Y = 6
        private const val SCALE = 7
        private const val BIAS = 8
        private const val SIZE_DIMENSIONS = 3
        private const val SIZE_WITH_TAPS = 7
        private const val SIZE_WITH_CORRECTION = 9

        /**
         * Inverse of [toArray]; a missing or short array is [NONE]. A
         * three-value array — an Intent from before the tap — keeps its
         * dimensions and has no taps; a seven-value one has no correction.
         */
        fun fromArray(values: FloatArray?): SpecimenGeometry {
            if (values == null || values.size < SIZE_DIMENSIONS) return NONE
            val taps = if (values.size < SIZE_WITH_TAPS) {
                BeamEdgeTaps.NONE
            } else {
                BeamEdgeTaps(values[TOP_X], values[TOP_Y], values[BOTTOM_X], values[BOTTOM_Y])
            }
            val corrected = values.size >= SIZE_WITH_CORRECTION
            return SpecimenGeometry(
                spanMm = values[SPAN],
                widthMm = values[WIDTH],
                thicknessMm = values[THICKNESS],
                loadPoint = taps,
                deflectionScale = if (corrected) values[SCALE] else 1f,
                deflectionBiasMm = if (corrected) values[BIAS] else 0f,
            )
        }
    }
}
