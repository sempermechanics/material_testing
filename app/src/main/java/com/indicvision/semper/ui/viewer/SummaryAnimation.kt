// build() answers several "nothing to do" cases up front — no frames, already
// cached, rename refused — and those guards read better as early returns than as
// nesting, so ReturnCount is suppressed for this file.
@file:Suppress("ReturnCount")

package com.indicvision.semper.ui.viewer

import com.indicvision.semper.DicResult
import com.indicvision.semper.report.FieldRangesStore
import com.indicvision.semper.report.GifEncoder
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.util.AtomicFiles
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * The result viewer's summary animation: every frame of one field, as a looping
 * GIF.
 *
 * Two properties make it worth having rather than just scrubbing:
 *
 * - **One colour scale for the whole sequence.** Each field is rendered against
 *   the envelope of every frame's own colour-bar ends ([globalRanges]): the
 *   lowest scale-min and the highest scale-max, which may come from different
 *   frames. Per-frame auto scaling — what the scrubber does — renormalises
 *   every frame and makes them incomparable by eye.
 * - **Exact colours.** Frames are rendered straight to jet-palette indices and
 *   handed to [GifEncoder] with that palette, so nothing is quantised.
 *
 * Frames are encoded one at a time and never accumulated, so a 150-frame
 * animation costs the same memory as a one-frame one.
 */
class SummaryAnimation(private val spec: Spec) {

    /** Everything a build needs from the viewer, captured once. */
    @Suppress("LongParameterList")
    class Spec(
        val batchFiles: List<File>,
        val imgW: Int,
        val imgH: Int,
        /** Grid pitch of a frame — a sweep gives each frame its own. */
        val stepAt: (Int) -> Int,
        val outputDir: File,
        /** Drawn where no correlated data covers a pixel; the viewer's canvas colour. */
        val backgroundColor: Int,
        /**
         * Image-pixel box `[left, top, right, bottom]` the GIF should fill
         * (same rest-fit region as the viewer). Null = discover from the first
         * readable frame's accepted points, else the full specimen.
         */
        val fitBounds: FloatArray? = null,
    )

    /** Bounds a field's GIF on disk was rendered with, so a scale change rebuilds it. */
    private val builtWith = mutableMapOf<Int, Pair<Float, Float>>()

    /** Resolved once per encode so every frame shares the same crop. */
    private var resolvedFit: FloatArray? = null

    fun fileFor(label: String): File =
        File(spec.outputDir, "${label}_animation_${bgKey()}_${fitKey()}.gif")

    /** Hex of the baked canvas colour so light and night GIFs do not collide. */
    private fun bgKey(): String = String.format(Locale.US, "%08X", spec.backgroundColor)

    /** Fit box in the filename so a ROI GIF never collides with a full-frame one. */
    private fun fitKey(): String {
        val b = spec.fitBounds ?: return "auto"
        return String.format(
            Locale.US,
            "%.0f-%.0f-%.0f-%.0f",
            b[HeatmapFit.LEFT],
            b[HeatmapFit.TOP],
            b[HeatmapFit.RIGHT],
            b[HeatmapFit.BOTTOM],
        )
    }

    /** True when [fileFor] is on disk and was built against [bounds]. */
    fun isBuilt(dataIndex: Int, label: String, bounds: Pair<Float, Float>): Boolean =
        builtWith[dataIndex] == bounds && fileFor(label).isFile

