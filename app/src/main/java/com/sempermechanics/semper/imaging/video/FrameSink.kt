package com.sempermechanics.semper.imaging.video

import android.content.Context
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionNaming
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.imaging.GrayPngEncoder
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.frames.ImportedBatch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Where one extraction's frames go: the staging batch [VideoFrameExtractor]
 * returns, with progress reported as each frame lands.
 *
 * Every import path — the AVI demuxer, the hardware decoder, the retriever
 * fallback — ends here, so the reference frame, the file naming and the commit
 * are decided once and all three produce identical batches. A frame that will
 * not decode, or a batch with nothing deformed in it, aborts the write where it
 * is found rather than part-writing a session.
 *
 * Each deformed frame keeps its time in the clip, so a frame the AVI rung
 * dropped as a repeat, or one the retriever could not read, takes no time
 * with it: the times stay aligned with the frames written.
 *
 * @param clipName the clip's display name (`tensile_03.mp4`); the reference,
 *   and so the analysis, is named after it (`tensile_03`), else "Video".
 */
internal class FrameSink(
    private val context: Context,
    private val cacheDir: File,
    private val stagingDir: File,
    private val clipName: String?,
    private val onProgress: (percent: Int, status: String) -> Unit,
) {

    /** The name the reference, the batch and the analysis take from the clip. */
    val videoName: String get() = SessionNaming.clipName(clipName) ?: context.getString(R.string.video)

    /** The staged file of the frame at sample [index] (index 0 is the reference). */
    fun deformedFile(index: Int): File = File(stagingDir, String.format(Locale.US, "%04d_frame.png", index))

    /** Reports that the frame at sample [index] of [count] is done. */
    fun progress(index: Int, count: Int) {
        val status = context.getString(R.string.video_extracting_progress_fmt, index + 1, count)
        onProgress((index + 1) * PERCENT / count, status)
    }

    /**
     * Writes one decoded frame per entry of [timesMs] (the time each frame was
     * asked for) as lossless grayscale PNGs: frame 0 is the reference, the rest
     * are the deformed batch. Each deformed frame is timed by its own
     * [DecodedFrame.ptsUs], and by the time asked for only when the decoder
     * cannot say. Null when a frame fails to decode or nothing deformed came out.
     */
    suspend fun write(
        timesMs: List<Long>,
        frameAt: (Int) -> DecodedFrame?,
    ): VideoFrameExtractor.ExtractionResult? {
        val count = timesMs.size
        val defPaths = mutableListOf<String>()
        val defTimesMs = mutableListOf<Long>()
        var reference: ReferenceFrame? = null

        for (i in 0 until count) {
            currentCoroutineContext().ensureActive()
            val frame = frameAt(i) ?: return null
            val luma = frame.luma
            currentCoroutineContext().ensureActive()

            if (i == 0) {
                val out = ByteArrayOutputStream()
                GrayPngEncoder.encode(out, luma)
                val png = out.toByteArray()
                reference = ReferenceFrame(
                    png = png,
                    size = ImageSize(luma.outWidth, luma.outHeight),
                    preview = BitmapDecoder.decodeByteArrayCapped(png, BitmapDecoder.PREVIEW_MAX_EDGE),
                    timeMs = frame.timeMs(requestedMs = timesMs[i]),
                )
            } else {
                val f = deformedFile(i)
                FileOutputStream(f).use { out -> GrayPngEncoder.encode(out, luma) }
                defPaths.add(f.absolutePath)
                defTimesMs.add(frame.timeMs(requestedMs = timesMs[i]))
            }
            progress(i, count)
        }
        return finish(reference, defPaths, defTimesMs)
    }

    /**
     * [assemble], once there is a reference and at least one deformed frame;
     * else null. [defTimesMs] is each of [defPaths]' time in the clip.
     */
    suspend fun finish(
        reference: ReferenceFrame?,
        defPaths: List<String>,
        defTimesMs: List<Long>,
    ): VideoFrameExtractor.ExtractionResult? =
        if (reference == null || defPaths.isEmpty()) null else assemble(reference, defPaths, defTimesMs)

    /** Commits the staged PNGs, each with its clip time, as an [ImportedBatch] and names the reference frame. */
    suspend fun assemble(
        reference: ReferenceFrame,
        defPaths: List<String>,
        defTimesMs: List<Long>,
    ): VideoFrameExtractor.ExtractionResult {
        require(defTimesMs.size == defPaths.size) { "One clip time per deformed frame" }
        val name = videoName
        val stagedBatch = ImportedBatch(
            frames = defPaths.zip(defTimesMs).sortedBy { it.first }.mapIndexed { idx, (path, timeMs) ->
                DeformedFrame(
                    path = path,
                    name = String.format(Locale.US, "frame_%04d.png", idx + 1),
                    size = reference.size,
                    timeMs = timeMs,
                )
            },
            fromVideo = true,
            videoName = name,
        )
        currentCoroutineContext().ensureActive()
        val batch = requireNotNull(
            FrameImportHelper.commitStagedBatch(cacheDir, stagingDir, stagedBatch),
        )

        return VideoFrameExtractor.ExtractionResult(
            reference = reference,
            refName = name,
            batch = batch,
        )
    }

    private companion object {
        const val PERCENT = 100
    }
}

/**
 * One decoded frame: its [luma] plane and [ptsUs], the presentation time the
 * stream gives it, or [UNKNOWN_PTS] when the decoder cannot say. The hardware
 * decoder returns the first frame at or after the time asked for, so that
 * time can be up to a frame early; the frame's own time is what the clip shows.
 */
internal class DecodedFrame(val luma: GrayPngEncoder.Luma, val ptsUs: Long = UNKNOWN_PTS) {

    /** The frame's time in the clip in ms: its own when known, else [requestedMs]. */
    fun timeMs(requestedMs: Long): Long = if (ptsUs >= 0) ptsUs / US_PER_MS else requestedMs

    companion object {
        /** A [ptsUs] the decoder could not report. */
        const val UNKNOWN_PTS = -1L
        private const val US_PER_MS = 1000L
    }
}
