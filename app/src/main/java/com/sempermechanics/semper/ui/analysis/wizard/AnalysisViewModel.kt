package com.sempermechanics.semper.ui.analysis.wizard
import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.sempermechanics.semper.SemperNativeLib
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.data.session.SessionRepository
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommender
import com.sempermechanics.semper.ui.analysis.roi.RoiResolveHelper
import com.sempermechanics.semper.ui.analysis.run.RunEnd
import com.sempermechanics.semper.ui.analysis.sweep.SweepFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds analysis inputs/state across configuration changes and runs the
 * batch solve: streams each deformed frame through the native engine
 * ([SemperNativeLib]) on a dedicated thread, writes per-frame `.dat`
 * results, and enqueues cloud sync via DicUploadWorker.
 */
class AnalysisViewModel(private val saved: SavedStateHandle) : ViewModel() {

    /** With an empty [SavedStateHandle], for tests; the Activity's factory passes its own. */
    @VisibleForTesting
    constructor() : this(SavedStateHandle())

    companion object {
        /** Below this, the correlation has effectively lost the speckle. */
        const val MIN_CONVERGENCE_PERCENT = 50f

        /**
         * How many consecutive low-convergence frames end the run. One bad frame
         * can be a transient — a flash, a knock — so a single strike would abort
         * runs that would have recovered.
         */
        const val LOW_CONVERGENCE_STRIKES = 2

        /** [refName] before a reference is picked. */
        const val NO_REFERENCE_NAME = "No image selected"

        /** [roi] before a reference is picked. */
        val NO_ROI = Roi.full(ImageSize.UNKNOWN)
    }

    internal val sessions = SessionRepository()

    /** The view model's tie to the process's one [WizardDraft]. */
    internal val drafts = WizardDraftBinding()

    private val state = MutableStateFlow(WizardInputs())

    /**
     * The wizard's inputs as one value (TD-286). Each property below reads
     * one field of it and writes it with one [edit]; [WizardState] saves and
     * restores it.
     */
    val inputs: StateFlow<WizardInputs> = state.asStateFlow()

    /**
     * Replaces [inputs] with [transform] of them, in one `update`. Main only,
     * like every wizard input: a run reads them as it is launched and writes
     * none. The draft mirror is the setters' ([refBytes], [roiMaskBytes]).
     */
    internal fun edit(transform: (WizardInputs) -> WizardInputs) = state.update(transform)

    /** Mirrored into the [WizardDraft] as it changes (ADR-005). */
    var refBytes: ByteArray?
        get() = inputs.value.refBytes
        set(value) {
            edit { it.copy(refBytes = value) }
            drafts.stage { it.writeReference(value) }
        }

    /** Mirrored into the [WizardDraft] as it changes (ADR-005). */
    var roiMaskBytes: ByteArray?
        get() = inputs.value.roiMaskBytes
        set(value) {
            edit { it.copy(roiMaskBytes = value) }
            drafts.stage { it.writeMask(value) }
        }

    /**
     * The deformed frames, in the order the run solves them: each one's staged
     * path, the name the user picked it as, its capture date and its pixel size.
     *
     * The size is measured once at import (the bytes are already in hand
     * there) so the match against the reference costs nothing to re-check
     * later. The engine places its grid in *reference* coordinates and reads
     * the deformed image at them; a frame of a different size skips the
     * phase-correlation seed and is solved anyway, giving a wrong field with no
     * error. [frameSizeError] is what stops such a batch from ever reaching the
     * engine.
     */
    var deformedFrames: List<DeformedFrame>
        get() = inputs.value.deformedFrames
        set(value) = edit { it.copy(deformedFrames = value) }

    /** The last [deformedFrames] unzipped, and its lists; one list is unzipped once. */
    private var unzipped: Pair<List<DeformedFrame>, DeformedFrame.Lists> =
        emptyList<DeformedFrame>().let { it to DeformedFrame.unzip(it) }

    /** [deformedFrames] as parallel lists, for the readers below. */
    private val frameLists: DeformedFrame.Lists
        get() {
            val frames = deformedFrames
            val last = unzipped
            if (last.first === frames) return last.second
            return DeformedFrame.unzip(frames).also { unzipped = frames to it }
        }

    /** The staged path of each of [deformedFrames]. */
    val defFilePaths: List<String> get() = frameLists.paths

