package com.indicvision.semper.data

import kotlinx.serialization.Serializable
import kotlin.math.hypot

/**
 * The two points a bending student taps on the reference photo, under the
 * load point: the beam's top edge, then its bottom edge. Their distance in
 * pixels is the thickness the student measured in millimetres, which gives
 * the photo its mm-per-pixel scale; their midpoint is where the deflection is
 * read. Reference-image pixels, the same frame as the `.dat` x / y.
 *
 * All zero means "not tapped" ([NONE]); a session from before the tap, and
 * every tensile session, carries that.
 */
@Serializable
data class BeamEdgeTaps(
    val topX: Float = 0f,
    val topY: Float = 0f,
    val bottomX: Float = 0f,
    val bottomY: Float = 0f,
) {
    /** Top-to-bottom distance in reference pixels. */
    val thicknessPx: Float get() = hypot(bottomX - topX, bottomY - topY)

    /** Both edges tapped and far enough apart to be a thickness, not a slip. */
    val isSet: Boolean get() = thicknessPx >= MIN_THICKNESS_PX

    val midX: Float get() = (topX + bottomX) / 2f
    val midY: Float get() = (topY + bottomY) / 2f

    /** Intent-extra form: top x, top y, bottom x, bottom y. */
    fun toArray(): FloatArray = floatArrayOf(topX, topY, bottomX, bottomY)

    companion object {
        val NONE = BeamEdgeTaps()

        /** Below this the taps are one point, not two edges. */
        const val MIN_THICKNESS_PX = 3f

        /**
         * Under this many pixels a one-pixel slip changes the scale — and so
         * E — by more than 2.5 %; the tap editor warns.
         */
        const val PRECISE_THICKNESS_PX = 40f

        private const val SIZE = 4
        private const val BOTTOM_Y = 3

        /** Inverse of [toArray]; a missing or short array is [NONE]. */
        fun fromArray(values: FloatArray?): BeamEdgeTaps = if (values == null || values.size < SIZE) {
            NONE
        } else {
            BeamEdgeTaps(values[0], values[1], values[2], values[BOTTOM_Y])
        }
    }
}
