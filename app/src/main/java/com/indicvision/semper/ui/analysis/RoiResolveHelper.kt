// ROI resolution takes the full image+ROI geometry set and clamps it with the
// engine's literal edge buffers; both read clearest passed/inlined directly.
@file:Suppress("LongParameterList", "MagicNumber")

package com.indicvision.semper.ui.analysis

/**
 * Pure ROI math for the analysis wizard: inset a full-frame solve so subsets
 * stay on-image, and cap subset size so the engine still has grid points after
 * its edge buffer.
 */
object RoiResolveHelper {

    /**
     * Clearance the engine demands around a grid point on top of half its
     * subset: 4 px of interpolation buffer plus a 15 px deformation buffer
     * (SemperJNI.cpp, `absolute_boundary_buffer`). Points inside it are
     * dropped, and a solve with no points left returns an ROI engine error.
     */
    const val ENGINE_EDGE_BUFFER_PX = 19

    /** Slack [resolve] adds beyond half a subset when insetting a frame. */
    const val ROI_MARGIN_SLACK_PX = 10

    /**
     * Returns `[x, y, w, h]` for the rectangle the engine solves over, or null
     * if it cannot hold one [subset]-sized window.
     */
    fun resolve(
        subset: Int,
        hasCustomRoi: Boolean,
        roiX: Int,
        roiY: Int,
        roiW: Int,
        roiH: Int,
        realRefWidth: Int,
        realRefHeight: Int,
    ): IntArray? {
        val roi = if (hasCustomRoi) {
            intArrayOf(roiX, roiY, roiW, roiH)
        } else {
            val margin = (subset / 2) + ROI_MARGIN_SLACK_PX
            intArrayOf(
                margin,
                margin,
                realRefWidth - (2 * margin),
                realRefHeight - (2 * margin),
            )
        }
        if (roi[2] < subset || roi[3] < subset) return null
        return roi
    }

    /**
     * Largest odd subset the loaded image and ROI can actually hold, given
     * [ENGINE_EDGE_BUFFER_PX]. Kept inside [SubsetRecommender]'s slider range.
     */
    fun maxSubsetForRoi(
        hasCustomRoi: Boolean,
        roiW: Int,
        roiH: Int,
        realRefWidth: Int,
        realRefHeight: Int,
    ): Int {
        val w = realRefWidth
        val h = realRefHeight
        if (w <= 0 || h <= 0) return SubsetRecommender.MAX_SUBSET
        val fits = if (hasCustomRoi) {
            minOf(roiW, roiH) - 2 * ENGINE_EDGE_BUFFER_PX
        } else {
            // Full frame is inset by (subset/2 + slack) a side and must still be
            // one subset wide: imgW - 2*(s/2 + slack) >= s  =>  s <= imgW/2 - slack.
            minOf(w, h) / 2 - ROI_MARGIN_SLACK_PX
        }
        return (fits - 1 or 1).coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET)
    }
}