    /**
     * Renders every frame of one field into a looping GIF and returns the file.
     * Re-encodes only when the file is missing or was built against different
     * bounds; the colour scale is baked into the pixels, so it cannot be reused
     * across scales.
     *
     * @param onProgress called with (framesDone, frameCount) on the calling
     *   dispatcher — this runs off the main thread.
     */
    suspend fun build(
        dataIndex: Int,
        label: String,
        bounds: Pair<Float, Float>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): File? {
        val frameCount = spec.batchFiles.size
        if (frameCount == 0) return null
        val out = fileFor(label)
        if (isBuilt(dataIndex, label, bounds)) return out

        // Encode to a sibling first: a cancelled or failed build must never leave
        // a truncated file behind that the next call would treat as cached.
        val partial = AtomicFiles.partOf(out)

        try {
            partial.outputStream().buffered().use { stream ->
                encodeInto(stream, dataIndex, label, bounds, onProgress)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Throwable, not Exception: cancellation and OOM must both clean up,
            // and neither should be swallowed. Rethrown untouched.
            partial.delete()
            throw e
        }

        out.delete()
        if (!partial.renameTo(out)) {
            partial.delete()
            return null
        }
        builtWith[dataIndex] = bounds
        return out
    }

    /** Renders every frame of one field straight into [stream] as a GIF. */
    private suspend fun encodeInto(
        stream: OutputStream,
        dataIndex: Int,
        label: String,
        bounds: Pair<Float, Float>,
        onProgress: (Int, Int) -> Unit,
    ) {
        val frameCount = spec.batchFiles.size
        val delay = delayCentis(frameCount)
        val palette = VisualizationEngine.gifPalette(spec.backgroundColor)
        val fit = fitBoundsForEncode()
        // Opened on the first frame, once its dimensions are known.
        var encoder: GifEncoder? = null
        try {
            spec.batchFiles.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val data = DicResult.decodeDatFile(file)
                if (data == null) {
                    Timber.w("Frame %d unreadable, skipped in the %s animation", index + 1, label)
                } else {
                    val plane = renderFrame(data, dataIndex, index, bounds, fit)
                    val gif = encoder
                        ?: GifEncoder(stream, plane.width, plane.height, palette).also { encoder = it }
                    gif.addFrame(plane.indices, delay)
                    onProgress(index + 1, frameCount)
                }
            }
        } finally {
            encoder?.close()
        }
    }

    /**
     * Custom ROI from [Spec.fitBounds], else the first readable frame's accepted
     * points, else the full image — same priority as the viewer rest pose.
     */
    private fun fitBoundsForEncode(): FloatArray {
        resolvedFit?.let { return it }
        val fromSpec = spec.fitBounds
        if (fromSpec != null && fromSpec.size >= HeatmapFit.BOX_LEN) {
            resolvedFit = fromSpec
            return fromSpec
        }
        for (file in spec.batchFiles) {
            val data = DicResult.decodeDatFile(file) ?: continue
            val accepted = DicResult.acceptedPointsBounds(data)
            val box = HeatmapFit.resolve(
                spec.imgW,
                spec.imgH,
                roiX = 0,
                roiY = 0,
                roiW = spec.imgW,
                roiH = spec.imgH,
                accepted = accepted,
            )
            resolvedFit = box
            return box
        }
        val full = floatArrayOf(0f, 0f, spec.imgW.toFloat(), spec.imgH.toFloat())
        resolvedFit = full
        return full
    }

    private fun renderFrame(
        data: FloatArray,
        dataIndex: Int,
        index: Int,
        bounds: Pair<Float, Float>,
        fit: FloatArray,
    ): VisualizationEngine.IndexPlane {
        val renderCap = HeatmapFit.renderLongEdgeCap(spec.imgW, spec.imgH, fit, MAX_EDGE)
        val plane = VisualizationEngine.generateHeatmapIndices(
            data = data,
            imgW = spec.imgW,
            imgH = spec.imgH,
            valIndex = dataIndex,
            step = spec.stepAt(index),
            customMin = bounds.first,
            customMax = bounds.second,
            maxLongEdge = renderCap,
        )
        return HeatmapFit.cropAndScale(plane, spec.imgW, spec.imgH, fit, MAX_EDGE)
    }

