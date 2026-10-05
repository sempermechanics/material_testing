package com.sempermechanics.semper.ui.analysis.frames

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.DicSettings
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
 * Video input. The first sampled frame becomes the reference; the rest
 * become the deformed sequence, feeding the exact same refBytes / defFilePaths
 * state as the image flow.
 *
 * Step 1: read metadata and key frames, show resolution/fps/length + sampling options.
 * Step 2: [onExtract] the chosen plan over the chosen time segment. Two plans
 * ([VideoSampling]):
 * - **Frame rate**: evenly at a chosen rate over the segment;
 * - **Key frames**: the encoder's own key frames in the segment, the least
 *   compressed frames the file holds. Offered only when the file marks two or
 *   more ([VideoKeyframes]).
 *
 * The estimate is the plan itself, so the count promised is the count extracted.
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

    /** Sampling by frame rate or key frames over a time segment, with a metadata summary. */
    internal fun show(uri: Uri, meta: VideoMeta, syncTimesUs: List<Long> = emptyList()): BottomSheetDialog {
        val form = SheetVideoSamplingBinding.inflate(activity.layoutInflater)
        val plan = SamplingPlan(meta, syncTimesUs, DicSettings.maxFrames(activity, AppRemoteConfig.maxFrames(activity)))
        form.tvVideoInfo.text = infoLine(meta)
        form.rgExtractMode.isVisible = plan.hasKeyframes

        // --- Frame-rate selector (capped at the source rate when known) ---
        form.sliderFps.valueFrom = 1f
        form.sliderFps.valueTo = plan.maxFps.toFloat()
        form.sliderFps.value = minOf(DEFAULT_FPS, plan.maxFps).toFloat()
        form.tvFpsValue.text = fpsLabel(form.sliderFps.value.toInt())

        // --- Time-segment selector (seconds) ---
        val durationSeconds = (meta.durationMs / MS_PER_SECOND).toFloat().coerceAtLeast(MIN_SEGMENT_SECONDS)
        form.rangeSegment.valueFrom = 0f
        form.rangeSegment.valueTo = durationSeconds
        form.rangeSegment.values = listOf(0f, durationSeconds)
        form.tvSegmentValue.text = segmentLabel(0, meta.durationMs)

        form.rgExtractMode.onButtonChecked { _ ->
            val keyframes = form.isKeyframeMode()
            form.layoutFps.isVisible = !keyframes
            form.tvKeyframesNote.isVisible = keyframes
            refreshEstimate(form, plan)
        }
        form.sliderFps.addOnChangeListener { _, v, _ ->
            form.tvFpsValue.text = fpsLabel(v.toInt())
            refreshEstimate(form, plan)
        }
        form.rangeSegment.addOnChangeListener { s, _, _ ->
            val startMs = (s.values.first() * MS_PER_SECOND_F).toLong()
            val endMs = (s.values.last() * MS_PER_SECOND_F).toLong()
            form.tvSegmentValue.text = segmentLabel(startMs, endMs)
            refreshEstimate(form, plan)
        }
        refreshEstimate(form, plan)

        // Bottom sheet (wireframe 05b): the primary button states the outcome.
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(form.root)
        form.btnExtractFrames.setOnClickListener {
            val times = timesOf(form, plan)
            sheet.dismiss()
            onExtract(ExtractionRequest(uri = uri, timesMs = times, rotationDegrees = meta.rotationDegrees))
        }
        sheet.show()
        return sheet
    }

    private fun SheetVideoSamplingBinding.isKeyframeMode() =
        rgExtractMode.isVisible && rgExtractMode.checkedButtonId == R.id.btnModeKeyframes

    private fun timesOf(form: SheetVideoSamplingBinding, plan: SamplingPlan): List<Double> =
        plan.timesMs(form.rangeSegment.values, form.sliderFps.value.toDouble(), form.isKeyframeMode())

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

    private fun fpsLabel(fps: Int): String = activity.getString(R.string.video_fps_fmt, fps.toDouble())

    private fun segmentLabel(startMs: Long, endMs: Long): String = activity.getString(
        R.string.video_segment_fmt,
        VideoFrameExtractor.formatClock(startMs),
        VideoFrameExtractor.formatClock(endMs),
    )

    private fun refreshEstimate(form: SheetVideoSamplingBinding, plan: SamplingPlan) {
        val n = timesOf(form, plan).size
        val capped = if (n >= plan.maxFrames) activity.getString(R.string.video_capped_suffix) else ""
        val deformed = (n - 1).coerceAtLeast(0)
        form.tvEstimate.text = when {
            !form.isKeyframeMode() ->
                activity.resources.getQuantityString(R.plurals.video_estimate_fmt, n, n, deformed, capped)
            n < 2 -> activity.getString(R.string.video_keyframes_too_few)
            else -> activity.resources.getQuantityString(R.plurals.video_keyframes_estimate_fmt, n, n, deformed, capped)
        }
        form.btnExtractFrames.isEnabled = n >= 2
        form.btnExtractFrames.text = activity.resources.getQuantityString(R.plurals.extract_n_frames_fmt, n, n)
    }

    private companion object {
        const val DEFAULT_FPS = 10
        const val MS_PER_SECOND = 1000.0
        const val MS_PER_SECOND_F = 1000f
        const val MIN_SEGMENT_SECONDS = 0.1f
    }
}

