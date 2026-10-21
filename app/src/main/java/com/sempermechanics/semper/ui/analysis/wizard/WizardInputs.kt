package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import com.sempermechanics.semper.ui.analysis.sweep.SweepFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy

/**
 * The wizard's inputs as one value: what the user picked, drew and set on its
 * pages, and the session its next run writes. [AnalysisViewModel.inputs] holds
 * it in a `StateFlow`; Main writes it, one [AnalysisViewModel.edit] per change,
 * a run reads it once as it is launched (`snapshotRunInputs`), and
 * [WizardState.save] writes the scalars into the saved-state Bundle, key for
 * key as before (`WizardStateBundleFormatTest`).
 *
 * The two byte arrays compare by identity, as a new pick is a new array; they
 * are mirrored into the draft by [AnalysisViewModel.edit] as they change (ADR-005).
 *
 * @property refBytes the reference image's bytes; null before one is picked
 * @property roiMaskBytes the ROI mask, one byte per reference pixel; null for none
 * @property deformedFrames the deformed frames, in the order the run solves
 *   them: each one's staged path, the name the user picked it as, its capture
 *   date and its pixel size. The size is measured once at import, so the match
 *   against the reference costs nothing to re-check: the engine places its grid
 *   in reference coordinates, and a frame of another size is solved anyway, to
 *   a wrong field with no error. [frameSizeError] keeps such a batch from the engine.
 * @property defOrderMode how the user wants the frames ordered (image batches only)
 * @property defOrderDirection ascending or descending, for name and date order
 * @property frameSizeError set when the frames do not all match the reference; blocks Compute
 * @property defFromVideo whether the frames were sampled from a video
 * @property defVideoName the clip the frames were sampled from; null for picked images
 * @property refSize the reference's true pixel size; [ImageSize.UNKNOWN] before one is picked
 * @property refName the reference's name, as the user picked it
 * @property hasCustomRoi true when [roi] is one the user drew, rather than the whole frame
 * @property roi the ROI as drawn, in reference pixels
 * @property step the page the wizard shows
 * @property settingsReviewed whether the settings page has been reached, which Compute needs
 * @property subsetRecommendation the subset suggested for this reference and ROI, once measured
 * @property subsetRecommendationKey the inputs [subsetRecommendation] was measured for
 * @property subsetUserModified set once the user takes the subset over from the suggestion
 * @property sweepMode true when Run sweeps the parameter space instead of solving once
 * @property sweepRanges the sweep's axes as the user set them
 * @property subsetOverlap the subset overlap every combination of a sweep shares
 * @property lineCutHorizontal true when the sweep's line cut runs along x
 * @property sweepFrame the frame the sweep is solved against
 * @property working the session folder the next run of its kind writes, while
 *   no Home row names it: the last run of that kind in this wizard saved
 *   nothing, and the next one writes there again (`openRunSession`). Null for
 *   none, so the next run mints a new one. A run reads it once, on Main, as it
 *   is launched (`snapshotRunInputs`), and its outcome sets it there
 *   (`applyRunOutcome`): the run itself never touches it. A run that saves
 *   clears it: a saved analysis is never written over (owner decision,
 *   2026-10-15). New inputs clear it too
 *   ([AnalysisViewModel.clearPreviousResults]).
 * @property lab the lab test's inputs (material_testing): the test type, the
 *   specimen and the machine loads ([LabWizardState]); its logic is in LabInputs.kt
 */
data class WizardInputs(
    val refBytes: ByteArray? = null,
    val roiMaskBytes: ByteArray? = null,
    val deformedFrames: List<DeformedFrame> = emptyList(),
    val defOrderMode: FrameOrderMode = FrameOrderMode.NAME,
    val defOrderDirection: FrameOrderDirection = FrameOrderDirection.ASCENDING,
    val frameSizeError: String? = null,
    val defFromVideo: Boolean = false,
    val defVideoName: String? = null,
    val refSize: ImageSize = ImageSize.UNKNOWN,
    val refName: String = AnalysisViewModel.NO_REFERENCE_NAME,
    val hasCustomRoi: Boolean = false,
    val roi: Roi = AnalysisViewModel.NO_ROI,
    val step: WizardStep = WizardStep.IMAGES,
    val settingsReviewed: Boolean = false,
    val subsetRecommendation: SubsetRecommender.Recommendation? = null,
    val subsetRecommendationKey: String? = null,
    val subsetUserModified: Boolean = false,
    val sweepMode: Boolean = false,
    val sweepRanges: SweepRanges = SweepRanges.UNSEEDED,
    val subsetOverlap: Double = SweepStudy.overlapForDenominator(SweepStudy.DEFAULT_STEP_DENOM),
    val lineCutHorizontal: Boolean = true,
    val sweepFrame: SweepFrame = SweepFrame.Middle,
    val working: WorkingSession? = null,
    val lab: LabWizardState = LabWizardState(),
)
