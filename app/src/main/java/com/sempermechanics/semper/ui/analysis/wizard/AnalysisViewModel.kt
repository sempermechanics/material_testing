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
import com.sempermechanics.semper.ui.analysis.roi.RoiResolveHelper
import com.sempermechanics.semper.ui.analysis.run.RunEnd
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

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

        /** [WizardInputs.refName] before a reference is picked. */
        const val NO_REFERENCE_NAME = "No image selected"

        /** [WizardInputs.roi] before a reference is picked. */
        val NO_ROI = Roi.full(ImageSize.UNKNOWN)
    }

    internal val sessions = SessionRepository()

    /** The view model's tie to the process's one [WizardDraft]. */
    internal val drafts = WizardDraftBinding()

    private val state = MutableStateFlow(WizardInputs())

    /**
     * The wizard's inputs as one value (TD-286, TD-308). The screen and its
     * parts read `inputs.value` and change it with one [edit] per change;
     * [WizardState] saves and restores it.
     */
    val inputs: StateFlow<WizardInputs> = state.asStateFlow()

    /**
     * Replaces [inputs] with [transform] of them, in one update: one change,
     * one emission, however many fields it touches. Main only, like every
     * wizard input: a run reads them as it is launched and writes none.
     *
     * A new reference or mask (a different array) is mirrored into the
     * [WizardDraft] here (ADR-005), so no edit can skip it.
     */
    internal fun edit(transform: (WizardInputs) -> WizardInputs) {
        var before: WizardInputs
        var after: WizardInputs
        do {
            before = state.value
            after = transform(before)
        } while (!state.compareAndSet(before, after))
        if (after.roiMaskBytes !== before.roiMaskBytes) drafts.stage { it.writeMask(after.roiMaskBytes) }
        if (after.refBytes !== before.refBytes) drafts.stage { it.writeReference(after.refBytes) }
    }

    /** The last frame list unzipped, and its lists; one list is unzipped once. */
    private var unzipped: Pair<List<DeformedFrame>, DeformedFrame.Lists> =
        emptyList<DeformedFrame>().let { it to DeformedFrame.unzip(it) }

    /** [WizardInputs.deformedFrames] as parallel lists, for the readers below. */
    private val frameLists: DeformedFrame.Lists
        get() {
            val frames = inputs.value.deformedFrames
            val last = unzipped
            if (last.first === frames) return last.second
            return DeformedFrame.unzip(frames).also { unzipped = frames to it }
        }

    /** The staged path of each of the [WizardInputs.deformedFrames]. */
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

    /**
     * Each deformed frame's time in its clip, index-aligned with [defFilePaths];
     * null unless every frame has one (picked images have none).
     */
    val defFrameTimesMs: List<Long>? get() = DeformedFrame.timesOf(inputs.value.deformedFrames)

    /** The runs' channels; [progress] and the rest are their read-only faces. */
    internal val runs = RunChannels()
    val progress: SharedFlow<BatchProgressUpdate?> = runs.progress.asSharedFlow()

    /** The batch run's end, held until the screen takes it ([PendingOutcome]). */
    internal val batchOutcome: PendingOutcome<RunEnd> get() = runs.batchOutcome

    val sweepProgress: SharedFlow<SweepStudyRunner.Progress?> = runs.sweepProgress.asSharedFlow()

    /** The sweep's end, held until the screen takes it ([PendingOutcome]). */
    internal val sweepOutcome: PendingOutcome<RunEnd> get() = runs.sweepOutcome

    fun isReadyToCompute(): Boolean = inputs.value.refBytes != null && defFilePaths.isNotEmpty()

    /** Largest odd subset the loaded image and ROI can hold. */
    fun maxSubsetForRoi(): Int =
        inputs.value.let { RoiResolveHelper.maxSubsetForRoi(it.hasCustomRoi, it.roi, it.refSize) }

    /** The sweep's step depth and the overlap it implies back to their defaults, as the settings' reset asks. */
    fun resetSweepDepth() = edit {
        it.copy(
            sweepRanges = it.sweepRanges.copy(stepDenominator = SweepStudy.DEFAULT_STEP_DENOM),
            subsetOverlap = SweepStudy.overlapForDenominator(SweepStudy.DEFAULT_STEP_DENOM),
        )
    }

    /** New inputs: the next run starts in a new session folder. */
    fun clearPreviousResults() = edit { it.copy(working = null) }

    /**
     * Takes [bytes] as the reference, from a picked image or a video's first
     * frame. Both pickers come through here, so they cannot disagree. One
     * [edit], so the screen never sees the new reference with the old ROI.
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
    fun applyNewReference(bytes: ByteArray, name: String, size: ImageSize) = edit {
        val sameSize = size == it.refSize
        val hasCustomRoi = sameSize && it.hasCustomRoi
        it.copy(
            working = null,
            hasCustomRoi = hasCustomRoi,
            roiMaskBytes = if (sameSize) it.roiMaskBytes else null,
            refSize = size,
            refName = name,
            refBytes = bytes,
            roi = if (hasCustomRoi) it.roi else Roi.full(size),
        )
    }

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