    /** Original picked filenames, index-aligned with [defFilePaths]. */
    val defOriginalNames: List<String> get() = frameLists.names

    /** Pixel size of each measured frame, keyed by its path in [defFilePaths]. */
    val defFrameSizes: Map<String, Pair<Int, Int>> get() = frameLists.sizes

    /**
     * Best-effort capture/creation time per deformed frame, index-aligned with
     * [defFilePaths]. [DeformedFrame.UNKNOWN_DATE] sorts last by date.
     */
    val defFrameDates: List<Long> get() = frameLists.dates

    /** How the user wants deformed frames ordered (image batches only). */
    var defOrderMode: FrameOrderMode
        get() = inputs.value.defOrderMode
        set(value) = edit { it.copy(defOrderMode = value) }

    /** Ascending/descending for Name and Date modes. */
    var defOrderDirection: FrameOrderDirection
        get() = inputs.value.defOrderDirection
        set(value) = edit { it.copy(defOrderDirection = value) }

    /** Set when loaded frames do not all match the reference; blocks Compute. */
    var frameSizeError: String?
        get() = inputs.value.frameSizeError
        set(value) = edit { it.copy(frameSizeError = value) }

    /**
     * Whether [defFilePaths] came from sampling a video rather than picked
     * images. Video frames are extracted to image files, so the two sources are
     * indistinguishable by the time the UI shows them — this is what lets the
     * deformed-frames card show an icon that matches what the user chose.
     */
    var defFromVideo: Boolean
        get() = inputs.value.defFromVideo
        set(value) = edit { it.copy(defFromVideo = value) }

    /** The clip [defFilePaths] were sampled from ("tensile_03", or "Video"); null for picked images. */
    var defVideoName: String?
        get() = inputs.value.defVideoName
        set(value) = edit { it.copy(defVideoName = value) }

    /**
     * Each deformed frame's time in its clip, index-aligned with [defFilePaths];
     * null unless every frame has one (picked images have none).
     */
    val defFrameTimesMs: List<Long>? get() = DeformedFrame.timesOf(deformedFrames)

    /** The reference's true pixel size, as the engine measures it; [ImageSize.UNKNOWN] before one is picked. */
    var refSize: ImageSize
        get() = inputs.value.refSize
        set(value) = edit { it.copy(refSize = value) }

    var realRefWidth: Int
        get() = refSize.width
        set(value) {
            refSize = refSize.copy(width = value)
        }
    var realRefHeight: Int
        get() = refSize.height
        set(value) {
            refSize = refSize.copy(height = value)
        }
    var refName: String
        get() = inputs.value.refName
        set(value) = edit { it.copy(refName = value) }

    val defCount: Int get() = deformedFrames.size

    /** True when [roi] is one the user drew, rather than the whole frame. */
    var hasCustomRoi: Boolean
        get() = inputs.value.hasCustomRoi
        set(value) = edit { it.copy(hasCustomRoi = value) }

    /** The ROI as drawn, in reference pixels; the run solves [Roi.forSolve] of it. */
    var roi: Roi
        get() = inputs.value.roi
        set(value) = edit { it.copy(roi = value) }

    var roiX: Int
        get() = roi.x
        set(value) {
            roi = roi.copy(x = value)
        }
    var roiY: Int
        get() = roi.y
        set(value) {
            roi = roi.copy(y = value)
        }
    var roiW: Int
        get() = roi.w
        set(value) {
            roi = roi.copy(w = value)
        }
    var roiH: Int
        get() = roi.h
        set(value) {
            roi = roi.copy(h = value)
        }

    /** The runs' channels; [progress] and the rest are their read-only faces. */
    internal val runs = RunChannels()
    val progress: SharedFlow<BatchProgressUpdate?> = runs.progress.asSharedFlow()

    /** The batch run's end, held until the screen takes it ([PendingOutcome]). */
    internal val batchOutcome: PendingOutcome<RunEnd> get() = runs.batchOutcome

    /** The page the wizard shows. */
    var step: WizardStep
        get() = inputs.value.step
        set(value) = edit { it.copy(step = value) }

    /** [step]'s number, as the saved state holds it. */
    var wizardStep: Int
        get() = step.number
        set(value) {
            step = WizardStep.of(value)
        }

