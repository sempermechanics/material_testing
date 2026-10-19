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
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.report.FieldRangesStore
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.ui.common.SerialJob
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Drives the viewer's summary slot — the looping animation that sits before
 * frame 1 and shows the whole sequence in the selected field.
 *
 * The heavy lifting is [SummaryAnimation]'s; this owns the screen: which field
 * is showing, the progress pill above the scrubber while a field is still
 * building (its line, its bar, and its close button that cancels), the colour
 * scale's ends ("—" until the sequence range is known), and playback.
 *
 * The pill is not part of the auto-hiding chrome
 * ([com.sempermechanics.semper.ui.viewer.ViewerChromeController]): it stays up
 * while the summary builds, since that is what the user is waiting on.
 */
class ViewerSummaryController(private val host: ResultViewerActivity) {

    private val layer: View = host.binding.summaryLayer
    private val image: ImageView = host.binding.imgSummary
    private val statusPanel: View = host.binding.summaryStatus
    private val progress: LinearProgressIndicator = host.binding.progressSummary
    private val status: TextView = host.binding.tvSummaryStatus
    private val cancelButton: View = host.binding.btnSummaryCancel

    /** Value range per field over the whole sequence; empty until the pass finishes. */
    private var ranges: Map<Int, ValueRange> = emptyMap()
    private val rangesJob = SerialJob()
    private val buildJob = SerialJob()

    /** Which field the on-screen animation belongs to, so a re-show can skip work. */
    private var shownField: Int? = null

    var isShowing: Boolean = false
        private set

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
                outputDir = CacheJanitor.shareDir(host.cacheDir),
                backgroundColor = ContextCompat.getColor(host, R.color.viewer_canvas),
                fitBounds = host.summaryFitBounds(),
            ),
        )
    }

    init {
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
        rangesJob.launch(host.lifecycleScope) {
            val files = host.batchFiles
            val computed = try {
                withContext(Dispatchers.Default) {
                    val rangesFile = files.firstOrNull()?.parentFile?.let {
                        File(it, FieldRangesStore.FILE_NAME)
                    }
                    SummaryAnimation.globalRanges(files, rangesFile) { done, total ->
                        host.lifecycleScope.launch(Dispatchers.Main.immediate) {
                            if (!isShowing || !rangesJob.isActive) return@launch
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
            if (isShowing) render(host.field.dataIndex)
        }
    }

    fun show() {
        isShowing = true
        layer.isVisible = true
        // The colour-scale scan is only needed once the summary is actually on screen.
        start()
        render(host.field.dataIndex)
    }

    fun hide() {
        isShowing = false
        stopPlayback()
        layer.isVisible = false
        hideStatus()
    }

    /** The field toggle moved; re-render if the summary is the thing on screen. */
    fun onFieldChanged() {
        if (isShowing) render(host.field.dataIndex)
    }

    /** A changed fixed scale invalidates the baked-in colours for that field. */
    fun onScaleChanged(dataIndex: Int) {
        if (shownField == dataIndex) shownField = null
        if (isShowing) render(host.field.dataIndex)
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
        host.customBoundsFor(dataIndex) ?: ranges[dataIndex]

    /** The label the frame counter shows while the summary is up: how many frames it plays, "5 frames". */
    fun counterText(): String {
        val frames = host.frameCount()
        return host.resources.getQuantityString(R.plurals.session_frames_fmt, frames, frames)
    }

    private fun render(dataIndex: Int) {
        val bounds = boundsFor(dataIndex)
        if (shownField == dataIndex && image.drawable != null) {
            // Back from a frame, whose own labels replaced the summary's.
            bounds?.let { host.refreshSummaryScaleLabels(dataIndex, it) }
            startPlayback()
            return
        }
        if (bounds == null) {
            renderWithoutRange()
            return
        }

        // The range is known: the scale shows the ends the animation is built on.
        host.refreshSummaryScaleLabels(dataIndex, bounds)
        val label = host.field.label
        val total = host.batchFiles.size
        buildJob.cancel()
        stopPlayback()
        showProgress(R.string.summary_building_fmt, 0, total)
        buildJob.launch(host.lifecycleScope) {
            val file = try {
                withContext(Dispatchers.Default) {
                    animation.build(dataIndex, label, bounds) { done, count ->
                        host.lifecycleScope.launch {
                            // showProgress itself skips a slot that is no longer up.
                            if (host.field.dataIndex != dataIndex || !buildJob.isActive) return@launch
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
            if (host.field.dataIndex != dataIndex || !isShowing) return@launch
            if (file == null) {
                showMessage(R.string.summary_failed)
                return@launch
            }
            val decoded = decodeForDisplay(file)
            // The decode suspended: the field or the slot may have moved on.
            if (host.field.dataIndex != dataIndex || !isShowing) return@launch
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
        status.text = host.getString(format, done, total)
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
        status.text = host.getString(message)
        progress.isVisible = false
        cancelButton.isVisible = false
    }

    private fun hideStatus() {
        statusPanel.isVisible = false
    }

    private fun showScalePending() {
        val pending = host.getString(R.string.scale_label_pending)
        host.binding.tvScaleMax.text = pending
        host.binding.tvScaleMin.text = pending
    }

    private companion object {
        const val PERCENT = 100
    }
}

/** Formats a field's animation bounds for the viewer's colour-scale labels. */
internal fun ResultViewerActivity.refreshSummaryScaleLabels(dataIndex: Int, bounds: ValueRange) {
    val multiplier = DicResult.strainMultiplier(dataIndex)
    val unit = getString(
        if (DicResult.isStrainFieldIndex(dataIndex)) R.string.scale_unit_strain else R.string.scale_unit_px,
    )
    binding.tvScaleMax.text =
        getString(R.string.scale_max_fmt, ReportBuilder.formatMetric(bounds.max * multiplier), unit)
    binding.tvScaleMin.text =
        getString(R.string.scale_min_fmt, ReportBuilder.formatMetric(bounds.min * multiplier), unit)
}
