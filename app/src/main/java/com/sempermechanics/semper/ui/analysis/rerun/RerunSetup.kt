package com.sempermechanics.semper.ui.analysis.rerun

import androidx.annotation.MainThread
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel

/**
 * The part of the wizard a Re-run needs back that the run's spec and images
 * do not carry: the ROI as drawn, each frame's date, size and clip time, the
 * frame order, and the sweep's axes. Frozen on Main with the run's images
 * (`snapshotRunInputs`), so the run on the native thread reads none of the
 * wizard's fields (TD-173).
 */
internal class RerunSetup(
    val hasCustomRoi: Boolean,
    val roi: Roi,
    val frames: List<DeformedFrame>,
    val fromVideo: Boolean,
    val order: Order,
    val sweep: Sweep,
) {
    /** The frame order the user picked on step 1. */
    class Order(val mode: FrameOrderMode, val direction: FrameOrderDirection)

    /** The sweep's ranges and subset overlap, as set on the sweep page. */
    class Sweep(val ranges: SweepRanges, val subsetOverlap: Double)
}

/** The wizard's [RerunSetup] as it stands now; the frame list is copied. */
@MainThread
internal fun AnalysisViewModel.snapshotRerunSetup(): RerunSetup = inputs.value.let { now ->
    RerunSetup(
        hasCustomRoi = now.hasCustomRoi,
        roi = now.roi,
        frames = now.deformedFrames.toList(),
        fromVideo = now.defFromVideo,
        order = RerunSetup.Order(now.defOrderMode, now.defOrderDirection),
        sweep = RerunSetup.Sweep(now.sweepRanges, now.subsetOverlap),
    )
}
