package com.indicvision.semper.report

import com.indicvision.semper.DicResult

/**
 * Engineering stress–strain from a machine load per frame and the DIC field:
 * stress is the logged load over the specimen's original cross-section;
 * strain is the mean of Exx (or Eyy) over the frame's accepted points, in
 * millistrain. Pure — the viewer, the CSV and the PDF all read one [Curve].
 *
 * Loads keep the sign they were logged with, so a compression test logged
 * negative plots in the third quadrant. Nothing here takes an absolute value.
 */
object StressStrain {

    const val UNIT_STRESS = "MPa"
    const val UNIT_STRAIN = "mε"

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
        val crossSectionMm2: Float,
        val axisX: Boolean,
        val frameCount: Int,
        val points: List<Point>,
    ) {
        val isEmpty: Boolean get() = points.isEmpty()

        fun at(frame: Int): Point? = points.firstOrNull { it.frame == frame }

        /** Largest |stress| on the curve, or null when empty. */
        val peak: Point? get() = points.maxByOrNull { kotlin.math.abs(it.stressMPa) }

        /**
         * (strain, stress) pairs for plotting, led by the unloaded reference at
         * the origin so the elastic line reads from zero. Display only — it is
         * not a solved frame and is not exported.
         */
        fun plotPoints(): List<Pair<Float, Float>> =
            listOf(0f to 0f) + points.map { it.strainMilli to it.stressMPa }
    }

    /** N / mm² is MPa. NaN when the area is not positive. */
    fun stressMPa(loadN: Float, areaMm2: Float): Float = if (areaMm2 > 0f) loadN / areaMm2 else Float.NaN

    /** Mean strain over accepted points along the load axis, in mε, or null with none. */
    fun strainMilli(data: FloatArray, axisX: Boolean): Float? =
        DicResult.fieldStats(data, if (axisX) DicResult.IDX_EXX else DicResult.IDX_EYY)?.get(MEAN)

    /**
     * Builds the curve by asking [frameData] for each frame in turn; a null
     * field or one with no accepted point is skipped. [onProgress] gets the
     * 1-based count of frames visited.
     */
    fun build(
        loadsN: List<Float>,
        areaMm2: Float,
        axisX: Boolean,
        frameData: (Int) -> FloatArray?,
        onProgress: (Int) -> Unit = {},
    ): Curve {
        val points = ArrayList<Point>(loadsN.size)
        loadsN.forEachIndexed { index, loadN ->
            val strain = frameData(index)?.let { strainMilli(it, axisX) }
            if (strain != null) {
                points += Point(index, loadN, stressMPa(loadN, areaMm2), strain)
            }
            onProgress(index + 1)
        }
        return Curve(areaMm2, axisX, loadsN.size, points)
    }

    private const val MEAN = 2
}
