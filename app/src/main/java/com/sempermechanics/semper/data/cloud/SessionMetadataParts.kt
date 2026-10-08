package com.sempermechanics.semper.data.cloud

import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Engine
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Frame
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Node
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Roi
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Skipped
import com.sempermechanics.semper.data.cloud.SessionMetadataDoc.Sweep
import com.sempermechanics.semper.data.mechanical.loadOfFrame
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord

internal fun frameOf(record: SessionRecord, index: Int, image: String): Frame {
    val label = if (record.isSweep) {
        record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
    } else {
        "Frame_${index + 1}"
    }
    val frame = Frame(
        index = index,
        frame = label,
        image = image,
        dat = SessionPaths.frameDatName(index),
        // A frame the time match found no row for has no `loadN` at all.
        loadN = if (record.hasMachineLoads) record.loadsN.loadOfFrame(index)?.toDouble() else null,
    )
    if (!record.isSweep) return frame
    val window = record.sweepStrainWindows.getOrElse(index) { record.strainWindow }
    return frame.copy(
        subset = record.sweepSubsets.getOrElse(index) { record.subset },
        step = record.sweepSteps.getOrElse(index) { record.step },
        strainWindow = window,
        vsg = window,
    )
}

internal fun engineOf(record: SessionRecord): Engine = Engine(
    subset = record.subset,
    step = record.step,
    strainWindow = record.strainWindow,
    strainMethod = record.strainMethod,
    use6x6 = record.use6x6,
    imageWidth = record.imgW,
    imageHeight = record.imgH,
    roi = Roi(record.roiX, record.roiY, record.roiW, record.roiH),
    stats = record.engineStats,
    sweep = if (record.isSweep) {
        Sweep(
            lineCutHorizontal = record.lineCutHorizontal,
            subsets = record.sweepSubsets,
            steps = record.sweepSteps,
            strainWindows = record.sweepStrainWindows,
            labels = record.sweepLabels,
            skipped = Skipped(
                nodes = record.resolvedSkipNodes().map { Node(it.subset, it.step, it.strainWindow, it.code) },
            ),
        )
    } else {
        null
    },
)
