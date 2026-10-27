// The public face of the heatmap renders. One request type, two entry points
// (a bitmap, or the index plane under it); generateFrameHeatmap keeps the
// positional form the report's fusion pass calls, so that loop stays whole.
@file:Suppress("LongParameterList")

package com.sempermechanics.semper.report

import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.HeatmapColorScale.JET_LUT
import kotlin.math.max

/**
 * Turns a full-field result array into heatmap bitmaps: grid interpolation,
 * percentile-based color scaling, and the jet colormap shared by the on-screen
 * viewer and the PDF report.
 */
object VisualizationEngine {

    /** Longest-edge cap for on-screen scrub heatmaps (export paths omit this). */
    const val DISPLAY_MAX_EDGE = 1080

    /**
     * Longest-edge cap for report/upload compositing. The PDF and cloud heatmaps
     * are downscaled to 600 px wide by [ReportBuilder.compressForPdf] anyway, so
     * this is far above the visible output — it exists purely to stop the
     * intermediate full-resolution ARGB_8888 bitmaps from OOMing on large
     * (e.g. 26 MP) references.
     */
    const val REPORT_MAX_EDGE = 1280

    /**
     * Scale factor that shrinks [imgW]×[imgH] so its longest edge is ≤ [maxEdge]
     * (1f when already within). This is the identical formula
     * [indices] uses for its own downscale, so a caller that composes
     * at `imgW*scale × imgH*scale` lines up pixel-for-pixel with the capped heatmap.
     */
    fun cappedRenderScale(imgW: Int, imgH: Int, maxEdge: Int): Float {
        val longest = max(imgW, imgH).coerceAtLeast(1)
        return if (longest > maxEdge) maxEdge.toFloat() / longest else 1f
    }

    /** [w]×[h] shrunk so its longest edge is ≤ [maxEdge]; unchanged if already within. */
    fun cappedDims(w: Int, h: Int, maxEdge: Int): ImageSize {
        val scale = cappedRenderScale(w, h, maxEdge)
        return ImageSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }

    /**
     * Palette slot for "no correlated data here" — transparent on screen, the
     * animation's background colour in a GIF. It costs the colour ramp its top
     * entry (values map to 0..[LAST_COLOR]), which is one 255th of the scale and
     * buys a single render path shared by the viewer, the report and the GIF.
     */
    const val TRANSPARENT_INDEX = 255
    internal const val LAST_COLOR = TRANSPARENT_INDEX - 1

    /**
     * One byte per pixel, each an index into the jet ramp or [TRANSPARENT_INDEX],
     * with the value range the colours were mapped against.
     */
    class IndexPlane(
        val indices: ByteArray,
        val width: Int,
        val height: Int,
        val min: Float,
        val max: Float,
    )

    /**
     * The jet ramp as a GIF global colour table: [TRANSPARENT_INDEX] takes
     * [background], every other slot is the colour the viewer would draw.
     */
    fun gifPalette(background: Int): IntArray =
        IntArray(JET_LUT.size) { if (it == TRANSPARENT_INDEX) background else JET_LUT[it] }

    /**
     * The colours values are mapped to, lowest value first: every [JET_LUT] slot
     * but [TRANSPARENT_INDEX]. Evenly spaced gradient stops over these draw a
     * colour bar that matches the map.
     */
    fun rampColors(): IntArray = JET_LUT.copyOf(LAST_COLOR + 1)

    /**
     * The displayed value range of several fields at once, in one pass over the
     * points — the same percentile-clamped bounds [render] would pick
     * for each. Null for a field with no correlated points.
     *
     * Per-frame heatmaps use this. The summary GIF widens these same ends
     * across the batch: lowest scale-min, highest scale-max.
     */
    fun valueRanges(data: FloatArray, valIndices: IntArray): Map<Int, ValueRange?> =
        valueRanges(data, data.size, valIndices, scratch = null)

    /** See [HeatmapColorScale.valueRanges]. */
    internal fun valueRanges(
        data: FloatArray,
        floatCount: Int,
        valIndices: IntArray,
        scratch: Array<FloatArray>?,
    ): Map<Int, ValueRange?> = HeatmapColorScale.valueRanges(data, floatCount, valIndices, scratch)

    /**
     * The heatmap [request] asks for: its index plane ([indices]) coloured
     * with the jet ramp, and the range the colours were mapped against.
     */
    fun render(request: HeatmapRequest): BakedHeatmap {
        val plane = indices(request)
        return BakedHeatmap(HeatmapRenderer.toBitmap(plane), plane.min, plane.max)
    }

    /**
     * The same render as [render], stopping one step earlier: one byte per
     * pixel, holding an index into the jet ramp — or [TRANSPARENT_INDEX] where
     * no correlated data covers the pixel. The summary GIF encodes these
     * directly, so its colours are the viewer's by construction.
     *
     * A [HeatmapRequest.displaced] request draws each point where it moved to,
     * (x + u, y + v), so the map lies on that frame's own photo the way the
     * plain render lies on the reference; the colour range is taken over the
     * same accepted values, so both share one scale (ADR-011, TD-141). See
     * [HeatmapRenderer.generateHeatmapIndices] and
     * [DeformedHeatmap.generateDeformedHeatmapIndices].
     */
    fun indices(request: HeatmapRequest): IndexPlane = with(request) {
        val customMin = bounds?.min
        val customMax = bounds?.max
        if (displaced) {
            DeformedHeatmap.generateDeformedHeatmapIndices(
                data,
                size.width,
                size.height,
                valIndex,
                step,
                customMin,
                customMax,
                maxLongEdge,
            )
        } else {
            HeatmapRenderer.generateHeatmapIndices(
                data,
                size.width,
                size.height,
                valIndex,
                step,
                customMin,
                customMax,
                maxLongEdge,
            )
        }
    }

    /**
     * [render] in the positional form the report's fusion pass
     * ([ReportBuilder.buildReport]) calls, kept so that loop's body stays
     * whole. Bounds apply only when both are set, as [ValueRange.custom] reads them.
     */
    fun generateFrameHeatmap(
        displaced: Boolean,
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        valIndex: Int,
        step: Int,
        customMin: Float? = null,
        customMax: Float? = null,
        maxLongEdge: Int? = null,
    ): BakedHeatmap = render(
        HeatmapRequest(
            data = data,
            size = ImageSize(imgW, imgH),
            valIndex = valIndex,
            step = step,
            bounds = ValueRange.custom(customMin, customMax),
            maxLongEdge = maxLongEdge,
            displaced = displaced,
        ),
    )
}
