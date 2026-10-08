package com.sempermechanics.semper.ui.analysis.sweep

import com.sempermechanics.semper.ui.analysis.sweep.SweepPlotView.Sample

internal fun SweepPlotView.hasFrame(): Boolean = frame.right > frame.left && frame.bottom > frame.top

/** Inside the plot area, edges included. */
internal fun SweepPlotView.contains(x: Float, y: Float): Boolean =
    x in frame.left..frame.right && y in frame.top..frame.bottom

internal fun SweepPlotView.updateScrub(xPx: Float, b: PlotBounds) {
    val clamped = xPx.coerceIn(frame.left, frame.right)
    val ratio = (clamped - frame.left) / (frame.right - frame.left)
    emitScrub(b.xMin + ratio * (b.xMax - b.xMin), b)
}

/** Moves the scrub to [fraction] (0..1) of the current viewport — for a slider. */
fun SweepPlotView.scrubToFraction(fraction: Float) {
    val full = dataBounds() ?: return
    if (!hasFrame()) return
    val vp = viewport.visible(full)
    emitScrub(vp.xMin + fraction.coerceIn(0f, 1f) * (vp.xMax - vp.xMin), vp)
}

/** Sets the scrub at data-x [xData] and reports it to [onScrub] / [onScrubMove]. */
internal fun SweepPlotView.emitScrub(xData: Float, vp: PlotBounds) {
    scrubX = xData
    val values = series
        .filterNot { it.muted }
        .mapNotNull { entry ->
            interpolateY(entry.points, xData)?.let { y -> Sample(entry.label, y, entry.color) }
        }
    onScrub?.invoke(xData, values)
    val span = vp.xMax - vp.xMin
    onScrubMove?.invoke(if (span > 0f) ((xData - vp.xMin) / span).coerceIn(0f, 1f) else 0f)
    invalidate()
}

internal fun SweepPlotView.clearScrub() {
    if (scrubX == null) return
    scrubX = null
    onScrub?.invoke(Float.NaN, emptyList())
    onScrubMove?.invoke(Float.NaN)
    invalidate()
}
