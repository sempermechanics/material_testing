// What the result viewer knows about the frame at a position: the planned
// frame behind it, its name, its deformed photo, and the summary's fit box.
// Split out of ResultViewerActivity.
package com.sempermechanics.semper.ui.viewer

import com.sempermechanics.semper.report.ReportImageNames
import com.sempermechanics.semper.ui.viewer.share.ViewerReportFactory

/**
 * Same rest-fit box the summary GIF should fill. Custom ROI when set;
 * otherwise null so [com.sempermechanics.semper.ui.viewer.summary.SummaryAnimation]
 * discovers accepted points from the first readable frame.
 */
internal fun ResultViewerActivity.summaryFitBounds(): FloatArray? = roi.takeIf { it.isCustomFor(imageSize) }?.toLtrb()

/**
 * The planned frame behind the [position]-th `.dat` on disk. A frame the
 * batch skipped leaves a gap in the numbering, so the two part ways there.
 */
internal fun ResultViewerActivity.plannedFrameIndex(position: Int): Int = plannedFrames.getOrElse(position) { position }

/**
 * What the frame at [position] is called on screen: its image name (or a
 * sweep's combination label), looked up by the planned frame, not the
 * position, so a frame after a skipped one keeps its own name.
 */
internal fun ResultViewerActivity.frameDisplayName(position: Int): String {
    val planned = plannedFrameIndex(position)
    return ReportImageNames.frameName(args.frameNames, planned) ?: "Frame ${planned + 1}"
}

/**
 * The deformed image solved at [position], or null when it is not on disk.
 * Every node of a sweep solves the one deformed image. A batch looks its
 * frame up by the name the run persisted it under, since `raw_deformed/`
 * keeps the user's own file names and sorts them alphabetically, not in
 * frame order.
 */
internal fun ResultViewerActivity.deformedImagePathAt(position: Int): String? =
    ViewerReportFactory.deformedImagePath(
        args,
        isSweep,
        defImagePaths,
        args.frameNames,
        plannedFrameIndex(position),
    )
