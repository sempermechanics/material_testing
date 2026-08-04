// Share/export hub: one method per export target (PNG, CSV, PDF, bundle) with
// early-return guards and broad IO catches around file writes; literal quality
// constants read clearest inline, so these rules are suppressed for this file.
@file:Suppress("MagicNumber", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

@file:SuppressLint("InflateParams")

package com.indicvision.semper.ui.viewer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.DeterminateProgressDialog
import com.indicvision.semper.imaging.ImageEncode
import com.indicvision.semper.report.AnalysisCsvWriter
import com.indicvision.semper.report.PdfReportGenerator
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.VisualizationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The Results share sheet (wireframe 08). One scope rule: photos share the
 * current frame; the PDF and CSV cover the whole analysis; the ZIP bundles
 * everything. Files are generated into `cacheDir/share` and handed to
 * [SendToSheet], which offers Save to Files (folder icon) and Share.
 */
class ShareCenter(private val host: ResultViewerActivity) {

    private val snap by lazy { host.buildShareSnapshot() }

    fun show() {
        val s = snap ?: return
        val sheet = BottomSheetDialog(host)
        val v = host.layoutInflater.inflate(R.layout.sheet_share, null)
        sheet.setContentView(v)

        val frameName = s.defNames.getOrNull(s.frameIndex) ?: "Frame ${s.frameIndex + 1}"
        v.findViewById<TextView>(R.id.tvShareCaption).text =
            host.resources.getQuantityString(
                R.plurals.share_caption_fmt,
                s.batchFiles.size,
                s.frameIndex + 1,
                s.batchFiles.size,
            )
        v.findViewById<TextView>(R.id.tvSharePhotoSub).text =
            host.getString(R.string.share_photo_sub_fmt, s.typeString, frameName)
        v.findViewById<TextView>(R.id.tvSharePdfSub).text =
            host.resources.getQuantityString(R.plurals.share_pdf_sub_fmt, s.batchFiles.size, s.batchFiles.size)
        v.findViewById<TextView>(R.id.tvShareCsvSub).text =
            host.resources.getQuantityString(R.plurals.share_csv_sub_fmt, s.batchFiles.size, s.batchFiles.size)

        v.findViewById<View>(R.id.rowSharePhoto).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating) { listOf(currentPhoto()) to "image/png" }
        }
        v.findViewById<View>(R.id.rowShareAllPhotos).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating) { allFieldPhotos() to "image/png" }
        }
        v.findViewById<View>(R.id.rowShareAnimations).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating_gif) { fieldAnimations() to "image/gif" }
        }
        v.findViewById<View>(R.id.rowSharePdf).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating_pdf) { report -> listOf(allFramesPdf(report)) to "application/pdf" }
        }
        v.findViewById<View>(R.id.rowShareCsv).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating) { listOf(batchCsv()) to "text/csv" }
        }
        v.findViewById<View>(R.id.rowShareZip).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating_pdf) { report -> listOf(everythingZip(report)) to "application/zip" }
        }
        sheet.show()
    }

    // ── Job runner: progress dialog → system share sheet (+ Local) ───────

    private fun runJob(
        progressText: Int,
        build: suspend (report: (Int, String) -> Unit) -> Pair<List<File>, String>,
    ) {
        val progress = DeterminateProgressDialog(host, host.getString(progressText))
        progress.show()
        host.lifecycleScope.launch {
            try {
                val report: (Int, String) -> Unit = { pct, label -> progress.update(pct, label) }
                val (files, mime) = withContext(Dispatchers.Default) { build(report) }
                // Safety: never hand an empty or missing file to the share sheet —
                // a generator that silently produced nothing would otherwise share
                // a 0-byte document.
                if (files.isEmpty() || files.any { !it.exists() || it.length() == 0L }) {
                    fail(progress, null, "Share produced no usable files")
                    return@launch
                }
                // SAF saves one document; bundle multi-file exports into a zip first.
                val handoff = withContext(Dispatchers.Default) {
                    if (files.size == 1) {
                        files[0] to mime
                    } else {
                        zipInto(files, "${snap?.baseName ?: "analysis"}_export.zip") to "application/zip"
                    }
                }
                if (!handoff.first.exists() || handoff.first.length() == 0L) {
                    fail(progress, null, "Bundled export was empty")
                    return@launch
                }
                progress.dismiss()
                shareWithLocalOption(handoff.first, handoff.second)
            } catch (e: CancellationException) {
                progress.dismiss()
                throw e
            } catch (e: Throwable) {
                // Throwable, not just Exception: a large multi-frame ZIP/PDF export
                // can hit OutOfMemoryError (an Error), which we'd rather surface as
                // a snackbar than let crash the app.
                fail(progress, e, "Share generation failed")
            }
        }
    }

    private fun fail(progress: DeterminateProgressDialog, e: Throwable?, log: String) {
        progress.dismiss()
        if (e != null) Timber.e(e, log) else Timber.e(log)
        Snackbar.make(
            host.findViewById(android.R.id.content),
            R.string.share_failed,
            Snackbar.LENGTH_LONG,
        ).show()
    }

    /**
     * Our own "Send to" sheet: Save to Files (folder icon) + Share. Owning the
     * rows is the only reliable way to show a folder icon — the system share
     * sheet ignores custom icons on EXTRA_INITIAL_INTENTS on Android 12+.
     */
    private fun shareWithLocalOption(file: File, mime: String) {
        SendToSheet.show(host, file, mime)
    }

    private fun shareDir(): File = File(host.cacheDir, "share").apply { mkdirs() }

    /** Bundle several files into a single zip — the SAF picker saves one document. */
    private fun zipInto(files: List<File>, zipName: String): File {
        val out = File(shareDir(), zipName)
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out
    }

    // ── Generators ───────────────────────────────────────────────────────

    /** Annotated PNG of one field for one frame's data. Full-res heatmap + base. */
    private fun renderAnnotated(
        data: FloatArray,
        dataIndex: Int,
        typeString: String,
        frameIndex: Int,
    ): Bitmap {
        val s = snap!!
        val (heatmap, actualMin, actualMax) = VisualizationEngine.generateHeatmap(
            data,
            s.imgW,
            s.imgH,
            dataIndex,
            s.stepAt(frameIndex),
            null,
            null,
            maxLongEdge = null,
        )
        val base = loadFullResBase(s)
        val out = createBitmap(s.imgW, s.imgH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(base, null, Rect(0, 0, s.imgW, s.imgH), null)
        canvas.drawBitmap(heatmap, 0f, 0f, Paint().apply { alpha = HEATMAP_ALPHA })
        val extrema = ReportBuilder.computeFieldExtrema(data, dataIndex)
        val unit = if (DicResult.isStrainFieldIndex(dataIndex)) "mε" else "px"
        ReportBuilder.bakeAnnotationsToCanvas(
            canvas, s.imgW, s.imgH, actualMin, actualMax,
            typeString, unit, extrema.maxIdx, extrema.minIdx, data,
        )
        heatmap.recycle()
        if (base !== s.baseImage) base.recycle()
        return out
    }

    /** Prefer the on-disk reference so export stays full-res when the viewer holds a display bitmap. */
    private fun loadFullResBase(s: Snapshot): Bitmap {
        s.refImagePath?.let { path ->
            BitmapFactory.decodeFile(path)?.let { return it }
        }
        val display = s.baseImage ?: error("No reference image for export")
        if (display.width == s.imgW && display.height == s.imgH) return display
        return display.scale(s.imgW, s.imgH)
    }

    private fun writePng(bmp: Bitmap, name: String): File {
        val f = File(shareDir(), name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, it) }
        bmp.recycle()
        return f
    }

    private fun currentPhoto(): File {
        val s = snap!!
        return writePng(
            renderAnnotated(s.data, s.dataIndex, s.typeString, s.frameIndex),
            "${s.baseName}_${s.typeString}_frame${s.frameIndex + 1}.png",
        )
    }

    private fun allFieldPhotos(): List<File> {
        val s = snap!!
        return FIELDS.map { (label, idx) ->
            writePng(
                renderAnnotated(s.data, idx, label, s.frameIndex),
                "${s.baseName}_${label}_frame${s.frameIndex + 1}.png",
            )
        }
    }

    /**
     * All five fields as looping GIFs, each covering every frame on that field's
     * whole-sequence colour scale.
     *
     * Shared as a set rather than one at a time: the point of the animations is
     * that they are directly comparable, which only holds if you have them all.
     * Fields the viewer has not rendered yet are built here, so sharing works
     * the moment the screen opens.
     */
    private suspend fun fieldAnimations(): List<File> {
        val s = snap!!
        val animation = s.summary ?: return emptyList()
        return SummaryAnimation.FIELDS.mapNotNull { (label, index) ->
            val bounds = s.summaryBounds(index) ?: return@mapNotNull null
            animation.build(index, label, bounds)
        }
    }

    /**
     * One CSV covering every frame's solved points, via the shared
     * [AnalysisCsvWriter] the cloud upload uses too. A sweep leads each row with
     * its settings columns; an ordinary analysis leads with the image name.
     */
    private fun batchCsv(): File {
        val s = snap!!
        val sweep = s.stepPerFrame != null
        // A sweep ran every combination against the one image; a batch has one
        // image per frame.
        val sweepImage = s.defImagePaths.firstOrNull()?.let { File(it).name } ?: "image"
        val frames = s.batchFiles.mapIndexed { index, file ->
            AnalysisCsvWriter.Frame(
                image = if (sweep) sweepImage else s.defNames.getOrNull(index) ?: "Frame_${index + 1}",
                subset = s.subsetPerFrame?.getOrNull(index) ?: 0,
                step = s.stepPerFrame?.getOrNull(index) ?: 0,
                strainWindow = s.strainWindowPerFrame?.getOrNull(index) ?: 0,
                data = { DicResult.decodeDatBytes(file.readBytes()) },
            )
        }
        val f = File(shareDir(), "${s.baseName}_data.csv")
        AnalysisCsvWriter.write(f, sweep, frames)
        return f
    }

    /**
     * One PDF holding every frame's full report, concatenated: each frame gets
     * the same cover / field-pages structure a single-frame report has, and one
     * telemetry page closes the document.
     */
    private suspend fun allFramesPdf(report: (Int, String) -> Unit = { _, _ -> }): File {
        val s = snap!!
        val f = File(shareDir(), "${s.baseName}_report.pdf")
        f.outputStream().use { out ->
            PdfReportGenerator.generateBatch(
                frameCount = s.batchFiles.size,
                dataAt = { index -> frameReport(index) },
                outputStream = out,
                frameTitle = { index -> frameTitle(index) },
            ).collect { progress ->
                when (progress) {
                    // generateBatch reports failures as a Flow event rather than
                    // throwing; surface it so the share job actually fails (and logs)
                    // instead of silently handing back an empty PDF.
                    is PdfReportGenerator.Progress.Error -> throw progress.ex
                    is PdfReportGenerator.Progress.Status -> report(progress.percent, progress.message)
                    PdfReportGenerator.Progress.Complete -> Unit
                }
            }
        }
        return f
    }

    /**
     * The frame's own report data. Built one frame at a time — the generator
     * recycles each frame's bitmaps before asking for the next.
     */
    private fun frameReport(index: Int): com.indicvision.semper.report.ReportData? {
        val s = snap!!
        val data = DicResult.decodeDatBytes(s.batchFiles[index].readBytes()) ?: return null
        return s.buildReportAt(index, data)
    }

    private fun frameTitle(index: Int): String {
        val s = snap!!
        val name = s.defNames.getOrNull(index)?.takeIf { it.isNotBlank() }
        return if (name == null) {
            "DIC Analysis Report — Frame ${index + 1}"
        } else {
            "DIC Analysis Report — $name"
        }
    }

    /**
     * The complete-bundle ZIP (`{ts}` = capture time, `yyyyMMdd_HHmmss`):
     * ```
     * ├── {base}_data.csv                      (root)
     * ├── {base}_report.pdf                    (root)
     * └── photos_{ts}/
     *     ├── raw photos/                      reference + deformed originals
     *     ├── animations/                      U, V, Exx, Eyy, Exy as looping GIFs
     *     └── results/<NNN_frame>/             U, V, Exx, Eyy, Exy per frame
     * ```
     */
    private suspend fun everythingZip(report: (Int, String) -> Unit = { _, _ -> }): File {
        val s = snap!!
        // The PDF is the long pole; give it the first 60% of the bar, then the
        // per-frame result images the last 40%.
        val pdf = allFramesPdf { pct, label -> report(pct * 60 / 100, label) }
        val csv = batchCsv()
        val animations = fieldAnimations()
        report(62, "Bundling files…")
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(shareDir(), "${s.baseName}_everything_$ts.zip")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            addRawPhotos(zip, s, ts)
            for (gif in animations) {
                zip.putNextEntry(ZipEntry("photos_$ts/animations/${gif.name}"))
                gif.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            addResultImages(zip, s, ts) { done, total ->
                report(70 + (if (total > 0) done * 30 / total else 0), "Adding result images…")
            }
            // Home of the archive: the data table and the full report.
            zip.putNextEntry(ZipEntry("${s.baseName}_data.csv"))
            csv.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("${s.baseName}_report.pdf"))
            pdf.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        report(100, "Bundling files…")
        return f
    }

    /** `photos_{ts}/raw photos/` — the reference and (best-effort) deformed originals. */
    private fun addRawPhotos(zip: ZipOutputStream, s: Snapshot, ts: String) {
        val dir = "photos_$ts/raw photos"

        val refFile = s.refImagePath?.let { File(it) }?.takeIf { it.exists() }
        if (refFile != null) {
            zip.putNextEntry(ZipEntry("$dir/reference_${refFile.name}"))
            refFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        } else {
            // No persisted reference file (shouldn't happen) — fall back to the
            // in-memory base image so the folder is never empty.
            zip.putNextEntry(ZipEntry("$dir/reference.png"))
            val base = s.baseImage ?: loadFullResBase(s)
            base.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, zip)
            if (base !== s.baseImage) base.recycle()
            zip.closeEntry()
        }

        // Deformed originals persisted in the session dir; names already carry a
        // sortable NNNN_ prefix. Guarded so a missing file can't abort the export.
        for (path in s.defImagePaths) {
            val df = File(path)
            if (!df.exists()) continue
            zip.putNextEntry(ZipEntry("$dir/${df.name}"))
            df.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    /** `photos_{ts}/results/<NNN_frame>/` — every field's annotated heatmap per frame. */
    private fun addResultImages(
        zip: ZipOutputStream,
        s: Snapshot,
        ts: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        for ((index, file) in s.batchFiles.withIndex()) {
            onProgress(index + 1, s.batchFiles.size)
            val data = DicResult.decodeDatBytes(file.readBytes()) ?: continue
            val prefix = (index + 1).toString().padStart(3, '0')
            val frameName = s.defNames.getOrNull(index)?.substringBeforeLast('.') ?: "Frame_${index + 1}"
            val folder = "photos_$ts/results/${prefix}_$frameName"
            for ((label, idx) in FIELDS) {
                var bmp: Bitmap? = null
                try {
                    bmp = renderAnnotated(data, idx, label, index)
                    zip.putNextEntry(ZipEntry("$folder/$label.png"))
                    bmp.compress(Bitmap.CompressFormat.PNG, ImageEncode.PNG_QUALITY_MAX, zip)
                    zip.closeEntry()
                } catch (e: Exception) {
                    // One unrenderable field shouldn't abort the whole export.
                    Timber.w(e, "Skipping %s of frame %d in ZIP export", label, index + 1)
                } finally {
                    bmp?.recycle()
                }
            }
        }
    }

    /** Everything the generators need, captured once from the viewer. */
    data class Snapshot(
        val data: FloatArray,
        val batchFiles: List<File>,
        val defNames: List<String>,
        /** Filename-safe base for exports, e.g. the specimen/reference name. */
        val baseName: String,
        val frameIndex: Int,
        val imgW: Int,
        val imgH: Int,
        val step: Int,
        /**
         * Per-frame step sizes for a parameter sweep, where each frame is a
         * different settings combination. Null for an ordinary analysis, whose
         * frames all share [step]. Its non-null-ness marks a sweep, which the
         * CSV export splits into subset/step/window/VSG columns.
         */
        val stepPerFrame: IntArray?,
        /** Per-frame subset sizes for a sweep; index-aligned with the frames. */
        val subsetPerFrame: IntArray?,
        /** Per-frame strain windows for a sweep; index-aligned with the frames. */
        val strainWindowPerFrame: IntArray?,
        val dataIndex: Int,
        val typeString: String,
        /** Display-scale bitmap (may be null while decode is in flight); exports prefer [refImagePath]. */
        val baseImage: Bitmap?,
        val refImagePath: String?,
        val defImagePaths: List<String>,
        /** The viewer's animation builder, so a share reuses what it already rendered. */
        val summary: SummaryAnimation?,
        /** Whole-sequence colour bounds of a field, or null if it has no data. */
        val summaryBounds: (Int) -> Pair<Float, Float>?,
        /**
         * Report data for one frame, given that frame's index and decoded
         * field. Index-driven so an all-frames report can build each frame's
         * own cover — its parameters and its deformed image — rather than
         * reusing the one on screen.
         */
        val buildReportAt: (Int, FloatArray) -> com.indicvision.semper.report.ReportData?,
    ) {
        /** Grid pitch of frame [index] — what rendering that frame depends on. */
        fun stepAt(index: Int): Int = stepPerFrame?.getOrNull(index) ?: step
    }

    private companion object {
        const val HEATMAP_ALPHA = 180

        val FIELDS = listOf(
            "U" to DicResult.IDX_U,
            "V" to DicResult.IDX_V,
            "Exx" to DicResult.IDX_EXX,
            "Eyy" to DicResult.IDX_EYY,
            "Exy" to DicResult.IDX_EXY,
        )
    }
}
