package com.rafad.indicvisiondic.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.R
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
 * Android share sheet via FileProvider — which includes save-to-device.
 */
class ShareCenter(private val host: ResultViewerActivity) {

    private val snap by lazy { host.buildShareSnapshot() }

    /** When on, artifacts are written to a user-chosen location instead of shared. */
    private var saveMode = false

    fun show() {
        val s = snap ?: return
        val sheet = BottomSheetDialog(host)
        val v = host.layoutInflater.inflate(R.layout.sheet_share, null)
        sheet.setContentView(v)

        v.findViewById<android.widget.CompoundButton>(R.id.switchSaveMode)
            .setOnCheckedChangeListener { _, checked -> saveMode = checked }

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

    // ── Job runner: progress dialog → system share sheet ────────────────

    private fun runJob(progressText: Int, build: suspend () -> Pair<List<File>, String>) {
        val progress = MaterialAlertDialogBuilder(host)
            .setMessage(progressText)
            .setCancelable(false)
            .show()
        host.lifecycleScope.launch {
            try {
                val (files, mime) = withContext(Dispatchers.Default) { build() }
                if (saveMode) {
                    // SAF saves one document; bundle multi-file exports into a zip first.
                    val save = withContext(Dispatchers.Default) {
                        if (files.size == 1) {
                            files[0] to mime
                        } else {
                            zipInto(files, "inDIC_export.zip") to "application/zip"
                        }
                    }
                    progress.dismiss()
                    host.saveFileToDevice(save.first, save.second)
                } else {
                    progress.dismiss()
                    shareFiles(files, mime)
                }
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

    private fun shareFiles(files: List<File>, mime: String) {
        val uris = ArrayList<Uri>(files.size)
        for (f in files) {
            uris.add(
                FileProvider.getUriForFile(host, "${host.packageName}.fileprovider", f),
            )
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        host.startActivity(Intent.createChooser(intent, host.getString(R.string.action_share)))
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

    /** Annotated PNG of one field for one frame's data. */
    private fun renderAnnotated(data: FloatArray, dataIndex: Int, typeString: String): Bitmap {
        val s = snap!!
        val (heatmap, actualMin, actualMax) = VisualizationEngine.generateHeatmap(
            data,
            s.imgW,
            s.imgH,
            dataIndex,
            s.step,
            null,
            null,
        )
        val extrema = ReportBuilder.computeFieldExtrema(data, dataIndex)
        val out = Bitmap.createBitmap(s.imgW, s.imgH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(s.baseImage, 0f, 0f, null)
        canvas.drawBitmap(heatmap, 0f, 0f, Paint().apply { alpha = HEATMAP_ALPHA })
        val unit = if (DicResult.isStrainFieldIndex(dataIndex)) "mε" else "px"
        ReportBuilder.bakeAnnotationsToCanvas(
            canvas, s.imgW, s.imgH, actualMin, actualMax,
            typeString, unit, extrema.maxIdx, extrema.minIdx, data,
        )
        heatmap.recycle()
        return out
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
            renderAnnotated(s.data, s.dataIndex, s.typeString),
            "inDIC_${s.typeString}_frame${s.frameIndex + 1}.png",
        )
    }

    private fun allFieldPhotos(): List<File> {
        val s = snap!!
        return FIELDS.map { (label, idx) ->
            writePng(
                renderAnnotated(s.data, idx, label),
                "inDIC_${label}_frame${s.frameIndex + 1}.png",
            )
        }
    }

    private fun batchCsv(): File {
        val s = snap!!
        val f = File(shareDir(), "inDIC_analysis_data.csv")
        f.bufferedWriter().use { w ->
            w.write("Image_Name,X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n")
            for ((index, file) in s.batchFiles.withIndex()) {
                val data = DicResult.decodeDatBytes(file.readBytes()) ?: continue
                val name = s.defNames.getOrNull(index) ?: "Frame_${index + 1}"
                var i = 0
                while (i < data.size) {
                    if (DicResult.isSolvedPoint(data[i + DicResult.IDX_ZNSSD])) {
                        w.write("$name,${DicResult.csvRow(data, i)}\n")
                    }
                    i += DicResult.STRIDE
                }
            }
        }
        return f
    }

    /** One PDF covering every frame: cover + chapter per frame + telemetry. */
    private suspend fun allFramesPdf(): File {
        val s = snap!!
        val cover = s.buildReport() ?: error("report data unavailable")
        val f = File(shareDir(), "inDIC_report_all_frames.pdf")
        f.outputStream().use { out ->
            PdfReportGenerator.generateBatch(
                cover = cover,
                frameCount = s.batchFiles.size,
                chapterAt = { index -> buildChapter(index) },
                outputStream = out,
            ).collect { progress ->
                // generateBatch reports failures as a Flow event rather than
                // throwing; surface it so the share job actually fails (and logs)
                // instead of silently handing back an empty PDF.
                if (progress is PdfReportGenerator.Progress.Error) throw progress.ex
            }
        }
        cover.fieldResults.forEach { it.bakedHeatmap.recycle() }
        cover.znssdHeatmap.recycle()
        return f
    }

    private fun buildChapter(index: Int): PdfReportGenerator.FrameChapter {
        val s = snap!!
        val data = DicResult.decodeDatBytes(s.batchFiles[index].readBytes())
            ?: return PdfReportGenerator.FrameChapter("Frame ${index + 1} (unreadable)", null, emptyList())
        val name = s.defNames.getOrNull(index) ?: "Frame_${index + 1}"
        val image = renderAnnotated(data, s.dataIndex, s.typeString)
        val rows = FIELDS.map { (label, idx) ->
            val stats = DicResult.fieldStats(data, idx) ?: floatArrayOf(0f, 0f, 0f)
            val unit = if (DicResult.isStrainFieldIndex(idx)) "mε" else "px"
            listOf(
                "$label [$unit]",
                ReportBuilder.formatMetric(stats[0]),
                ReportBuilder.formatMetric(stats[1]),
                ReportBuilder.formatMetric(stats[2]),
            )
        }
        return PdfReportGenerator.FrameChapter(name, image, rows)
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
            s.baseImage.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, zip)
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
                    bmp = renderAnnotated(data, idx, label)
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
        val dataIndex: Int,
        val typeString: String,
        val baseImage: Bitmap,
        val refImagePath: String?,
        val defImagePaths: List<String>,
        val buildReport: () -> com.rafad.indicvisiondic.report.ReportData?,
    )

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
