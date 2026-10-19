package com.sempermechanics.semper.field

/**
 * A colour-scale range in a field's stored units: the auto p02/p98 span or
 * the user's custom bounds.
 *
 * The engine's per-frame pass (`VisualizationEngine.valueRanges`) and the
 * batch run that writes it into its sidecar (`FieldRangesStore`), the
 * summary animation's ranges (`SummaryAnimation.globalRanges`,
 * `ShareCenter.Snapshot.summaryBounds`), the viewer's custom bounds and a
 * heatmap request's bounds (`HeatmapRequest.bounds`) all carry it. No
 * ordering is enforced: a stored range is read back as it was written.
 */
data class ValueRange(val min: Float, val max: Float) {

    companion object {
        /**
         * The user's custom range, or null unless both bounds are set: the
         * `customMin != null && customMax != null` test `VisualizationEngine` makes.
         */
        fun custom(min: Float?, max: Float?): ValueRange? =
            if (min != null && max != null) ValueRange(min, max) else null
    }
}
