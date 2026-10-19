package com.sempermechanics.semper.ui.analysis.run

import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.data.mechanical.MechanicalTestInputs
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionRecordSettings

/**
 * What a re-run that wrote no record of its own left behind.
 *
 * @property settings what the run solved with, which the frames on disk now
 *   reflect
 * @property defNames the frame names on disk, as a saved run records them
 * @property videoName the clip the run's frames came from, when they did
 * @property frameTimesMs each frame's time in that clip, index-aligned with [defNames]
 * @property mechanical the lab test the run solved with: its specimen and its
 *   loads, which describe these frames and not the previous run's
 */
internal data class UnsavedRerun(
    val framesOnDisk: Int,
    val stopCode: Int,
    val plannedFrames: Int,
    val settings: SessionRecordSettings,
    val defNames: List<String>,
    val videoName: String? = null,
    val frameTimesMs: List<Long>? = null,
    val mechanical: MechanicalTestInputs = MechanicalTestInputs.NONE,
)

/**
 * What the Home row of a re-run that saved nothing should become. The run
 * deleted the previous frames before it started, so the row can no longer
 * describe them as on this phone.
 *
 * - Nothing on disk and a cloud copy: unchanged. The row reads "Only in
 *   cloud", and the cloud copy is the run it describes.
 * - Nothing on disk and no cloud copy: null, the row goes. There is no
 *   analysis left anywhere for it to open.
 * - Some frames on disk: it describes those. They are this run's, so the row
 *   takes its settings, frame names, video fields and lab test (type,
 *   dimensions, loads, as a saved run records them, with no curve
 *   correction), and is an ordinary analysis even if it was a sweep; it keeps
 *   no headline or stats from the run that is gone, and is not backed up.
 *   Upstream deleted this path (semperdic-app `6989327`); it goes at the next sync.
 */
internal fun afterUnsavedRerun(previous: SessionRecord, run: UnsavedRerun): SessionRecord? = when {
    run.framesOnDisk == 0 && previous.syncState == SessionRecord.SyncState.SYNCED -> previous
    run.framesOnDisk == 0 -> null
    else -> previous.copy(
        updatedAt = System.currentTimeMillis(),
        frameCount = run.framesOnDisk,
        subset = run.settings.subset,
        step = run.settings.step,
        strainWindow = run.settings.strainWin,
        use6x6 = run.settings.use6x6,
        roiX = run.settings.roiX,
        roiY = run.settings.roiY,
        roiW = run.settings.roiW,
        roiH = run.settings.roiH,
        defNames = run.defNames,
        videoName = run.videoName,
        frameTimesMs = run.frameTimesMs,
        testType = run.mechanical.testType,
        crossSectionMm2 = run.mechanical.crossSectionMm2,
        loadAxisX = run.mechanical.loadAxisX,
        geometry = run.mechanical.geometry,
        curveCorrection = CurveCorrection.NONE,
        loadsN = run.mechanical.loadsN.take(run.defNames.size),
        loadSource = run.mechanical.loadSource,
        loadMapping = run.mechanical.loadMapping,
        stopCode = run.stopCode,
        plannedFrameCount = run.plannedFrames,
        headline = "",
        engineStats = emptyList(),
        syncState = SessionRecord.SyncState.LOCAL_ONLY,
        sweepSubsets = emptyList(),
        sweepSteps = emptyList(),
        sweepStrainWindows = emptyList(),
        sweepLabels = emptyList(),
        sweepSkipSubsets = emptyList(),
        sweepSkipSteps = emptyList(),
        sweepSkipStrainWindows = emptyList(),
        sweepSkipCodes = emptyList(),
        sweepSkippedNodes = emptyList(),
    )
}