/**
 * Which instants a sampling of [meta] extracts, capped at [maxFrames]: evenly
 * at a rate, or the key frames [syncTimesUs] marks. The segment slider reaches
 * the clip's end, where no frame starts; sampling stops at the last frame's
 * start so the estimate is what extraction delivers (see
 * [VideoSampling.lastFrameStartMs]).
 */
internal class SamplingPlan(meta: VideoMeta, private val syncTimesUs: List<Long>, val maxFrames: Int) {
    /** The frame-rate slider's top: the source rate when known. */
    val maxFps = (if (meta.fpsKnown) ceil(meta.fps).toInt() else ASSUMED_FPS).coerceIn(MIN_TOP_FPS, MAX_TOP_FPS)

    /** The key-frame plan is offered only when the file marks two or more. */
    val hasKeyframes: Boolean get() = syncTimesUs.size >= 2

    private val lastFrameMs = VideoSampling.lastFrameStartMs(meta.durationMs, meta.fps, meta.fpsKnown)

    /** The segment slider's [values], in seconds, as ms clamped to the last frame's start. */
    fun segmentMs(values: List<Float>): Pair<Long, Long> =
        (values.first() * MS_PER_SECOND).toLong().coerceAtMost(lastFrameMs) to
            (values.last() * MS_PER_SECOND).toLong().coerceAtMost(lastFrameMs)

    /** The instants extracted over [values]: at [fps], or the key frames when [keyframes]. */
    fun timesMs(values: List<Float>, fps: Double, keyframes: Boolean): List<Double> {
        val (startMs, endMs) = segmentMs(values)
        return if (keyframes) {
            // A key frame can sit past the last frame's start by rounding;
            // the segment's own end is the bound here.
            VideoSampling.keyframeTimesMs(syncTimesUs, startMs, (values.last() * MS_PER_SECOND).toLong(), maxFrames)
        } else {
            VideoSampling.sampleTimesMs(startMs, endMs, fps.coerceAtLeast(MIN_EXTRACT_FPS), maxFrames)
        }
    }

    /** Frames a frame-rate sampling at [fps] over [values] extracts. */
    fun estimate(values: List<Float>, fps: Double): Int = timesMs(values, fps, keyframes = false).size

    private companion object {
        const val ASSUMED_FPS = 30
        const val MIN_TOP_FPS = 2
        const val MAX_TOP_FPS = 60
        const val MS_PER_SECOND = 1000f
        const val MIN_EXTRACT_FPS = 0.1
    }
}