    var settingsReviewed: Boolean
        get() = inputs.value.settingsReviewed
        set(value) = edit { it.copy(settingsReviewed = value) }

    /**
     * Subset size suggested by [SubsetRecommender] for the current reference
     * image + ROI, or null while it has not been computed. It seeds the subset
     * slider until [subsetUserModified] says the user has taken it over.
     */
    var subsetRecommendation: SubsetRecommender.Recommendation?
        get() = inputs.value.subsetRecommendation
        set(value) = edit { it.copy(subsetRecommendation = value) }

    /** Identifies the inputs [subsetRecommendation] was computed for. */
    var subsetRecommendationKey: String?
        get() = inputs.value.subsetRecommendationKey
        set(value) = edit { it.copy(subsetRecommendationKey = value) }

    /** Set once the user drags or types a subset size; suppresses re-seeding. */
    var subsetUserModified: Boolean
        get() = inputs.value.subsetUserModified
        set(value) = edit { it.copy(subsetUserModified = value) }

    // ------------------------------------------------------------------
    // Virtual strain gauge study (see [SweepStudy])
    // ------------------------------------------------------------------

    /** True when Run should sweep the parameter space instead of solving once. */
    var sweepMode: Boolean
        get() = inputs.value.sweepMode
        set(value) = edit { it.copy(sweepMode = value) }

    /**
     * The sweep's axes as the user set them; [SweepRanges.UNSEEDED] until a
     * recommendation or default seeds the ranges. The seven properties below
     * read and write one axis each.
     */
    var sweepRanges: SweepRanges
        get() = inputs.value.sweepRanges
        set(value) = edit { it.copy(sweepRanges = value) }

    /** Smallest subset size of the sweep; 0 until a recommendation seeds it. */
    var subsetMin: Int
        get() = sweepRanges.subsetMin
        set(value) {
            sweepRanges = sweepRanges.copy(subsetMin = value)
        }

    /** Largest subset size of the sweep; 0 until a recommendation seeds it. */
    var subsetMax: Int
        get() = sweepRanges.subsetMax
        set(value) {
            sweepRanges = sweepRanges.copy(subsetMax = value)
        }

    /** Smallest strain window of the sweep; 0 until a default seeds it. */
    var strainWinMin: Int
        get() = sweepRanges.strainWinMin
        set(value) {
            sweepRanges = sweepRanges.copy(strainWinMin = value)
        }

    /** Largest strain window of the sweep; 0 until a default seeds it. */
    var strainWinMax: Int
        get() = sweepRanges.strainWinMax
        set(value) {
            sweepRanges = sweepRanges.copy(strainWinMax = value)
        }

    /** How many subset sizes the sweep samples across the subset range (x axis). */
    var subsetSamples: Int
        get() = sweepRanges.subsetSamples
        set(value) {
            sweepRanges = sweepRanges.copy(subsetSamples = value)
        }

    /** How many strain window sizes the sweep samples up to [strainWinMax] (y axis). */
    var strainWinSamples: Int
        get() = sweepRanges.strainWinSamples
        set(value) {
            sweepRanges = sweepRanges.copy(strainWinSamples = value)
        }

    /**
     * Step-depth denominator: one N for every subset, `step = round(subset / N)`.
     * 2 = half the subset (coarsest); 9 is the finest. Default 3.
     */
    var stepDenominator: Int
        get() = sweepRanges.stepDenominator
        set(value) {
            sweepRanges = sweepRanges.copy(stepDenominator = value)
        }

    /**
     * Subset overlap shared by every combination in a sweep (`1 − step/subset`).
     * Kept in sync with [stepDenominator] (`1 − 1/N`).
     */
    var subsetOverlap: Double
        get() = inputs.value.subsetOverlap
        set(value) = edit { it.copy(subsetOverlap = value) }

    /** True when the line cut runs along x; false for a cut along y. Editing state; a run reads its `RunSpec`. */
    var lineCutHorizontal: Boolean
        get() = inputs.value.lineCutHorizontal
        set(value) = edit { it.copy(lineCutHorizontal = value) }

    /** The frame the sweep is solved against: the middle of the sequence until one is picked. */
    var sweepFrame: SweepFrame
        get() = inputs.value.sweepFrame
        set(value) = edit { it.copy(sweepFrame = value) }

