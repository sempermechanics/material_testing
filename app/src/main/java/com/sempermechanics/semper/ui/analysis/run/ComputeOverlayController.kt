// Overlay helper binds a fixed set of named views; the constructor list and the
// small progress/animation constants read clearest passed and inlined directly.
@file:Suppress("LongParameterList")

package com.sempermechanics.semper.ui.analysis.run

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityStaticAnalysisBinding
import com.sempermechanics.semper.ui.analysis.sweep.LiveSweepLattice
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.BatchProgressUpdate
import com.sempermechanics.semper.ui.common.EtaEstimator
import com.sempermechanics.semper.ui.common.Insets
import kotlin.math.roundToInt

/**
 * Owns the compute / import progress sheet, docked at the bottom of the
 * wizard over a light scrim that still takes every touch: visibility, the
 * header (what is running, with its percent), the line under it (the run's
 * reference and the time left), and below it, for a batch run, the
 * convergence-per-frame bins with their caption and one-strike warning; for a
 * sweep, the lattice filling in as combinations end, the combination being
 * solved and how the last one went; for an import or a video extraction, a
 * progress bar and its status line. Cancel sits at the end of the sheet.
 *
 * Once a single run has solved, [showDone] holds the sheet for a moment with
 * a check, the frame count, the time and the lowest convergence.
 *
 * Progress ticks are coalesced (~100 ms) into a single main-thread post that
 * updates all wired widgets together.
 */
