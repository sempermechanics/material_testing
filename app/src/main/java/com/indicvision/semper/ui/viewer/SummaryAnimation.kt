// build() answers several "nothing to do" cases up front — no frames, already
// cached, rename refused — and those guards read better as early returns than as
// nesting, so ReturnCount is suppressed for this file.
@file:Suppress("ReturnCount")

package com.indicvision.semper.ui.viewer

import com.indicvision.semper.DicResult
import com.indicvision.semper.report.GifEncoder
import com.indicvision.semper.report.VisualizationEngine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import timber.log.Timber
import java.io.File
import java.io.OutputStream

/**
 * The result viewer's summary animation: every frame of one field, as a looping
 * GIF.
 *
 * Two properties make it worth having rather than just scrubbing:
 *
 * - **One colour scale for the whole sequence.** Each field is rendered against
 *   its range over *all* frames ([globalRanges]), so a colour means the same
 *   strain in the first frame as in the last. Per-frame auto scaling — what the
 *   scrubber does — renormalises every frame and makes them incomparable by eye.
 * - **Exact colours.** Frames are rendered straight to jet-palette indices and
 *   handed to [GifEncoder] with that palette, so nothing is quantised.
 *
 * Frames are encoded one at a time and never accumulated, so a 150-frame
 * animation costs the same memory as a one-frame one.
 */
class SummaryAnimation(private val spec: Spec) {

    /** Everything a build needs from the viewer, captured once. */
    class Spec(
        val batchFiles: List<File>,
        val imgW: Int,
        val imgH: Int,
        /** Grid pitch of a frame — a sweep gives each frame its own. */
        val stepAt: (Int) -> Int,
        val outputDir: File,
        /** Drawn where no correlated data covers a pixel; the viewer's canvas colour. */
        val backgroundColor: Int,
    )

    /** Bounds a field's GIF on disk was rendered with, so a scale change rebuilds it. */
    private val builtWith = mutableMapOf<Int, Pair<Float, Float>>()

    fun fileFor(label: String): File = File(spec.outputDir, "inDIC_${label}_animation.gif")

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
        val partial = File(out.parentFile, out.name + ".part")

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
        // Opened on the first frame, once its dimensions are known.
        var encoder: GifEncoder? = null
        try {
            spec.batchFiles.forEachIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                val data = DicResult.decodeDatBytes(file.readBytes())
                if (data == null) {
                    Timber.w("Frame %d unreadable, skipped in the %s animation", index + 1, label)
                } else {
                    val plane = renderFrame(data, dataIndex, index, bounds)
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

    private fun renderFrame(
        data: FloatArray,
        dataIndex: Int,
        index: Int,
        bounds: Pair<Float, Float>,
    ): VisualizationEngine.IndexPlane = VisualizationEngine.generateHeatmapIndices(
        data = data,
        imgW = spec.imgW,
        imgH = spec.imgH,
        valIndex = dataIndex,
        step = spec.stepAt(index),
        customMin = bounds.first,
        customMax = bounds.second,
        maxLongEdge = MAX_EDGE,
    )

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
         * Every field's value range across every frame, from one decode pass.
         *
         * A field's entry spans the displayed range of all frames — the lowest low
         * and the highest high of the per-frame percentile bounds the viewer
         * itself shows. Using those rather than raw extremes matters: one
         * decorrelated point anywhere in the sequence would otherwise stretch the
         * scale so far that the whole animation renders as a single colour.
         *
         * Fields with no correlated points anywhere are absent from the result.
         */
        fun globalRanges(batchFiles: List<File>): Map<Int, Pair<Float, Float>> {
            val indices = FIELDS.map { it.second }.toIntArray()
            val spans = mutableMapOf<Int, Pair<Float, Float>>()
            batchFiles
                // A frame that will not read or decode is skipped, not fatal: a
                // scale drawn from the rest still beats no animation at all.
                .mapNotNull { file -> file.runCatching { readBytes() }.getOrNull() }
                .mapNotNull { bytes -> DicResult.decodeDatBytes(bytes) }
                .forEach { data ->
                    VisualizationEngine.valueRanges(data, indices).forEach { (valIndex, range) ->
                        if (range != null) spans[valIndex] = widen(spans[valIndex], range)
                    }
                }
            return spans
        }

        private fun widen(seen: Pair<Float, Float>?, range: Pair<Float, Float>): Pair<Float, Float> =
            if (seen == null) range else minOf(seen.first, range.first) to maxOf(seen.second, range.second)
    }
}
