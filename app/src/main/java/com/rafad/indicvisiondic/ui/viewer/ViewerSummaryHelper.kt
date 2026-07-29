// A UI controller: one small method per thing the viewer can do to the summary
// (show, hide, field change, scale change, cancel) plus the playback and status
// helpers they share, so TooManyFunctions is suppressed for this file.
@file:Suppress("TooManyFunctions")

package com.rafad.indicvisiondic.ui.viewer

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.report.ReportBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Drives the viewer's summary slot — the looping animation that sits before
 * frame 1 and shows the whole sequence in the selected field.
 *
 * The heavy lifting is [SummaryAnimation]'s; this owns the screen: which field
 * is showing, the progress line while a field is still rendering, and playback.
 */
class ViewerSummaryHelper(private val host: ResultViewerActivity) {

    private val layer: View = host.findViewById(R.id.summaryLayer)
    private val image: ImageView = host.findViewById(R.id.imgSummary)
    private val statusPanel: View = host.findViewById(R.id.summaryStatus)
    private val progress: ProgressBar = host.findViewById(R.id.progressSummary)
    private val status: TextView = host.findViewById(R.id.tvSummaryStatus)

    /** Value range per field over the whole sequence; empty until the pass finishes. */
    private var ranges: Map<Int, Pair<Float, Float>> = emptyMap()
    private var rangesJob: Job? = null
    private var buildJob: Job? = null

    /** Which field the on-screen animation belongs to, so a re-show can skip work. */
    private var shownField: Int? = null

    var isShowing: Boolean = false
        private set

    /** Shared with the share sheet, so both build into the same cached files. */
    internal val animation: SummaryAnimation by lazy {
        SummaryAnimation(
            SummaryAnimation.Spec(
                batchFiles = host.summaryBatchFiles(),
                imgW = host.imgW,
                imgH = host.imgH,
                stepAt = { index -> host.sweepSteps?.getOrNull(index) ?: host.baseStep },
                outputDir = File(host.cacheDir, "share").apply { mkdirs() },
                backgroundColor = ContextCompat.getColor(host, R.color.viewer_canvas),
            ),
        )
    }

    /** Kicks off the one decode pass that fixes every field's colour scale. */
    fun start() {
        if (host.summaryBatchFiles().isEmpty()) return
        rangesJob = host.lifecycleScope.launch {
            val computed = withContext(Dispatchers.Default) {
                SummaryAnimation.globalRanges(host.summaryBatchFiles())
            }
            ranges = computed
            if (isShowing) render(host.currentDataIndex)
        }
    }

    fun show() {
        isShowing = true
        layer.isVisible = true
        render(host.currentDataIndex)
    }

    fun hide() {
        isShowing = false
        stopPlayback()
        layer.isVisible = false
    }

    /** The field toggle moved; re-render if the summary is the thing on screen. */
    fun onFieldChanged() {
        if (isShowing) render(host.currentDataIndex)
    }

    /** A changed fixed scale invalidates the baked-in colours for that field. */
    fun onScaleChanged(dataIndex: Int) {
        if (shownField == dataIndex) shownField = null
        if (isShowing) render(host.currentDataIndex)
    }

    fun cancel() {
        rangesJob?.cancel()
        buildJob?.cancel()
        stopPlayback()
    }

    /** Bounds for [dataIndex]: a user-set fixed scale wins, else the global range. */
    fun boundsFor(dataIndex: Int): Pair<Float, Float>? =
        host.customBoundsFor(dataIndex) ?: ranges[dataIndex]

    /** The label the frame counter shows while the summary is up. */
    fun counterText(): String {
        val frames = host.summaryBatchFiles().size
        return host.resources.getQuantityString(
            R.plurals.summary_counter_fmt,
            frames,
            host.currentTypeString,
            frames,
        )
    }

    private fun render(dataIndex: Int) {
        if (shownField == dataIndex && image.drawable != null) {
            startPlayback()
            return
        }
        val bounds = boundsFor(dataIndex)
        if (bounds == null) {
            // Either the range pass is still running or nothing correlated at all.
            showStatus(if (rangesJob?.isActive == true) host.getString(R.string.share_generating) else null)
            if (rangesJob?.isActive != true) showStatus(host.getString(R.string.summary_no_data))
            return
        }

        val label = host.currentTypeString
        val total = host.summaryBatchFiles().size
        buildJob?.cancel()
        stopPlayback()
        showStatus(host.getString(R.string.summary_building_fmt, label, 0, total))
        buildJob = host.lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.Default) {
                    animation.build(dataIndex, label, bounds) { done, count ->
                        host.lifecycleScope.launch {
                            if (host.currentDataIndex != dataIndex) return@launch
                            progress.progress = done * PERCENT / count.coerceAtLeast(1)
                            status.text = host.getString(R.string.summary_building_fmt, label, done, count)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                Timber.e(e, "Summary animation for %s failed", label)
                null
            }
            if (host.currentDataIndex != dataIndex || !isShowing) return@launch
            if (file == null) {
                showStatus(host.getString(R.string.summary_failed))
                return@launch
            }
            shownField = dataIndex
            display(file)
            host.refreshSummaryScaleLabels(dataIndex, bounds)
        }
    }

    private fun display(file: File) {
        // AnimatedImageDrawable arrived in API 28; below that the same GIF still
        // builds and shares, it just does not move on screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val drawable = runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
            }.getOrElse {
                Timber.w(it, "Could not decode the summary animation")
                showStatus(host.getString(R.string.summary_failed))
                return
            }
            image.setImageDrawable(drawable)
            statusPanel.isVisible = false
            (drawable as? Animatable)?.start()
        } else {
            val still = runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
            if (still == null) {
                showStatus(host.getString(R.string.summary_failed))
                return
            }
            image.setImageBitmap(still)
            showStatus(host.getString(R.string.summary_needs_android_9))
        }
    }

    private fun startPlayback() {
        statusPanel.isVisible = false
        (image.drawable as? Animatable)?.start()
    }

    private fun stopPlayback() {
        (image.drawable as? Animatable)?.stop()
    }

    private fun showStatus(message: String?) {
        statusPanel.isVisible = message != null
        status.text = message.orEmpty()
        progress.isVisible = message != null &&
            message != host.getString(R.string.summary_failed) &&
            message != host.getString(R.string.summary_no_data) &&
            message != host.getString(R.string.summary_needs_android_9)
    }

    private companion object {
        const val PERCENT = 100
    }
}

/** Formats a field's animation bounds for the viewer's colour-scale labels. */
internal fun ResultViewerActivity.refreshSummaryScaleLabels(dataIndex: Int, bounds: Pair<Float, Float>) {
    val multiplier = DicResult.strainMultiplier(dataIndex)
    val unit = getString(
        if (DicResult.isStrainFieldIndex(dataIndex)) R.string.scale_unit_strain else R.string.scale_unit_px,
    )
    findViewById<TextView>(R.id.tvScaleMax).text =
        getString(R.string.scale_max_fmt, ReportBuilder.formatMetric(bounds.second * multiplier), unit)
    findViewById<TextView>(R.id.tvScaleMin).text =
        getString(R.string.scale_min_fmt, ReportBuilder.formatMetric(bounds.first * multiplier), unit)
}