class ComputeOverlayController(
    private val overlay: View,
    private val header: TextView,
    private val percent: TextView,
    private val status: TextView,
    private val eta: TextView,
    private val bar: ProgressBar? = null,
    private val bins: ConvergenceBinsView? = null,
    private val strikeWarn: TextView? = null,
    private val sweepLattice: LiveSweepLattice? = null,
    private val binsCaption: View? = null,
    private val doneIcon: View? = null,
    private val cancel: View? = null,
) {
    /**
     * The wizard's overlay, with its run detail. The sheet's bottom padding
     * takes the navigation bar's inset, so Cancel stays clear of the gesture bar.
     */
    constructor(wizard: ActivityStaticAnalysisBinding) : this(
        overlay = wizard.computeOverlay,
        header = wizard.tvRunHeader,
        percent = wizard.tvRunPercent,
        status = wizard.overlayStatus,
        eta = wizard.tvRunEta,
        bar = wizard.overlayBar,
        bins = wizard.runBins,
        strikeWarn = wizard.tvRunStrikeWarn,
        sweepLattice = LiveSweepLattice(wizard.runSweepGroup, wizard.runSweepLattice, wizard.tvRunSweepLast),
        binsCaption = wizard.tvRunBinsCaption,
        doneIcon = wizard.ivRunDone,
        cancel = wizard.btnRunCancel,
    ) {
        Insets.padBottom(wizard.runSheet)
    }

    private val res get() = overlay.resources
    private val mainHandler = Handler(Looper.getMainLooper())
    private val etaEstimator = EtaEstimator()

    /** Wall-clock start of what the overlay shows; the batch run times itself from it. */
    var processingStartTime: Long = 0

    // Coalesced pending state (any thread may write; flushed on main).
    @Volatile private var pendingPercent: Float? = null

    @Volatile private var pendingStatus: String? = null

    @Volatile private var pendingTitle: String? = null

    @Volatile private var pendingRun: BatchProgressUpdate? = null

    @Volatile private var pendingSweep: SweepStudyRunner.Progress? = null

    /** The run's reference name, for the line under the header; null for an import or a sweep. */
    private var reference: String? = null

    // The run's latest convergence snapshot. Only frame starts and ends carry
    // one; the engine's in-frame ticks between them do not, and each flush
    // clears pendingRun, so the snapshot has to outlive it. It outlives hide()
    // too: the run's chrome comes down before its outcome is handled, and by
    // then the view model's progress replay is null (the run's finally), so
    // this is where showDone finds the run's convergence. show() clears it.
    @Volatile private var lastConvergence: FloatArray? = null

    private var lastFlushUptimeMs = 0L
    private var flushScheduled = false

    private val flushRunnable = Runnable {
        flushScheduled = false
        applyPending()
    }

    /**
     * Shows the overlay from zero with [title] in the header. [showConvergence]
     * is for a batch run: the bins, whose header the ticks then write, and the
     * run's [reference] name before the time left. A sweep passes its
     * [sweepPlan] instead and gets the lattice. An import and a video
     * extraction get the progress bar and [status] under it.
     */
    fun show(
        title: String = res.getString(R.string.initializing_engine),
        status: String = "",
        showConvergence: Boolean = true,
        sweepPlan: List<SweepStudy.Point> = emptyList(),
        reference: String? = null,
    ) {
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        clearPending()
        lastConvergence = null
        etaEstimator.reset()
        this.reference = reference
        header.text = title
        percent.text = RunOverlayText.percent(0f)
        percent.visibility = View.VISIBLE
        doneIcon?.visibility = View.GONE
        cancel?.visibility = View.VISIBLE
        this.status.showText(status.takeUnless { showConvergence || it.isEmpty() })
        eta.text = RunOverlayText.subline(res, reference, res.getString(R.string.eta_estimating))
        bar?.apply {
            max = BAR_MAX
            progress = 0
            visibility = if (!showConvergence && sweepPlan.isEmpty()) View.VISIBLE else View.GONE
        }
        // Reset the run detail too, so a re-run doesn't flash the PREVIOUS run's
        // bins and warning until its first frame completes.
        strikeWarn?.visibility = View.GONE
        bins?.clear()
        bins?.visibility = if (showConvergence) View.VISIBLE else View.GONE
        binsCaption?.visibility = bins?.visibility ?: View.GONE
        sweepLattice?.show(sweepPlan)
        overlay.visibility = View.VISIBLE
    }

    /**
     * A single run has solved: the sheet, back up after the run's chrome came
     * down, shows a check, "5 frames solved" and "7 s · lowest 94% converged"
     * from [executionTimeMs] and [convergence]: each frame's, by default the
     * last snapshot this run's ticks carried (the bins are redrawn from them).
     * Cancel's place is kept empty; the caller [hide]s the sheet when it moves on.
     */
    fun showDone(frames: Int, executionTimeMs: Int, convergence: FloatArray? = lastConvergence) {
        hide()
        header.text = RunOverlayText.doneHeader(res, frames)
        eta.text = RunOverlayText.doneDetail(res, executionTimeMs, convergence)
        percent.visibility = View.GONE
        doneIcon?.visibility = View.VISIBLE
        cancel?.visibility = View.INVISIBLE
        status.visibility = View.GONE
        strikeWarn?.visibility = View.GONE
        if (convergence != null) bins?.setRun(convergence.size, convergence.size, PERCENT, convergence)
        overlay.visibility = View.VISIBLE
    }

    fun hide() {
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        clearPending()
        overlay.visibility = View.GONE
    }

    /**
     * Drop every pending main-thread callback. Call from the host's onDestroy so
     * a queued flush cannot fire against a dead view hierarchy after the
     * Activity is gone.
     */
    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        flushScheduled = false
        clearPending()
    }

    /** An import or extraction tick: percent and bar, status, header. Safe to call from any thread. */
    fun update(percent: Float? = null, status: String? = null, title: String? = null) {
        if (percent != null) pendingPercent = percent.coerceIn(0f, PERCENT)
        if (status != null) pendingStatus = status
        if (title != null) pendingTitle = title
        scheduleFlush()
    }

    /**
     * A batch run's tick. The overlay words its own header from the frame
     * fields; [BatchProgressUpdate.status] is the engine-side log line.
     */
    fun updateRun(tick: BatchProgressUpdate) {
        pendingPercent = tick.percent.coerceIn(0f, PERCENT)
        tick.perFrameConvergence?.let { lastConvergence = it }
        pendingRun = tick
        scheduleFlush()
    }

    /** A sweep's tick: the percent, the header, the status and the lattice. */
    fun updateSweep(tick: SweepStudyRunner.Progress) {
        pendingPercent = tick.percent.toFloat().coerceIn(0f, PERCENT)
        pendingSweep = tick
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        val now = SystemClock.uptimeMillis()
        val elapsedSince = now - lastFlushUptimeMs
        // Post through mainHandler, not overlay.post: show()/hide()/release() cancel
        // the flush via mainHandler.removeCallbacks, and a View's post() enqueues on
        // a different Handler instance, so those cancels would silently miss it and
        // a queued flush could still fire into a torn-down view hierarchy.
        if (elapsedSince >= THROTTLE_MS) {
            mainHandler.post(flushRunnable)
        } else {
            mainHandler.postDelayed(flushRunnable, THROTTLE_MS - elapsedSince)
        }
    }

    private fun applyPending() {
        lastFlushUptimeMs = SystemClock.uptimeMillis()
        pendingPercent?.let {
            bar?.progress = (it * BAR_SCALE).roundToInt().coerceIn(0, BAR_MAX)
            percent.text = RunOverlayText.percent(it)
            val left = etaEstimator.sample(it / PERCENT.toDouble(), SystemClock.elapsedRealtime())
            val label = EtaEstimator.label(res, left) ?: res.getString(R.string.eta_estimating)
            eta.text = RunOverlayText.subline(res, reference, label)
            pendingPercent = null
        }
        pendingStatus?.let {
            status.showText(it)
            pendingStatus = null
        }
        pendingTitle?.let {
            header.text = it
            pendingTitle = null
        }
        pendingRun?.let { tick ->
            header.text = RunOverlayText.header(res, tick)
            val values = tick.perFrameConvergence ?: lastConvergence
            bins?.setRun(tick.plannedFrames, tick.frameIndex, tick.framePercent, values)
            if (values != null) strikeWarn.showText(RunOverlayText.strikeWarning(res, values))
            pendingRun = null
        }
        pendingSweep?.let {
            header.text = RunOverlayText.sweepHeader(res, it)
            status.showText(RunOverlayText.sweepStatus(res, it))
            sweepLattice?.apply(it)
            pendingSweep = null
        }
    }

    private fun clearPending() {
        pendingPercent = null
        pendingStatus = null
        pendingTitle = null
        pendingRun = null
        pendingSweep = null
    }

    companion object {
        private const val THROTTLE_MS = 100L
        private const val PERCENT = 100f
        private const val BAR_MAX = 1000
        private const val BAR_SCALE = 10f
    }
}

/** Shows [text], or hides the view when there is none. */
private fun TextView?.showText(text: String?) {
    this ?: return
    visibility = if (text != null) View.VISIBLE else View.GONE
    if (text != null) this.text = text
}
