package com.sempermechanics.semper.ui.analysis.frames

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.databinding.SheetVideoSamplingBinding
import com.sempermechanics.semper.imaging.video.ExtractionRequest
import com.sempermechanics.semper.imaging.video.VideoFrameExtractor
import com.sempermechanics.semper.imaging.video.VideoKeyframes
import com.sempermechanics.semper.imaging.video.VideoMeta
import com.sempermechanics.semper.imaging.video.VideoSampling
import com.sempermechanics.semper.ui.common.dialog.FaqRedirect
import com.sempermechanics.semper.ui.common.onButtonChecked
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

/**
 * The video sampling sheet: which frames of a clip become the reference and
 * the deformed batch. Two plans ([VideoSampling]):
 * - **Frame rate** — evenly at a chosen rate over the segment;
 * - **Key frames** — the encoder's own key frames in the segment, the least
 *   compressed frames the file holds. Offered only when the file marks two or
 *   more ([VideoKeyframes]).
 *
 * The estimate is the plan itself, so the count promised is the count
 * extracted: [onExtract] receives the request with the planned sample times
 * ([ExtractionRequest.timesMs]), and each extracted frame keeps its own time,
 * which a timed load log is matched by.
 */
class VideoSamplingSheet(
    private val activity: AppCompatActivity,
    private val onExtract: (ExtractionRequest) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Reads [uri]'s metadata and key frames on [io], then offers the sampling
     * sheet; a video it cannot read gets a snackbar.
     */
    fun open(uri: Uri) {
        activity.lifecycleScope.launch {
            val meta = withContext(io) { VideoFrameExtractor.readMeta(activity, uri) }
            // An AVI we could demux but not decode can say which codec it is,
            // which beats "could not read this video" by a mile.
            val unsupported = meta.unsupportedCodec
            when {
                unsupported != null -> FaqRedirect.snackbar(
                    activity,
                    activity.getString(R.string.video_codec_unsupported_fmt, unsupported.trim()),
                    R.string.url_faq_video_read,
                )
                meta.durationMs <= 0L ->
                    FaqRedirect.snackbar(activity, R.string.video_read_failed, R.string.url_faq_video_read)
                else -> {
                    val syncTimesUs = withContext(io) { VideoKeyframes.syncTimesUs(activity, uri) }
                    show(uri, meta, syncTimesUs)
                }
            }
        }
    }

    /** The sheet for [meta]; key frames are offered when [syncTimesUs] holds two or more. */
    @Suppress("LongMethod") // One sheet: its views, their wiring, and the estimate.
    internal fun show(uri: Uri, meta: VideoMeta, syncTimesUs: List<Long>): BottomSheetDialog {
        val form = SheetVideoSamplingBinding.inflate(activity.layoutInflater)
        val maxFrames = AppSettings.maxFrames(activity, AppRemoteConfig.maxFrames(activity))
        form.tvVideoInfo.text = infoLine(meta)
        form.rgExtractMode.isVisible = syncTimesUs.size >= 2

        // Frame-rate selector, capped at the source rate when known.
        val maxFps = (if (meta.fpsKnown) ceil(meta.fps).toInt() else FALLBACK_FPS_CAP)
            .coerceIn(MIN_FPS_CAP, MAX_FPS_CAP)
        form.sliderFps.valueFrom = 1f
        form.sliderFps.valueTo = maxFps.toFloat()
        form.sliderFps.value = minOf(DEFAULT_FPS, maxFps).toFloat()
        form.tvFpsValue.text = fpsLabel(form.sliderFps.value)

        // Time segment, in seconds.
        val durationSeconds = (meta.durationMs / MS_PER_SECOND).toFloat().coerceAtLeast(MIN_SEGMENT_SECONDS)
        form.rangeSegment.valueFrom = 0f
        form.rangeSegment.valueTo = durationSeconds
        form.rangeSegment.values = listOf(0f, durationSeconds)
        form.tvSegmentValue.text = segmentLabel(0L, meta.durationMs)

        // The slider reaches the clip's end, where no frame starts; sampling
        // stops at the last frame's start so the estimate is what extraction
        // delivers (see VideoSampling).
        val lastFrameMs = VideoSampling.lastFrameStartMs(meta.durationMs, meta.fps, meta.fpsKnown)
        fun segmentMs(): Pair<Long, Long> =
            (form.rangeSegment.values.first() * MS_PER_SECOND).toLong().coerceAtMost(lastFrameMs) to
                (form.rangeSegment.values.last() * MS_PER_SECOND).toLong().coerceAtMost(lastFrameMs)
        fun keyframeMode() = form.rgExtractMode.checkedButtonId == R.id.btnModeKeyframes
        fun plan(): List<Double> {
            val (startMs, endMs) = segmentMs()
            return if (keyframeMode()) {
                // A key frame can sit past the last frame's start by rounding;
                // the segment's own end is the bound here.
                val segmentEndMs = (form.rangeSegment.values.last() * MS_PER_SECOND).toLong()
                VideoSampling.keyframeTimesMs(syncTimesUs, startMs, segmentEndMs, maxFrames)
            } else {
                VideoSampling.sampleTimesMs(startMs, endMs, form.sliderFps.value.toDouble(), maxFrames)
            }
        }
        fun refreshEstimate() {
            val n = plan().size
            form.tvEstimate.text = estimateLine(keyframeMode(), n, maxFrames)
            form.btnExtractFrames.isEnabled = n >= 2
            form.btnExtractFrames.text = activity.resources.getQuantityString(R.plurals.extract_n_frames_fmt, n, n)
        }

        form.rgExtractMode.onButtonChecked {
            form.layoutFps.isVisible = !keyframeMode()
            form.tvKeyframesNote.isVisible = keyframeMode()
            refreshEstimate()
        }
        form.sliderFps.addOnChangeListener { _, v, _ ->
            form.tvFpsValue.text = fpsLabel(v)
            refreshEstimate()
        }
        form.rangeSegment.addOnChangeListener { s, _, _ ->
            val startMs = (s.values.first() * MS_PER_SECOND).toLong()
            val endMs = (s.values.last() * MS_PER_SECOND).toLong()
            form.tvSegmentValue.text = segmentLabel(startMs, endMs)
            refreshEstimate()
        }
        refreshEstimate()

        // Bottom sheet (wireframe 05b): the primary button states the outcome.
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(form.root)
        form.btnExtractFrames.setOnClickListener {
            val times = plan()
            sheet.dismiss()
            onExtract(
                ExtractionRequest(
                    uri = uri,
                    fpsExtract = form.sliderFps.value.toDouble().coerceAtLeast(MIN_EXTRACT_FPS),
                    startMs = times.first().toLong(),
                    endMs = times.last().toLong(),
                    maxFrames = maxFrames,
                    preferKeyframes = keyframeMode(),
                    rotationDegrees = meta.rotationDegrees,
                    timesMs = times,
                ),
            )
        }
        sheet.show()
        return sheet
    }

    /** What [n] planned frames make: one reference, the rest deformed. */
    private fun estimateLine(keyframes: Boolean, n: Int, maxFrames: Int): String {
        val capped = if (n >= maxFrames) activity.getString(R.string.video_capped_suffix) else ""
        val deformed = (n - 1).coerceAtLeast(0)
        return when {
            !keyframes -> activity.resources.getQuantityString(R.plurals.video_estimate_fmt, n, n, deformed, capped)
            n < 2 -> activity.getString(R.string.video_keyframes_too_few)
            else -> activity.getString(R.string.video_keyframes_estimate_fmt, n, deformed, capped)
        }
    }

    /** Only the parts the file actually reported. */
    private fun infoLine(meta: VideoMeta): String {
        val info = mutableListOf<String>()
        if (meta.width > 0 && meta.height > 0) {
            info.add(activity.getString(R.string.video_resolution_fmt, meta.width, meta.height))
        }
        if (meta.fpsKnown) info.add(activity.getString(R.string.video_fps_fmt, meta.fps))
        info.add(VideoFrameExtractor.formatClock(meta.durationMs))
        return info.joinToString(activity.getString(R.string.video_info_separator))
    }

    private fun fpsLabel(fps: Float): String = activity.getString(R.string.video_fps_fmt, fps.toInt().toDouble())

    private fun segmentLabel(startMs: Long, endMs: Long): String = activity.getString(
        R.string.video_segment_fmt,
        VideoFrameExtractor.formatClock(startMs),
        VideoFrameExtractor.formatClock(endMs),
    )

    private companion object {
        const val DEFAULT_FPS = 10
        const val MIN_FPS_CAP = 2
        const val MAX_FPS_CAP = 60
        const val FALLBACK_FPS_CAP = 30
        const val MS_PER_SECOND = 1000f
        const val MIN_SEGMENT_SECONDS = 0.1f
        const val MIN_EXTRACT_FPS = 0.1
    }
}
