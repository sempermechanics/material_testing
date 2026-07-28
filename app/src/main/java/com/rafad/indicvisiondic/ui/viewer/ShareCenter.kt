// Share/export hub: one method per export target (PNG, CSV, PDF, bundle) with
// early-return guards and broad IO catches around file writes; literal quality
// constants read clearest inline, so these rules are suppressed for this file.
@file:Suppress("MagicNumber", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.rafad.indicvisiondic.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.report.AnalysisCsvWriter
import com.rafad.indicvisiondic.report.PdfReportGenerator
import com.rafad.indicvisiondic.report.ReportBuilder
import com.rafad.indicvisiondic.report.VisualizationEngine
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
 * everything. Files are generated into `cacheDir/share` and handed to the
 * Android share sheet via FileProvider, with Save to Files as an initial
 * chooser target alongside other apps.
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
            host.getString(R.string.share_caption_fmt, s.frameIndex + 1, s.batchFiles.size)
        v.findViewById<TextView>(R.id.tvSharePhotoSub).text =
            host.getString(R.string.share_photo_sub_fmt, s.typeString, frameName)
        v.findViewById<TextView>(R.id.tvSharePdfSub).text =
            host.getString(R.string.share_pdf_sub_fmt, s.batchFiles.size)
        v.findViewById<TextView>(R.id.tvShareCsvSub).text =
            host.getString(R.string.share_csv_sub_fmt, s.batchFiles.size)

        v.findViewById<View>(R.id.rowSharePhoto).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating) { listOf(currentPhoto()) to "image/png" }
        }
        v.findViewById<View>(R.id.rowShareAllPhotos).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating) { allFieldPhotos() to "image/png" }
        }
        v.findViewById<View>(R.id.rowSharePdf).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating_pdf) { listOf(allFramesPdf()) to "application/pdf" }
        }
        v.findViewById<View>(R.id.rowShareCsv).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating) { listOf(batchCsv()) to "text/csv" }
        }
        v.findViewById<View>(R.id.rowShareZip).setOnClickListener {
            sheet.dismiss()
            runJob(R.string.share_generating_pdf) { listOf(everythingZip()) to "application/zip" }
        }
        sheet.show()
    }

    // ── Job runner: progress dialog → system share sheet (+ Local) ───────

    private fun runJob(progressText: Int, build: suspend () -> Pair<List<File>, String>) {
        val progress = MaterialAlertDialogBuilder(host)
            .setMessage(progressText)
            .setCancelable(false)
            .show()
        host.lifecycleScope.launch {
            try {
                val (files, mime) = withContext(Dispatchers.Default) { build() }
                // SAF saves one document; bundle multi-file exports into a zip first.
                val handoff = withContext(Dispatchers.Default) {
                    if (files.size == 1) {
                        files[0] to mime
                    } else {
                        zipInto(files, "inDIC_export.zip") to "application/zip"
                    }
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
                progress.dismiss()
                Timber.e(e, "Share generation failed")
                Snackbar.make(
                    host.findViewById(android.R.id.content),
                    R.string.share_failed,
                    Snackbar.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * System share chooser with an initial "Save to Files" target so Local sits
     * alongside other apps — no mode toggle on the sheet.
     */
    private fun shareWithLocalOption(file: File, mime: String) {
        val uri = FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, host.getString(R.string.action_share)).apply {
            putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                arrayOf(SaveExportActivity.intent(host, file, mime)),
            )
        }
        host.startActivity(chooser)
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
        val out = Bitmap.createBitmap(s.imgW, s.imgH, Bitmap.Config.ARGB_8888)
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
        return Bitmap.createScaledBitmap(display, s.imgW, s.imgH, true)
    }

    private fun writePng(bmp: Bitmap, name: String): File {
        val f = File(shareDir(), name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        bmp.recycle()
        return f
    }

    private fun currentPhoto(): File {
        val s = snap!!
        return writePng(
            renderAnnotated(s.data, s.dataIndex, s.typeString, s.frameIndex),
            "inDIC_${s.typeString}_frame${s.frameIndex + 1}.png",
        )
    }

    private fun allFieldPhotos(): List<File> {
        val s = snap!!
        return FIELDS.map { (label, idx) ->
            writePng(
                renderAnnotated(s.data, idx, label, s.frameIndex),
                "inDIC_${label}_frame${s.frameIndex + 1}.png",
            )
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
        val f = File(shareDir(), "inDIC_analysis_data.csv")
        AnalysisCsvWriter.write(f, sweep, frames)
        return f
    }

    /**
     * One PDF holding every frame's full report, concatenated: each frame gets
     * the same cover / field-pages structure a single-frame report has, and one
     * telemetry page closes the document.
     */
    private suspend fun allFramesPdf(): File {
        val s = snap!!
        val f = File(shareDir(), "inDIC_report_all_frames.pdf")
        f.outputStream().use { out ->
            PdfReportGenerator.generateBatch(
                frameCount = s.batchFiles.size,
                dataAt = { index -> frameReport(index) },
                outputStream = out,
                frameTitle = { index -> frameTitle(index) },
            ).collect { progress ->
                // generateBatch reports failures as a Flow event rather than
                // throwing; surface it so the share job actually fails (and logs)
                // instead of silently handing back an empty PDF.
                if (progress is PdfReportGenerator.Progress.Error) throw progress.ex
            }
        }
        return f
    }

    /**
     * The frame's own report data. Built one frame at a time — the generator
     * recycles each frame's bitmaps before asking for the next.
     */
    private fun frameReport(index: Int): com.rafad.indicvisiondic.report.ReportData? {
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
     * ├── inDIC_analysis_{ts}_data.csv         (root)
     * ├── inDIC_report_all_{ts}_frames.pdf     (root)
     * └── photos_{ts}/
     *     ├── raw photos/                      reference + deformed originals
     *     └── results/<NNN_frame>/             U, V, Exx, Eyy, Exy per frame
     * ```
     */
    private suspend fun everythingZip(): File {
        val s = snap!!
        val pdf = allFramesPdf()
        val csv = batchCsv()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(shareDir(), "inDIC_everything_$ts.zip")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            addRawPhotos(zip, s, ts)
            addResultImages(zip, s, ts)
            // Home of the archive: the data table and the full report.
            zip.putNextEntry(ZipEntry("inDIC_analysis_${ts}_data.csv"))
            csv.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("inDIC_report_all_${ts}_frames.pdf"))
            pdf.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
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
            base.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, zip)
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
    private fun addResultImages(zip: ZipOutputStream, s: Snapshot, ts: String) {
        for ((index, file) in s.batchFiles.withIndex()) {
            val data = DicResult.decodeDatBytes(file.readBytes()) ?: continue
            val prefix = (index + 1).toString().padStart(3, '0')
            val frameName = s.defNames.getOrNull(index)?.substringBeforeLast('.') ?: "Frame_${index + 1}"
            val folder = "photos_$ts/results/${prefix}_$frameName"
            for ((label, idx) in FIELDS) {
                var bmp: Bitmap? = null
                try {
                    bmp = renderAnnotated(data, idx, label, index)
                    zip.putNextEntry(ZipEntry("$folder/inDIC_$label.png"))
                    bmp.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, zip)
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
        /**
         * Report data for one frame, given that frame's index and decoded
         * field. Index-driven so an all-frames report can build each frame's
         * own cover — its parameters and its deformed image — rather than
         * reusing the one on screen.
         */
        val buildReportAt: (Int, FloatArray) -> com.rafad.indicvisiondic.report.ReportData?,
    ) {
        /** Grid pitch of frame [index] — what rendering that frame depends on. */
        fun stepAt(index: Int): Int = stepPerFrame?.getOrNull(index) ?: step
    }

    private companion object {
        const val HEATMAP_ALPHA = 180
        const val PNG_QUALITY = 100

        val FIELDS = listOf(
            "U" to DicResult.IDX_U,
            "V" to DicResult.IDX_V,
            "Exx" to DicResult.IDX_EXX,
            "Eyy" to DicResult.IDX_EYY,
            "Exy" to DicResult.IDX_EXY,
        )
    }
}
