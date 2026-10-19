package com.sempermechanics.semper.data.mechanical

/**
 * One hand-entered straight-line match, corrected = [scale] × measured +
 * [bias]: what the tensile curve's strain and stress ([CurveCorrection]) and
 * bending's deflection (`BeamDeflection.Correction`) are each made of. The
 * arithmetic and the typed-number parsing live here once, in whatever unit
 * the caller's measured value has.
 */
data class LinearCorrection(val scale: Float = 1f, val bias: Float = 0f) {
    val isNone: Boolean get() = scale == 1f && bias == 0f

    fun apply(measured: Float): Float = scale * measured + bias

    /** The measured value back from a corrected one: the inverse of [apply]. */
    fun remove(corrected: Float): Float = (corrected - bias) / scale

    companion object {
        val NONE = LinearCorrection()

        /** A typed scale: blank is 1, a decimal comma is accepted; null unless a positive number. */
        fun parseScale(text: String): Float? = typedDecimal(text, blank = 1f)?.takeIf { it > 0f }

        /** A typed bias: blank is 0, a decimal comma is accepted; null unless a number. */
        fun parseBias(text: String): Float? = typedDecimal(text, blank = 0f)

        /** A typed decimal: blank is [blank], a decimal comma is accepted; null unless a finite number. */
        fun typedDecimal(text: String, blank: Float): Float? {
            val trimmed = text.trim()
            return if (trimmed.isEmpty()) blank else trimmed.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }
        }
    }
}
