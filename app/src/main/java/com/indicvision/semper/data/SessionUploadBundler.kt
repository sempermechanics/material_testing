// Bundler stages a whole session's files (reference, raw frames, .dat, csv,
// reports) in one cohesive pass over the full file set; kept together so the
// staging order stays in one place.
@file:Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")

package com.indicvision.semper.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import com.indicvision.semper.DicResult
import com.indicvision.semper.imaging.ImageEncode
import com.indicvision.semper.report.AnalysisCsvWriter
import com.indicvision.semper.report.EngineStats
import com.indicvision.semper.report.FieldResult
import com.indicvision.semper.report.PdfReportGenerator
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.RoiData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.Locale

/**
 * Builds per-frame upload artifacts: combined analysis CSV rows, PDF reports,
 * and processed field heatmaps staged under `reports/` and `processed/`.
 *
 * CSV and report bake share one `.dat` decode per frame so upload staging does
 * not pay for decoding twice.
 */
object SessionUploadBundler {

    data class BundleCounts(val reports: Int, val processed: Int)

    /**
     * One pass over the frames: optional combined CSV plus the artifact sets as
     * plain files in the staging dir (Session.zip compresses everything at the
     * end, so there is no point deflating them twice into nested archives):
     *  - `csv/analysis_data.csv` (via [csvFile])
     *  - `reports/Master_Report_Frame_N.pdf`
     *  - `processed/Frame_N_<field>.png` — the U/V/Exx/Eyy/Exy heatmaps
     *
     * They're built together deliberately: [ReportBuilder.buildReport] already
     * bakes the field heatmaps to make the PDF, so writing them out here costs
     * nothing extra — and the CSV reuses the same decoded `.dat`.
     *
     * The PDF is rendered to a scratch file reused per frame, and each frame's
     * bitmaps are recycled before moving on, so memory stays flat regardless of
     * frame count. Parallel bake is intentionally avoided (OOM risk on large ROIs).
     */
    suspend fun stageCsvAndBundles(
        context: Context,
        record: SessionRecord,
        sessionDir: File,
        refFile: File,
        rawDeformedDir: File,
        stagingDir: File,
        csvFile: File?,
        writeReports: Boolean,
    ): BundleCounts = withContext(Dispatchers.Default) {
        val reportsDir = File(stagingDir, "reports").apply { if (writeReports) mkdirs() }
        val processedDir = File(stagingDir, "processed").apply { if (writeReports) mkdirs() }
        var reports = 0
        var processed = 0

        val canReport = writeReports && record.imgW > 0 && record.imgH > 0
        if (writeReports && !canReport) {
            Timber.e("Bad image dimensions for %s — skipping reports", record.id)
        }

        // The reference is the SAME image in every frame's report — decode and
        // scale it once for the whole session, not once per frame. Falls back
        // to a deformed frame if the reference won't decode.
        val baseImg: Bitmap? = if (canReport) {
            val originalBaseImg = decodeBaseImage(refFile, rawDeformedDir, record.defNames.firstOrNull())
            if (originalBaseImg == null) {
                Timber.e("No decodable base image (reference %s) — skipping reports", refFile.absolutePath)
                null
            } else {
                val scaled = originalBaseImg.scale(record.imgW, record.imgH)
                if (scaled !== originalBaseImg) originalBaseImg.recycle()
                scaled
            }
        } else {
            null
        }

        val scratch = if (baseImg != null) {
            File(context.cacheDir, "upload_${record.id}_frame.pdf")
        } else {
            null
        }
        val ctx = if (baseImg != null && scratch != null) {
            RenderContext(record, baseImg, scratch)
        } else {
            null
        }

        val sweepImage = record.defNames.firstOrNull().orEmpty()
        val csvAppender = csvFile?.let { AnalysisCsvWriter.open(it, record.isSweep) }
        try {
            record.defNames.forEachIndexed { index, defName ->
                val datFile = File(sessionDir, String.format(Locale.US, "frame_%04d.dat", index))
                if (!datFile.exists()) return@forEachIndexed
                val data = DicResult.decodeDatBytes(datFile.readBytes()) ?: return@forEachIndexed

                csvAppender?.append(
                    AnalysisCsvWriter.Frame(
                        image = if (record.isSweep) {
                            sweepImage
                        } else {
                            record.defNames.getOrElse(index) { "Frame_${index + 1}" }
                        },
                        subset = record.sweepSubsets.getOrElse(index) { record.subset },
                        step = record.sweepSteps.getOrElse(index) { record.step },
                        strainWindow = record.sweepStrainWindows.getOrElse(index) { record.strainWindow },
                        data = { data },
                    ),
                )

                if (ctx == null) return@forEachIndexed

                val frameName = if (record.isSweep) {
                    record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
                        .replace('/', '-').replace('\\', '-')
                } else {
                    "Frame_${index + 1}"
                }
                val defFile = File(rawDeformedDir, defName)

                // One processed/<frame>/ subfolder per combination, so its five
                // field maps stay together instead of all frames' maps landing
                // flat in processed/.
                val frameDir = File(processedDir, frameName)
                frameDir.mkdirs()
                val ok = renderFrame(ctx, data, defFile, frameName, index) { fields ->
                    fields.forEach { field ->
                        File(frameDir, "${field.fieldKey}.png").outputStream().buffered().use { out ->
                            field.bakedHeatmap.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, out)
                        }
                        processed++
                    }
                }
                if (!ok || scratch!!.length() == 0L) {
                    Timber.w("Report generation failed for %s", frameName)
                    return@forEachIndexed
                }
                scratch.copyTo(File(reportsDir, "Master_Report_$frameName.pdf"), overwrite = true)
                reports++
            }
        } finally {
            csvAppender?.close()
            scratch?.delete()
            baseImg?.recycle()
        }
        if (writeReports) {
            Timber.i("Staged %d frame reports and %d processed images", reports, processed)
        }
        BundleCounts(reports, processed)
    }

    /** Per-session state shared by every frame's report render. */
    private class RenderContext(
        val record: SessionRecord,
        /** Reference image, already scaled to engine dimensions. NOT owned by renderFrame. */
        val baseImg: Bitmap,
        /** Scratch PDF file, reused per frame. */
        val scratch: File,
    )

    /**
     * Build one frame's report: writes the classic single-frame PDF to
     * [RenderContext.scratch] and hands the freshly baked per-field heatmaps to
     * [onFieldHeatmaps] before they are recycled. The reference bitmap comes
     * pre-scaled from the context and is shared across frames — never recycled
     * here.
     */
    @Suppress("LongParameterList") // per-frame render inputs plus the heatmap callback
    private suspend fun renderFrame(
        ctx: RenderContext,
        data: FloatArray,
        defFile: File,
        frameName: String,
        frameIndex: Int,
        onFieldHeatmaps: (List<FieldResult>) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        val record = ctx.record

        // The deformed original is only the cover image; fall back to the
        // reference rather than losing the whole report over it.
        val originalDefImg = BitmapFactory.decodeFile(defFile.absolutePath)
        val defImg = if (originalDefImg != null) {
            originalDefImg.scale(record.imgW, record.imgH)
        } else {
            ctx.baseImg
        }

        val statsArray = FloatArray(ENGINE_STATS_SIZE) { record.engineStats.getOrElse(it) { 0f } }
        val frameSubset = record.sweepSubsets.getOrElse(frameIndex) { record.subset }
        val frameStep = record.sweepSteps.getOrElse(frameIndex) { record.step }
        val frameWindow = record.sweepStrainWindows.getOrElse(frameIndex) { record.strainWindow }
        val reportData = ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = ctx.baseImg,
                defImgForCover = defImg,
                imgW = record.imgW,
                imgH = record.imgH,
                step = frameStep,
                sessionId = record.id,
                specimenName = record.refName,
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = frameSubset,
                strainWindow = frameWindow,
                strainMethod = record.strainMethod.ifBlank { "VSG" },
                roiData = RoiData(record.roiX, record.roiY, record.roiW, record.roiH),
                engineStats = EngineStats.fromArray(statsArray),
                referenceImageName = "Baseline",
                deformedImageName = frameName,
                drawMinMarker = false,
            ),
        )

        var ok = true
        try {
            ctx.scratch.outputStream().use { stream ->
                PdfReportGenerator.generate(reportData, stream).collect { progress ->
                    if (progress is PdfReportGenerator.Progress.Error) {
                        Timber.e(progress.ex, "PDF generation failed for %s", frameName)
                        ok = false
                    }
                }
            }
            onFieldHeatmaps(reportData.fieldResults)
        } finally {
            reportData.fieldResults.forEach { it.bakedHeatmap.recycle() }
            reportData.znssdHeatmap.recycle()
            if (defImg !== ctx.baseImg && defImg !== originalDefImg) defImg.recycle()
            if (originalDefImg !== null && originalDefImg !== defImg) originalDefImg.recycle()
        }
        ok
    }

    /** The base image for a session's reports: the reference, or a deformed frame if the reference won't decode. */
    private fun decodeBaseImage(refFile: File, rawDeformedDir: File, defName: String?): Bitmap? =
        BitmapFactory.decodeFile(refFile.absolutePath)
            ?: defName?.let { BitmapFactory.decodeFile(File(rawDeformedDir, it).absolutePath) }

    private const val ENGINE_STATS_SIZE = 16
}
