package com.sempermechanics.semper.report

import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.ValueRange

/**
 * One heatmap render ([VisualizationEngine.render], [VisualizationEngine.indices]):
 * the field at [valIndex] of [data], the grid it was solved on, and how to
 * colour and size the map. It replaces the eight positional parameters each
 * of the engine's five entry points took.
 *
 * Not a data class: it holds the frame's array, which is compared by identity.
 */
@Suppress("LongParameterList") // a request is its parameters, named at every call
class HeatmapRequest(
    val data: FloatArray,
    /** The reference image's size; the map covers it. */
    val size: ImageSize,
    val valIndex: Int,
    /** Grid pitch between solved points, px. */
    val step: Int,
    /** A fixed colour range; null for the frame's own percentile-clamped range. */
    val bounds: ValueRange? = null,
    /** When set and smaller than the image's longest edge, the map is rendered at that cap. */
    val maxLongEdge: Int? = null,
    /** Each point drawn where it moved to: the frame shown on its own photo (ADR-011). */
    val displaced: Boolean = false,
)
