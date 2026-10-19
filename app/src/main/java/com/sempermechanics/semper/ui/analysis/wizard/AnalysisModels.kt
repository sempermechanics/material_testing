package com.sempermechanics.semper.ui.analysis.wizard

import com.sempermechanics.semper.data.session.SessionRecordSettings
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy

/**
 * The result of the last run, as ONE immutable snapshot.
 *
 * A run builds it on the native thread and hands it back in its outcome
 * ([BatchAnalysisOutcome.result]); the view model takes it on Main
 * (`applyRunOutcome`), and [AnalysisNavHelper] reads it there when building
 * the result intent. The `lastX` properties of [AnalysisViewModel] are thin
 * accessors over it.
 *
 * @property spec what the run was computed from (ADR-004); null before the first run
 * @property settings what the saved session records, once it is saved. For a
 *   sweep that is its first solved combination, not the plan's first.
 * @property sweepPlan the parameter combination behind each frame of a sweep, in order
 * @property sweepSkippedNodes the combinations the engine could not solve in a sweep
 */
@Suppress("ArrayInDataClass") // engineStats identity-compared; never used as a map key
data class RunResult(
    val batchDirPath: String? = null,
    val refPath: String? = null,
    val defPath: String? = null,
    val stop: RunStop = RunStop.Finished,
    val plannedFrames: Int = 0,
    val engineStats: FloatArray? = null,
    val spec: RunSpec? = null,
    val settings: SessionRecordSettings? = null,
    /** The Home-list id of the session the run wrote, which the viewer opens on. */
    val sessionId: String? = null,
    val sweepPlan: List<SweepStudy.Point> = emptyList(),
    val sweepSkippedNodes: List<SkippedNode> = emptyList(),
) {
    /** The settings the viewer shows: the saved session's, else the spec's (a sweep that solved nothing). */
    fun viewerSettings(): SessionRecordSettings? = settings ?: spec?.recordSettings()
}

/**
 * One tick of a batch run's progress, for the overlay.
 *
 * @property percent the whole run, 0–100
 * @property frameIndex the 0-based frame being solved; -1 while the reference
 *   is cached; [plannedFrames] once every frame is solved and the run is saving
 * @property framePercent progress within [frameIndex], 0–100
 * @property perFrameConvergence each frame's convergence once it is solved,
 *   NaN before (and for a frame that kept no points); null when this tick
 *   carries none. Always a copy: the progress flow drops old ticks, and the
 *   next frame's tick carries every frame so far, so a dropped one loses nothing.
 */
@Suppress("ArrayInDataClass") // a snapshot read once by the overlay; never compared or hashed
data class BatchProgressUpdate(
    val percent: Float,
    val status: String,
    val frameIndex: Int = -1,
    val plannedFrames: Int = 0,
    val framePercent: Float = 0f,
    val perFrameConvergence: FloatArray? = null,
)

/**
 * The session a run wrote, as the wizard's working session names it
 * (`AnalysisViewModel.working`): while no Home row names it, the next run of
 * the same kind ([sweep]) writes it again (`openRunSession`).
 */
data class WorkingSession(val id: String, val sweep: Boolean)

/** How a batch run or a sweep ended. */
data class BatchAnalysisOutcome(
    val engineErrorCode: Int,
    val firstFrameValidPoints: Int,
    /**
     * Frames that actually produced a field. Below the planned count when the
     * run stopped early — the frames before that point are kept, so this is
     * what the session holds and what the user should be told. 0 for a
     * cancelled run, which keeps none.
     */
    val totalFrames: Int,
    val executionTimeMs: Int,
    val batchDirPath: String,
    /**
     * Which frame the run stopped on, and its image name — the failure is
     * almost always a property of one image pair, so naming it is the
     * difference between an actionable message and a shrug. -1 / null when
     * the run finished.
     */
    val failedFrameIndex: Int = -1,
    val failedFrameName: String? = null,
    /**
     * Points ICGN accepted on the first frame, before outlier rejection and
     * the strain fit; -1 when that frame never reached the engine. With
     * [firstFrameValidPoints] at 0 it tells a strain window that fits no
     * point from a frame where nothing correlated.
     */
    val firstFrameCorrelatedPoints: Int = -1,
    /**
     * Whether a Home row holds this run's frames: its own record was
     * written. A run writes a record only when its first frame kept points, so
     * later frames can solve (a non-zero [totalFrames]) with nothing
     * saved; only a saved run may be told its frames are kept.
     */
    val saved: Boolean = false,
    /**
     * True when the run's record could not be saved because the session
     * index could not be read or written: not the quota, which stops the
     * run as [RunStop.SessionLimit].
     */
    val indexUnavailable: Boolean = false,
    /**
     * The session the run wrote: null when it never got one (the quota
     * blocked it) or kept nothing (it was cancelled). The view model makes it
     * its working session unless it was [saved] (`applyRunOutcome`).
     */
    val session: WorkingSession? = null,
    /** What the viewer reads of the run, for the view model to take on Main; null with no [session]. */
    val result: RunResult? = null,
) {
    /** The Home-list id of the session the run wrote; null with none. */
    val sessionId: String? get() = session?.id

    /**
     * [engineErrorCode] as the batch's stop code: [RunStop.Finished] when
     * the loop ran through every frame, whatever they kept.
     */
    val stop: RunStop get() = RunStop.fromWireCode(engineErrorCode)

    /**
     * The 1-based frame the run stopped at, numbered as the error below it
     * numbers it. The kept count is not that: a frame the engine failed on
     * is not kept, while the low-convergence stop keeps the frame it stops on.
     */
    val stoppedAtFrame: Int get() = if (failedFrameIndex >= 0) failedFrameIndex + 1 else totalFrames
}

/** What [AnalysisViewModel.restoreDraft] found: nothing to restore, the inputs back, or the draft gone. */
enum class DraftRestore { NONE, RESTORED, LOST }