    companion object {
        /** Long-edge cap for animation frames — small enough to encode fast and share. */
        const val MAX_EDGE = 640

        /** The animation never runs longer than this, in hundredths of a second. */
        const val MAX_TOTAL_CENTIS = 1000

        /** What a frame gets when the whole sequence fits comfortably: 300 ms. */
        const val PREFERRED_CENTIS = 30

        /** Below ~20 ms many viewers substitute a delay of their own. */
        const val MIN_CENTIS = 2

        val FIELDS = listOf(
            "U" to DicResult.IDX_U,
            "V" to DicResult.IDX_V,
            "Exx" to DicResult.IDX_EXX,
            "Eyy" to DicResult.IDX_EYY,
            "Exy" to DicResult.IDX_EXY,
        )

        /**
         * How long each frame is shown, in hundredths of a second.
         *
         * Every frame is included, and the total is capped at [MAX_TOTAL_CENTIS]:
         * a frame gets [PREFERRED_CENTIS] whenever the whole sequence fits inside
         * that, and only shorter when the frame count demands it. Integer division
         * is what keeps `frameCount * delay` under the cap rather than near it.
         */
        fun delayCentis(frameCount: Int): Int = when {
            frameCount <= 0 -> PREFERRED_CENTIS
            frameCount * PREFERRED_CENTIS <= MAX_TOTAL_CENTIS -> PREFERRED_CENTIS
            else -> (MAX_TOTAL_CENTIS / frameCount).coerceAtLeast(MIN_CENTIS)
        }

        /**
         * Every field's colour-bar envelope across every frame, from one decode pass.
         *
         * A field's entry is the lowest of each frame's scale-min and the highest
         * of each frame's scale-max — the same ends a still-frame colour bar would
         * show ([VisualizationEngine.valueRanges]). Those two ends need not come
         * from the same frame: if frame 1's scale is ±200 and a later frame's
         * scale is ±400, the GIF colour bar is ±400.
         *
         * Fields with no correlated points anywhere are absent from the result.
         *
         * @param rangesFile optional sidecar written by [FieldRangesStore.write] at
         *   analysis time (see [com.indicvision.semper.ui.analysis.AnalysisViewModel]).
         *   Used only when present and its frame count matches [batchFiles] exactly —
         *   anything else (missing, corrupt, a resumed/edited batch whose frame count
         *   has since changed) falls back to decoding every frame, unchanged from
         *   before this cache existed. Either path returns bit-identical values: the
         *   cache holds the exact same [VisualizationEngine.valueRanges] output the
         *   fallback would (re)compute, just computed once instead of on every call.
         * @param onProgress optional `(done, total)` after each frame is considered
         * (including unreadable ones). Hop to Main inside the callback for UI.
         */
        suspend fun globalRanges(
            batchFiles: List<File>,
            rangesFile: File? = null,
            onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        ): Map<Int, Pair<Float, Float>> {
            val indices = FIELDS.map { it.second }.toIntArray()
            val total = batchFiles.size

            val cached = rangesFile?.let { FieldRangesStore.read(it, indices) }
            if (cached != null && cached.size == total) {
                val spans = mutableMapOf<Int, Pair<Float, Float>>()
                cached.forEachIndexed { index, frameRanges ->
                    currentCoroutineContext().ensureActive()
                    frameRanges.forEach { (valIndex, range) ->
                        if (range != null) spans[valIndex] = widen(spans[valIndex], range)
                    }
                    onProgress(index + 1, total)
                }
                return spans
            }

            val spans = mutableMapOf<Int, Pair<Float, Float>>()
            // One frame at a time. The previous chain kept every ByteArray and
            // FloatArray alive until the pass finished — a heavy PLC band OOM'd
            // the 512 MB heap before the first GIF frame was built.
            batchFiles.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val data = runCatching { DicResult.decodeDatFile(file) }.getOrNull()
                if (data != null) {
                    VisualizationEngine.valueRanges(data, indices).forEach { (valIndex, range) ->
                        if (range != null) spans[valIndex] = widen(spans[valIndex], range)
                    }
                }
                onProgress(index + 1, total)
            }
            return spans
        }

        private fun widen(seen: Pair<Float, Float>?, range: Pair<Float, Float>): Pair<Float, Float> =
            if (seen == null) range else minOf(seen.first, range.first) to maxOf(seen.second, range.second)
    }
}
