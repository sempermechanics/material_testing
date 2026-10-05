package com.indicvision.semper.report

import com.indicvision.semper.DicResult

/**
 * A virtual extensometer for the tensile lab table's Extension column, in
 * pixels along the strain axis. The gauge is fixed once, on the first solved
 * frame: a band of points at each end of the analysed region
 * ([BAND_FRACTION] of its length), in reference coordinates. On every frame
 * ΔL is the mean displacement along the axis in the far band minus that in
 * the near band, so ΔL / [Gauge.lengthPx] is the strain between the bands —
 * close to the mean strain the curve plots when the strain is even.
 *
 * Pixels, because tensile has no mm scale. A frame where either band has no
 * accepted point (that end has left the view, or stopped correlating) has no
 * extension, rather than one measured over a different length.
 */
object Extensometer {

    /** Each end band's share of the analysed length along the axis. */
    const val BAND_FRACTION = 0.1f

    /**
     * Band limits in reference pixels along the axis, and the gauge length:
     * the distance between the bands' mean positions on the frame that set it.
     */
    data class Gauge(
        val axisX: Boolean,
        val nearFrom: Float,
        val nearTo: Float,
        val farFrom: Float,
        val farTo: Float,
        val lengthPx: Float,
    ) {
        /** ΔL in pixels on [data], or null when a band has no accepted point. */
        fun extensionPx(data: FloatArray): Float? {
            val near = bandMean(data, axisX, nearFrom, nearTo, displacement = true)
            val far = bandMean(data, axisX, farFrom, farTo, displacement = true)
            return if (near == null || far == null) null else far - near
        }
    }

    /** The gauge over [data]'s accepted points, or null when they have no length along the axis. */
    fun gauge(data: FloatArray, axisX: Boolean): Gauge? {
        val bounds = DicResult.acceptedPointsBounds(data) ?: return null
        val from = if (axisX) bounds[MIN_X] else bounds[MIN_Y]
        val to = if (axisX) bounds[MAX_X] else bounds[MAX_Y]
        val band = (to - from) * BAND_FRACTION
        val nearAt = bandMean(data, axisX, from, from + band, displacement = false)
        val farAt = bandMean(data, axisX, to - band, to, displacement = false)
        // Equal bounds give equal band positions, so no gauge.
        return if (nearAt == null || farAt == null || farAt <= nearAt) {
            null
        } else {
            Gauge(axisX, from, from + band, to - band, to, farAt - nearAt)
        }
    }

    /** Mean position (or displacement) along the axis of accepted points whose reference position is in [from, to]. */
    private fun bandMean(data: FloatArray, axisX: Boolean, from: Float, to: Float, displacement: Boolean): Float? {
        val at = if (axisX) DicResult.IDX_X else DicResult.IDX_Y
        val value = when {
            !displacement -> at
            axisX -> DicResult.IDX_U
            else -> DicResult.IDX_V
        }
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + DicResult.STRIDE <= data.size) {
            val p = data[i + at]
            if (p in from..to && DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                sum += data[i + value]
                n++
            }
            i += DicResult.STRIDE
        }
        return if (n == 0) null else (sum / n).toFloat()
    }

    private const val MIN_X = 0
    private const val MIN_Y = 1
    private const val MAX_X = 2
    private const val MAX_Y = 3
}
