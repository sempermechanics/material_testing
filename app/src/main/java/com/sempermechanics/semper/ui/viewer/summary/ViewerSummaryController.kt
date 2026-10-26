// A UI controller: one small method per thing the viewer can do to the summary
// (show, hide, field change, scale change, cancel) plus the playback and status
// helpers they share, so TooManyFunctions is suppressed for this file.
@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.ui.viewer.summary

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.databinding.LabResultsPanelBinding
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.FrameParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.FieldRangesStore
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.viewer.ResultViewerViewModel
import com.sempermechanics.semper.ui.viewer.ViewerViews
import com.sempermechanics.semper.ui.viewer.mechanical.ViewerStressStrainHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/** What [ViewerSummaryController] needs from the viewer. */
internal interface SummaryHost : ViewerViews {
    /** The field on screen and its fixed scale, if the user set one. */
    val viewModel: ResultViewerViewModel

    /** The opened frame set's `.dat` files; empty until it is read. */
    val batchFiles: List<File>
    val imageSize: ImageSize
    val frameParams: FrameParams

    /** The box the animation is framed to, or null to fit its accepted points. */
    fun summaryFitBounds(): FloatArray?

    /** The whole-sequence ranges are known: the ⓘ caption may quote them now. */
    fun onSequenceRangesReady()
}

/**
 * Drives the viewer's summary slot — the looping animation that sits before
 * frame 1 and shows the whole sequence in the selected field. A test with a
 * load per frame shows its Results there instead (the stress–strain curve,
 * E and peak stress): for a student that is the summary of the experiment,
 * and the heatmap animations stay one tap away in Share.
 *
 * The heavy lifting is [SummaryAnimation]'s; this owns the screen: which field
 * is showing, the progress pill above the scrubber while a field is still
 * building (its line, its bar, and its close button that cancels), the colour
 * scale's ends ("—" until the sequence range is known), and playback.
 *
 * The pill is not part of the auto-hiding chrome
 * ([com.sempermechanics.semper.ui.viewer.ViewerChromeController]): it stays up
 * while the summary builds, since that is what the user is waiting on.
 *
 * Its passes run in [activity]'s scope. Constructed before onCreate; reads
 * [ViewerViews.binding] lazily, and [wire] once the views are there.
 *
 * @param labResults the lab test's Results (Semper Classroom), read when the
 *   slot is shown; null, the default, for a viewer that has none
 */
