package com.sempermechanics.semper.data.mechanical

import kotlinx.serialization.Serializable

/**
 * A tensile session's hand-entered match to a reference curve, set from
 * Results after the run: ε = [strainScale] × ε_camera + [strainBiasMilli] and
 * σ = [stressScale] × σ_load + [stressBiasMPa]. The student (or TA) reads the
 * scale and bias off a comparison with the machine's own stress–strain export
 * and types them in; 1 and 0 leave the curve as measured.
 *
 * It lives in the Axial model (`StressStrain.Model.Axial`), so every reader of
 * a curve — the plot, E, the yield point, the peak, the CSV and the PDF — gets
 * the corrected numbers from one place. Strain is in millistrain and stress in
 * MPa, the curve's own units.
 */
@Serializable
data class CurveCorrection(
    val strainScale: Float = 1f,
    val strainBiasMilli: Float = 0f,
    val stressScale: Float = 1f,
    val stressBiasMPa: Float = 0f,
) {
    val isNone: Boolean get() = this == NONE

    fun strain(cameraMilli: Float): Float = strainScale * cameraMilli + strainBiasMilli

    fun stress(loadMPa: Float): Float = stressScale * loadMPa + stressBiasMPa

    /** The camera's strain back from a corrected one: the inverse of [strain]. */
    fun removeStrain(correctedMilli: Float): Float = (correctedMilli - strainBiasMilli) / strainScale

    /** The load's stress back from a corrected one: the inverse of [stress]. */
    fun removeStress(correctedMPa: Float): Float = (correctedMPa - stressBiasMPa) / stressScale

    /** Intent-extra form; the order is fixed by [fromArray]. */
    fun toArray(): FloatArray = floatArrayOf(strainScale, strainBiasMilli, stressScale, stressBiasMPa)

    companion object {
        val NONE = CurveCorrection()

        private const val SIZE = 4
        private const val STRESS_SCALE = 2
        private const val STRESS_BIAS = 3

        /**
         * A usable correction: a scale that is not positive, or any value that
         * is not finite, gives [NONE] rather than a curve that cannot be undone.
         */
        fun of(strainScale: Float, strainBiasMilli: Float, stressScale: Float, stressBiasMPa: Float): CurveCorrection {
            val usable = strainScale > 0f &&
                stressScale > 0f &&
                listOf(strainScale, strainBiasMilli, stressScale, stressBiasMPa).all { it.isFinite() }
            return if (usable) CurveCorrection(strainScale, strainBiasMilli, stressScale, stressBiasMPa) else NONE
        }

        /** Inverse of [toArray]; a missing or short array is [NONE]. */
        fun fromArray(values: FloatArray?): CurveCorrection =
            if (values == null || values.size < SIZE) {
                NONE
            } else {
                of(values[0], values[1], values[STRESS_SCALE], values[STRESS_BIAS])
            }

        /** A typed scale: blank is 1, a decimal comma is accepted; null unless a positive number. */
        fun parseScale(text: String): Float? = typed(text, blank = 1f)?.takeIf { it > 0f }

        /** A typed bias: blank is 0, a decimal comma is accepted; null unless a number. */
        fun parseBias(text: String): Float? = typed(text, blank = 0f)

        private fun typed(text: String, blank: Float): Float? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return blank
            return trimmed.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
        }
    }
}
