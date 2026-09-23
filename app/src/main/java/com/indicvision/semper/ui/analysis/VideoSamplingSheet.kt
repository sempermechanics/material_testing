package com.indicvision.semper.ui.analysis

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.indicvision.semper.R
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
 * extracted. [onExtract] receives the sample times in ms.
 */
internal object VideoSamplingSheet {

    private const val MS_PER_S = 1000
    private const val DEFAULT_FPS = 10
    private const val MIN_FPS_CAP = 2
    private const val MAX_FPS_CAP = 60
    private const val FALLBACK_FPS_CAP = 30
    private const val MIN_SEGMENT_S = 0.1f

    @Suppress("LongMethod") // One sheet: its views, their wiring, and the estimate.
    fun show(
        activity: AppCompatActivity,
        meta: VideoMeta,
        syncTimesUs: List<Long>,
        maxFrames: Int,
        onExtract: (timesMs: List<Double>) -> Unit,
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_video_sampling, null)
        val toggleMode = view.findViewById<MaterialButtonToggleGroup>(R.id.toggleSamplingMode)
        val tvKeyframesNote = view.findViewById<TextView>(R.id.tvKeyframesNote)
        val layoutFps = view.findViewById<View>(R.id.layoutFps)
        val sliderFps = view.findViewById<Slider>(R.id.sliderFps)
        val tvFps = view.findViewById<TextView>(R.id.tvFpsValue)
        val range = view.findViewById<RangeSlider>(R.id.rangeSegment)
        val tvSegment = view.findViewById<TextView>(R.id.tvSegmentValue)
        val tvEstimate = view.findViewById<TextView>(R.id.tvEstimate)
        val btnExtract = view.findViewById<MaterialButton>(R.id.btnExtractFrames)

        view.findViewById<TextView>(R.id.tvVideoInfo).text = infoLine(meta)
        toggleMode.visibility = if (syncTimesUs.size >= 2) View.VISIBLE else View.GONE

        // Frame-rate selector, capped at the source rate when known.
        val maxFps = (if (meta.fpsKnown) ceil(meta.fps).toInt() else FALLBACK_FPS_CAP)
            .coerceIn(MIN_FPS_CAP, MAX_FPS_CAP)
        sliderFps.valueFrom = 1f
        sliderFps.valueTo = maxFps.toFloat()
        sliderFps.value = minOf(DEFAULT_FPS, maxFps).toFloat()
        tvFps.text = fpsLabel(sliderFps.value)

        // Time segment, in seconds.
        val durationSec = (meta.durationMs / MS_PER_S.toDouble()).toFloat().coerceAtLeast(MIN_SEGMENT_S)
        range.valueFrom = 0f
        range.valueTo = durationSec
        range.values = listOf(0f, durationSec)
        tvSegment.text = segmentLabel(0L, meta.durationMs)

        // The slider reaches the clip's end, where no frame starts; sampling
        // stops at the last frame's start so the estimate is what extraction
        // delivers (see VideoSampling).
        val lastFrameMs = VideoSampling.lastFrameStartMs(meta.durationMs, meta.fps, meta.fpsKnown)
        fun segmentMs(): Pair<Long, Long> =
            (range.values.first() * MS_PER_S).toLong().coerceAtMost(lastFrameMs) to
                (range.values.last() * MS_PER_S).toLong().coerceAtMost(lastFrameMs)
        fun keyframeMode() = toggleMode.checkedButtonId == R.id.btnModeKeyframes
        fun plan(): List<Double> {
            val (startMs, endMs) = segmentMs()
            return if (keyframeMode()) {
                // A key frame can sit past the last frame's start by rounding;
                // the segment's own end is the bound here.
                val segmentEndMs = (range.values.last() * MS_PER_S).toLong()
                VideoSampling.keyframeTimesMs(syncTimesUs, startMs, segmentEndMs, maxFrames)
            } else {
                VideoSampling.sampleTimesMs(startMs, endMs, sliderFps.value.toDouble(), maxFrames)
            }
        }
        fun refreshEstimate() {
            val n = plan().size
            tvEstimate.text = estimateLine(activity, keyframeMode(), n, maxFrames)
            btnExtract.isEnabled = n >= 2
            btnExtract.text = activity.resources.getQuantityString(R.plurals.extract_n_frames_fmt, n, n)
        }

        toggleMode.addOnButtonCheckedListener { _, _, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            layoutFps.visibility = if (keyframeMode()) View.GONE else View.VISIBLE
            tvKeyframesNote.visibility = if (keyframeMode()) View.VISIBLE else View.GONE
            refreshEstimate()
        }
        sliderFps.addOnChangeListener { _, v, _ ->
            tvFps.text = fpsLabel(v)
            refreshEstimate()
        }
        range.addOnChangeListener { s, _, _ ->
            tvSegment.text = segmentLabel((s.values.first() * MS_PER_S).toLong(), (s.values.last() * MS_PER_S).toLong())
            refreshEstimate()
        }
        refreshEstimate()

        // Bottom sheet (wireframe 05b): the primary button states the outcome.
        val sheet = BottomSheetDialog(activity)
        sheet.setContentView(view)
        btnExtract.setOnClickListener {
            val times = plan()
            sheet.dismiss()
            onExtract(times)
        }
        sheet.show()
    }

    /** What [n] planned frames make: one reference, the rest deformed. */
    private fun estimateLine(activity: AppCompatActivity, keyframes: Boolean, n: Int, maxFrames: Int): String {
        val capped = if (n >= maxFrames) activity.getString(R.string.video_capped_suffix) else ""
        val deformed = (n - 1).coerceAtLeast(0)
        return when {
            !keyframes -> "≈ $n frame(s): 1 reference + $deformed deformed$capped"
            n < 2 -> activity.getString(R.string.video_keyframes_too_few)
            else -> activity.getString(R.string.video_keyframes_estimate_fmt, n, deformed, capped)
        }
    }

    /** Only the parts the file actually reported. */
    private fun infoLine(meta: VideoMeta): String {
        val info = mutableListOf<String>()
        if (meta.width > 0 && meta.height > 0) info.add("${meta.width}×${meta.height}")
        if (meta.fpsKnown) info.add("%.0f fps".format(meta.fps))
        info.add(VideoFrameExtractor.formatClock(meta.durationMs))
        return info.joinToString("   ·   ")
    }

    private fun fpsLabel(value: Float) = "${value.toInt()} fps"

    private fun segmentLabel(startMs: Long, endMs: Long) =
        "${VideoFrameExtractor.formatClock(startMs)} – ${VideoFrameExtractor.formatClock(endMs)}"
}
