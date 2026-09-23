package com.indicvision.semper.data

import kotlinx.serialization.Serializable

/**
 * The specimen dimensions a bending test needs to turn the machine's load
 * into a stress. Tensile needs only the cross-section, which predates this
 * class and stays its own field on [SessionRecord]; everything here is 0
 * ("not entered") on a tensile session.
 *
 * Bending is three-point: [spanMm] between the supports, [widthMm] across the
 * beam and [thicknessMm] in the direction of loading. All in millimetres, so
 * stresses come out in MPa.
 */
@Serializable
data class SpecimenGeometry(
    val spanMm: Float = 0f,
    val widthMm: Float = 0f,
    val thicknessMm: Float = 0f,
) {
    val isNone: Boolean get() = this == NONE

    /** Intent-extra form; the order is fixed by [fromArray]. */
    fun toArray(): FloatArray = floatArrayOf(spanMm, widthMm, thicknessMm)

    companion object {
        val NONE = SpecimenGeometry()

        // Positions in [toArray]; changing one changes the Intent extra.
        private const val SPAN = 0
        private const val WIDTH = 1
        private const val THICKNESS = 2
        private const val SIZE = 3

        /** Inverse of [toArray]; a missing or short array is [NONE]. */
        fun fromArray(values: FloatArray?): SpecimenGeometry {
            if (values == null || values.size < SIZE) return NONE
            return SpecimenGeometry(
                spanMm = values[SPAN],
                widthMm = values[WIDTH],
                thicknessMm = values[THICKNESS],
            )
        }
    }
}
