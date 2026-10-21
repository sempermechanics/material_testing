// The reference lookup (cache, file, display bitmap) reads best as early returns.
@file:Suppress("ReturnCount")

package com.sempermechanics.semper.ui.viewer.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.sempermechanics.semper.field.FieldKind
import com.sempermechanics.semper.imaging.BitmapDecoder
import com.sempermechanics.semper.imaging.ImageEncoder
import com.sempermechanics.semper.report.HeatmapRequest
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.share.ShareExportBuilder.Companion.HEATMAP_ALPHA
import java.io.File

/**
 * The annotated field PNGs of a [ShareExportBuilder] job: the current field
 * ([ShareKind.PHOTO]), every field of the frame ([ShareKind.PHOTOS]), and the
 * render the everything ZIP's result images use.
 */
internal class FieldImageExport(
    private val s: ShareCenter.Snapshot,
    private val outDir: File,
) {

    /**
     * What a frame's field PNGs are drawn on: its own photo ([displaced], the map
     * and markers where the points moved to), or the reference when the frame
     * has none on disk or it does not decode. The viewer makes the same choice
     * ([com.sempermechanics.semper.ui.viewer.ViewerImageLoader.onFramePhoto]).
     */
    class FrameBase(val bitmap: Bitmap, val displaced: Boolean)

    /**
     * One frame's [FrameBase], decoded once for all its fields. Make one per
     * frame and [recycleBaseCache] it before the next, so an export over many
     * frames holds one frame's photo at a time.
     */
    class FrameBaseCache {
        internal val bases = mutableMapOf<Triple<Int, Int, Int>, FrameBase>()
    }

    /**
     * Annotated PNG of one field for one frame's data, drawn as the viewer draws
     * that frame (ADR-011, TD-139): on the frame's own photo with the map at the
     * displaced positions ([VisualizationEngine.generateFrameHeatmap]), else on
     * the reference. Composited at the
     * [VisualizationEngine.REPORT_MAX_EDGE]-capped size rather than full sensor
     * resolution: a 26 MP reference otherwise held four ~100 MB ARGB bitmaps at once
     * (heatmap + base + out + decode) per field. Marker coordinates are scaled by the
     * same factor, mirroring [ReportBuilder.buildReport].
     */
    fun renderAnnotated(
        data: FloatArray,
        dataIndex: Int,
        typeString: String,
        frameIndex: Int,
        baseCache: FrameBaseCache? = null,
    ): Bitmap {
        // Optional cache: multi-field export reuses one decoded base bitmap.
        val size = s.imageSize
        val renderScale =
            VisualizationEngine.cappedRenderScale(size.width, size.height, VisualizationEngine.REPORT_MAX_EDGE)
        val renderW = (size.width * renderScale).toInt().coerceAtLeast(1)
        val renderH = (size.height * renderScale).toInt().coerceAtLeast(1)

        // The base decides how the map is drawn, so it is loaded first.
        val base = loadFrameBase(frameIndex, renderW, renderH, baseCache)
        // Each full-size bitmap is freed in finally, so a throw part-way (an
        // OOM) does not strand the others.
        var heatmap: Bitmap? = null
        var out: Bitmap? = null
        var done = false
        try {
            val baked = VisualizationEngine.render(
                HeatmapRequest(
                    data = data,
                    size = size,
                    valIndex = dataIndex,
                    step = s.stepAt(frameIndex),
                    maxLongEdge = VisualizationEngine.REPORT_MAX_EDGE,
                    displaced = base.displaced,
                ),
            )
            heatmap = baked.bitmap
            out = createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawBitmap(base.bitmap, null, Rect(0, 0, renderW, renderH), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawBitmap(heatmap, 0f, 0f, Paint().apply { alpha = HEATMAP_ALPHA })
            // Signed, as the PDF does: with absolute values "MIN" marked the strain
            // nearest zero under a label giving the most negative.
            val extrema = ReportBuilder.computeFieldExtrema(data, dataIndex, absoluteStrainValues = false)
            val unit = FieldKind.unitOf(dataIndex)
            ReportBuilder.bakeAnnotationsToCanvas(
                canvas,
                renderW,
                renderH,
                baked.range,
                extrema,
                data,
                ReportBuilder.FieldAnnotation(
                    typeString = typeString,
                    unit = unit,
                    dataIndex = dataIndex,
                    imageName = s.sourceImageName(frameIndex),
                    displaced = base.displaced,
                ),
                coordScale = renderScale,
            )
            done = true
            return out
        } finally {
            heatmap?.recycle()
            // A cached base belongs to the cache; the display bitmap to the viewer.
            if (baseCache == null && base.bitmap !== s.baseImage) base.bitmap.recycle()
            if (!done) out?.recycle()
        }
    }

    /**
     * The [FrameBase] of the frame at [frameIndex], decoded no larger than the
     * capped composite it draws into: the frame's own photo, found and decoded
     * as the viewer does ([ViewerReportFactory.Source.deformedImagePathAt],
     * [BitmapDecoder.decodeFileForView]), else the reference.
     */
    private fun loadFrameBase(frameIndex: Int, renderW: Int, renderH: Int, cache: FrameBaseCache?): FrameBase {
        val key = Triple(frameIndex, renderW, renderH)
        cache?.bases?.get(key)?.let { return it }
        val photo = s.reportSource.deformedImagePathAt(frameIndex)?.let { decodeCapped(it, renderW, renderH) }
        val base = if (photo != null) {
            FrameBase(photo, displaced = true)
        } else {
            FrameBase(loadCappedReference(renderW, renderH), displaced = false)
        }
        // The viewer's display bitmap is never cached: the cache recycles what it holds.
        if (base.bitmap !== s.baseImage) cache?.bases?.put(key, base)
        return base
    }

    private fun decodeCapped(path: String, renderW: Int, renderH: Int): Bitmap? = BitmapDecoder.decodeFileForView(
        path,
        renderW,
        renderH,
        VisualizationEngine.REPORT_MAX_EDGE,
        rawWidth = s.imageSize.width,
        rawHeight = s.imageSize.height,
    )

    /**
     * Reference image for compositing, decoded no larger than the capped composite it
     * draws into — prefer the on-disk reference (inSampleSize-decoded) over the
     * viewer's display bitmap so export quality doesn't depend on viewer scale.
     */
    internal fun loadCappedReference(renderW: Int, renderH: Int): Bitmap {
        s.refImagePath?.let { path -> decodeCapped(path, renderW, renderH)?.let { return it } }
        val display = s.baseImage ?: error("No reference image for export")
        return if (display.width == renderW && display.height == renderH) {
            display
        } else {
            display.scale(renderW, renderH)
        }
    }

    fun recycleBaseCache(cache: FrameBaseCache) {
        cache.bases.values.forEach { base ->
            if (base.bitmap !== s.baseImage) base.bitmap.recycle()
        }
        cache.bases.clear()
    }

    /** Writes [bmp] as a PNG and frees it, whether or not the write succeeds. */
    private fun writePng(bmp: Bitmap, name: String): File {
        try {
            val f = File(outDir, name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, ImageEncoder.PNG_QUALITY_MAX, it) }
            return f
        } finally {
            bmp.recycle()
        }
    }

    fun currentPhoto(): File {
        return writePng(
            renderAnnotated(s.frameData(), s.dataIndex, s.typeString, s.frameIndex),
            "${s.baseName}_${s.typeString}_frame${s.frameIndex + 1}.png",
        )
    }

    /**
     * Every field of the current frame as an annotated PNG. [onField] hears
     * (fields done, field count, the field now in progress) before each field,
     * and once more when the last is written.
     */
    fun allFieldPhotos(onField: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> }): List<File> {
        val data = s.frameData()
        val baseCache = FrameBaseCache()
        return try {
            val fields = FieldKind.entries
            fields.mapIndexed { done, field ->
                onField(done, fields.size, field.label)
                writePng(
                    renderAnnotated(data, field.dataIndex, field.label, s.frameIndex, baseCache),
                    "${s.baseName}_${field.label}_frame${s.frameIndex + 1}.png",
                )
            }.also { onField(fields.size, fields.size, fields.last().label) }
        } finally {
            recycleBaseCache(baseCache)
        }
    }
}