    val sweepProgress: SharedFlow<SweepStudyRunner.Progress?> = runs.sweepProgress.asSharedFlow()

    /** The sweep's end, held until the screen takes it ([PendingOutcome]). */
    internal val sweepOutcome: PendingOutcome<RunEnd> get() = runs.sweepOutcome

    fun isReadyToCompute(): Boolean = refBytes != null && defFilePaths.isNotEmpty()

    /** Largest odd subset the loaded image and ROI can hold. */
    fun maxSubsetForRoi(): Int = RoiResolveHelper.maxSubsetForRoi(
        hasCustomRoi = hasCustomRoi,
        roiX = roiX,
        roiY = roiY,
        roiW = roiW,
        roiH = roiH,
        realRefWidth = realRefWidth,
        realRefHeight = realRefHeight,
    )

    /** The sweep's step depth and the overlap it implies back to their defaults, as the settings' reset asks. */
    fun resetSweepDepth() {
        stepDenominator = SweepStudy.DEFAULT_STEP_DENOM
        subsetOverlap = SweepStudy.overlapForDenominator(SweepStudy.DEFAULT_STEP_DENOM)
    }

    /** New inputs: the next run starts in a new session folder. */
    fun clearPreviousResults() {
        working = null
    }

    /**
     * Takes [bytes] as the reference, from a picked image or a video's first
     * frame. Both pickers come through here, so they cannot disagree.
     *
     * - **A new input.** Like a new set of frames, it resets the previous
     *   results, so the next run starts in a new session folder.
     * - **The ROI and mask follow the pixel size.** Both are in the pixels of
     *   the image they were drawn on (the mask is one byte per pixel). A
     *   reference of a different size drops them and goes back to the full
     *   frame. One of the same size keeps them: that is another shot of the
     *   same set-up, and the user's crop still lands where they drew it.
     *   [Roi.forSolve] clips whatever is kept to the image anyway.
     */
    fun applyNewReference(bytes: ByteArray, name: String, size: ImageSize) {
        val sameSize = size == refSize
        clearPreviousResults()
        if (!sameSize) {
            hasCustomRoi = false
            roiMaskBytes = null
        }
        refSize = size
        refName = name
        refBytes = bytes
        if (!hasCustomRoi) roi = Roi.full(size)
    }

    /**
     * The session folder the next run of its kind writes, while no Home row
     * names it: the last run of that kind in this wizard saved nothing, and
     * the next one writes there again (`openRunSession`). Null for none, so
     * the next run mints a new one. A run reads it once, on Main, as it is
     * launched (`snapshotRunInputs`), and its outcome sets it there
     * (`applyRunOutcome`): the run itself never touches it. A run that saves
     * clears it: a saved analysis is never written over (owner decision,
     * 2026-10-15). New inputs clear it too ([clearPreviousResults]).
     */
    internal var working: WorkingSession?
        get() = inputs.value.working
        set(value) = edit { it.copy(working = value) }

    /**
     * Cooperative cancel: checked between frames here, and forwarded to the
     * engine, which polls it inside its point loops. Setting it therefore stops
     * the solve already running rather than only the ones after it.
     * Shared with [SweepStudyRunner] via [AnalysisCancel].
     */
    var cancelRequested: Boolean
        get() = AnalysisCancel.requested
        set(value) {
            AnalysisCancel.requested = value
        }

    // Process death (ADR-005): the scalars in [saved], the rest in the draft

    /** Starts mirroring the inputs into [target]; see [WizardDraftBinding.attach]. */
    fun attachDraft(target: WizardDraft) = drafts.attach(target)

    /**
     * Finishes what [init] began after a process death: reads the reference,
     * mask and frame list back from the draft. [DraftRestore.LOST] when any of
     * them is gone; the inputs are then reset to an empty step 1.
     */
    suspend fun restoreDraft(): DraftRestore = restoreFromDraft()

    /** The wizard was left for good: nothing will restore from the draft. See [WizardDraftBinding.discard]. */
    fun discardDraft() = drafts.discard()

    init {
        // Last in the class, so the restored values land after every
        // property initializer above has run, not before.
        drafts.pendingRestore = saved.get<Bundle>(WizardState.KEY)?.also { WizardState.restoreScalars(this, it) }
        saved.setSavedStateProvider(WizardState.KEY) { saveWizardState() }
    }
}
