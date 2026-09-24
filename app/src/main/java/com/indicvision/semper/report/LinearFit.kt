package com.indicvision.semper.report

/**
 * Ordinary least-squares straight line, y = slope·x + intercept, with its
 * coefficient of determination. Shared by the tensile modulus (stress on
 * strain) and the bending load–deflection slope. Pure; doubles throughout so
 * a dozen lab points fit without float cancellation.
 */
object LinearFit {

    data class Line(val slope: Double, val intercept: Double, val r2: Double, val n: Int) {
        fun at(x: Double): Double = slope * x + intercept
    }

    /**
     * The fitted line, or null with fewer than two points, mismatched arrays,
     * or no spread in x. When y has no spread every point is on the line, so
     * R² is 1.
     */
    fun fit(xs: DoubleArray, ys: DoubleArray): Line? =
        if (xs.size < 2 || ys.size != xs.size) null else fitPaired(xs, ys)

    private fun fitPaired(xs: DoubleArray, ys: DoubleArray): Line? {
        val n = xs.size
        val meanX = xs.average()
        val meanY = ys.average()
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - meanX
            val dy = ys[i] - meanY
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        if (sxx <= EPSILON * (1.0 + meanX * meanX)) return null
        val slope = sxy / sxx
        val r2 = if (syy <= 0.0) 1.0 else (sxy * sxy) / (sxx * syy)
        return Line(slope, meanY - slope * meanX, r2, n)
    }

    private const val EPSILON = 1e-18
}