internal class ViewerSummaryController(
    private val activity: AppCompatActivity,
    private val host: SummaryHost,
    private val labResults: () -> ViewerStressStrainHelper? = { null },
) {

    private val layer: View get() = host.binding.summaryLayer
    private val image: ImageView get() = host.binding.imgSummary
    private val statusPanel: View get() = host.binding.summaryStatus
    private val progress: LinearProgressIndicator get() = host.binding.progressSummary
    private val status: TextView get() = host.binding.tvSummaryStatus
    private val cancelButton: View get() = host.binding.btnSummaryCancel
    private val resultsPanel: View get() = host.binding.summaryResults

    /** The Results panel, inflated from its stub on the first show of a session with loads. */
    private val resultsViews: ViewerStressStrainHelper.Views by lazy {
        val panel = LabResultsPanelBinding.bind(host.binding.summaryResultsStub.inflate())
        // The summary page has the room the Details sheet does not.
        panel.plotLabResults.updateLayoutParams {
            height = activity.resources.getDimensionPixelSize(R.dimen.lab_results_summary_plot_height)
        }
        ViewerStressStrainHelper.Views.of(panel)
    }

    /** Value range per field over the whole sequence; empty until the pass finishes. */
    private var ranges: Map<Int, ValueRange> = emptyMap()
    private val rangesJob = SerialJob()

    /**
     * Where the range pass reads the frames. A test holds it: on a small batch
     * the pass can finish before [start]'s `withContext` suspends, and then its
     * result lands in the same main-thread task as the click that asked for it.
     */
    @VisibleForTesting
    internal var rangesDispatcher: CoroutineDispatcher = Dispatchers.Default
    private val buildJob = SerialJob()

    /** Which field the on-screen animation belongs to, so a re-show can skip work. */
    private var shownField: Int? = null

    var isShowing: Boolean = false
        private set

    /** Whether the slot shows Results rather than the heatmap animation. */
    val showsResults: Boolean get() = labResults()?.showsResults == true

    /**
     * Shared with the share sheet, so both build into the same cached files.
     * Holds plain values only: an export job keeps it past a rotation, so it
     * must not reach back into this viewer.
     */
    internal val animation: SummaryAnimation by lazy {
        val frameParams = host.frameParams
        SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = host.batchFiles,
                imgW = host.imageSize.width,
                imgH = host.imageSize.height,
                stepAt = { index -> frameParams.at(index).step },
                outputDir = CacheJanitor.shareDir(activity.cacheDir),
                backgroundColor = ContextCompat.getColor(activity, R.color.viewer_canvas),
                fitBounds = host.summaryFitBounds(),
            ),
        )
    }

    /** The pill's close button cancels; once, from onCreate. */
    fun wire() {
        cancelButton.setOnClickListener { cancel() }
    }

    /**
     * Kicks off the one pass that fixes every field's colour scale.
     *
     * `ResultViewerActivity` starts it on every open of a multi-frame, non-sweep session,
     * since single frames also take their colour scale from the sequence range;
     * [show] calls it too. It is idempotent, so repeated calls do
     * not re-scan. A batch run writes the [FieldRangesStore] sidecar, and then the pass
     * decodes nothing. Without it (older or restored sessions) the pass decodes and
     * range-scans **every frame**, about 1 MB of garbage per frame at 19 200 points,
     * which set the 150-frame heap (TD-87).
     */
    fun start() {
        if (host.batchFiles.isEmpty()) return
        if (rangesJob.isActive || ranges.isNotEmpty()) return
        rangesJob.launch(activity.lifecycleScope) {
            val files = host.batchFiles
            val computed = try {
                withContext(rangesDispatcher) {
                    val rangesFile = files.firstOrNull()?.parentFile?.let {
                        File(it, FieldRangesStore.FILE_NAME)
                    }
                    SummaryAnimation.globalRanges(files, rangesFile) { done, total ->
                        activity.lifecycleScope.launch(Dispatchers.Main.immediate) {
                            if (!isShowing || showsResults || !rangesJob.isActive) return@launch
                            showProgress(R.string.summary_scanning_fmt, done, total)
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                Timber.e(e, "OOM computing summary colour ranges")
                emptyMap()
            }
            ranges = computed
            host.onSequenceRangesReady()
            // Share still needs the ranges; only the animation on screen is skipped.
            if (isShowing && !showsResults) render(host.viewModel.field.dataIndex)
        }
    }

    fun show() {
        isShowing = true
        layer.isVisible = true
        resultsPanel.isVisible = showsResults
        if (showsResults) {
            hideStatus()
            // Only a tensile curve has a scale and bias to match the machine with.
            labResults()?.fill(resultsViews)
            return
        }
        // The colour-scale scan is only needed once the summary is actually on screen.
        start()
        render(host.viewModel.field.dataIndex)
    }

    fun hide() {
        isShowing = false
        stopPlayback()
        layer.isVisible = false
        hideStatus()
    }

    /** The field toggle moved; re-render if the summary is the thing on screen. */
    fun onFieldChanged() {
        if (isShowing && !showsResults) render(host.viewModel.field.dataIndex)
    }

    /** A changed fixed scale invalidates the baked-in colours for that field. */
    fun onScaleChanged(dataIndex: Int) {
        if (shownField == dataIndex) shownField = null
        if (isShowing && !showsResults) render(host.viewModel.field.dataIndex)
    }

    /** The pill's close button, and the viewer going away: stop both passes and drop the pill. */
    fun cancel() {
        rangesJob.cancel()
        buildJob.cancel()
        stopPlayback()
        hideStatus()
    }

    /** Bounds for [dataIndex]: a user-set fixed scale wins, else the global range. */
    fun boundsFor(dataIndex: Int): ValueRange? =
        host.viewModel.customBounds[dataIndex] ?: ranges[dataIndex]

    /**
     * The label the frame counter shows while the summary is up: how many
     * frames it plays, "5 frames"; "Results" while a lab test's Results stand
     * in for it.
     */
    fun counterText(): String {
        if (showsResults) return activity.getString(R.string.results_title)
        val frames = host.batchFiles.size
        return activity.resources.getQuantityString(R.plurals.session_frames_fmt, frames, frames)
    }

    private fun render(dataIndex: Int) {
        val bounds = boundsFor(dataIndex)
        if (shownField == dataIndex && image.drawable != null) {
            // Back from a frame, whose own labels replaced the summary's.
            bounds?.let { showScaleLabels(dataIndex, it) }
            startPlayback()
            return
        }
        if (bounds == null) {
            renderWithoutRange()
            return
        }

        // The range is known: the scale shows the ends the animation is built on.
        showScaleLabels(dataIndex, bounds)
        val label = host.viewModel.field.label
        val total = host.batchFiles.size
        buildJob.cancel()
        stopPlayback()
        showProgress(R.string.summary_building_fmt, 0, total)
        buildJob.launch(activity.lifecycleScope) {
            val file = try {
                withContext(Dispatchers.Default) {
                    animation.build(dataIndex, label, bounds) { done, count ->
                        activity.lifecycleScope.launch {
                            // showProgress itself skips a slot that is no longer up.
                            if (host.viewModel.field.dataIndex != dataIndex || !buildJob.isActive) return@launch
                            showProgress(R.string.summary_building_fmt, done, count)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                Timber.e(e, "Summary animation for %s failed", label)
                null
            }
            if (host.viewModel.field.dataIndex != dataIndex || !isShowing) return@launch
            if (file == null) {
                showMessage(R.string.summary_failed)
                return@launch
            }
            val decoded = decodeForDisplay(file)
            // The decode suspended: the field or the slot may have moved on.
            if (host.viewModel.field.dataIndex != dataIndex || !isShowing) return@launch
            shownField = dataIndex
            display(decoded)
        }
    }

    /** No range for the field yet: the pass is still reading frames, or nothing correlated at all. */
    private fun renderWithoutRange() {
        // "—", not a "0.0" that reads as a measurement, nor the last frame's ends.
        showScalePending()
        // Do not steal the determinate "Reading frames" status that [start]
        // already pushes — a static "Preparing files…" looked hung on PLC.
        if (rangesJob.isActive) {
            if (!statusPanel.isVisible) {
                showProgress(R.string.summary_scanning_fmt, 0, host.batchFiles.size)
            }
        } else {
            showMessage(R.string.summary_no_data)
        }
    }

    /**
     * The GIF as something the slot can show, decoded off the main thread: an
     * animated drawable (minSdk 30 always has AnimatedImageDrawable). Null when
     * it does not decode.
     */
    private suspend fun decodeForDisplay(file: File): Drawable? = withContext(Dispatchers.IO) {
        runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) }
            .onFailure { Timber.w(it, "Could not decode the summary animation") }
            .getOrNull()
    }

    private fun display(drawable: Drawable?) {
        if (drawable == null) {
            showMessage(R.string.summary_failed)
            return
        }
        image.setImageDrawable(drawable)
        hideStatus()
        (drawable as? Animatable)?.start()
    }

    private fun startPlayback() {
        hideStatus()
        (image.drawable as? Animatable)?.start()
    }

    private fun stopPlayback() {
        (image.drawable as? Animatable)?.stop()
    }

    /**
     * The pill as progress: "Building summary · 3 of 5", its bar, and the close
     * button. The bar runs indeterminate until the phase reports its first frame.
     */
    private fun showProgress(@StringRes format: Int, done: Int, total: Int) {
        if (!isShowing) return
        statusPanel.isVisible = true
        status.text = activity.getString(format, done, total)
        progress.isVisible = true
        if (done <= 0) {
            progress.isIndeterminate = true
        } else {
            progress.isIndeterminate = false
            progress.setProgressCompat(done * PERCENT / total.coerceAtLeast(1), true)
        }
        cancelButton.isVisible = true
    }

    /** The pill as a plain note (failed, nothing to animate, needs Android 9): nothing to cancel. */
    private fun showMessage(@StringRes message: Int) {
        if (!isShowing) return
        statusPanel.isVisible = true
        status.text = activity.getString(message)
        progress.isVisible = false
        cancelButton.isVisible = false
    }

    private fun hideStatus() {
        statusPanel.isVisible = false
    }

    /** The colour scale's ends for [bounds] of field [dataIndex], as the animation colours them. */
    private fun showScaleLabels(dataIndex: Int, bounds: ValueRange) {
        val multiplier = DicResult.strainMultiplier(dataIndex)
        val unit = activity.getString(
            if (DicResult.isStrainFieldIndex(dataIndex)) R.string.scale_unit_strain else R.string.scale_unit_px,
        )
        host.binding.tvScaleMax.text =
            activity.getString(R.string.scale_max_fmt, ReportBuilder.formatMetric(bounds.max * multiplier), unit)
        host.binding.tvScaleMin.text =
            activity.getString(R.string.scale_min_fmt, ReportBuilder.formatMetric(bounds.min * multiplier), unit)
    }

    private fun showScalePending() {
        val pending = activity.getString(R.string.scale_label_pending)
        host.binding.tvScaleMax.text = pending
        host.binding.tvScaleMin.text = pending
    }

    private companion object {
        const val PERCENT = 100
    }
}
