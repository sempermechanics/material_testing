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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
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

    // ── Job runner: progress dialog → system share sheet ────────────────

    private fun runJob(progressText: Int, build: suspend () -> Pair<List<File>, String>) {
        val progress = MaterialAlertDialogBuilder(host)
            .setMessage(progressText)
            .setCancelable(false)
            .show()
        host.lifecycleScope.launch {
            try {
                val (files, mime) = withContext(Dispatchers.Default) { build() }
                progress.dismiss()
                shareFiles(files, mime)
            } catch (e: Exception) {
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
                    val c = data[i + DicResult.IDX_ZNSSD]
                    if (DicResult.isSolvedPoint(c)) {
                        w.write(
                            "$name,${data[i]},${data[i + 1]},${data[i + DicResult.IDX_U]}," +
                                "${data[i + DicResult.IDX_V]},${data[i + DicResult.IDX_EXX]}," +
                                "${data[i + DicResult.IDX_EYY]},${data[i + DicResult.IDX_EXY]},$c\n",
                        )
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
            ).collect { }
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
            val stats = fieldStats(data, idx)
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

    /** [max, min, mean] over accepted points, unit-scaled. */
    private fun fieldStats(data: FloatArray, dataIndex: Int): FloatArray {
        val multiplier = DicResult.strainMultiplier(dataIndex)
        var maxV = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var sum = 0.0
        var n = 0
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val v = data[i + dataIndex] * multiplier
                if (v > maxV) maxV = v
                if (v < minV) minV = v
                sum += v
                n++
            }
            i += DicResult.STRIDE
        }
        return if (n == 0) {
            floatArrayOf(0f, 0f, 0f)
        } else {
            floatArrayOf(maxV, minV, (sum / n).toFloat())
        }
    }

    private suspend fun everythingZip(): File {
        val s = snap!!
        val pdf = allFramesPdf()
        val csv = batchCsv()
        val f = File(shareDir(), "inDIC_everything.zip")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            for ((index, file) in s.batchFiles.withIndex()) {
                val data = DicResult.decodeDatBytes(file.readBytes()) ?: continue
                val name = s.defNames.getOrNull(index) ?: "Frame_${index + 1}"
                val bmp = renderAnnotated(data, s.dataIndex, s.typeString)
                zip.putNextEntry(ZipEntry("photos/inDIC_${s.typeString}_$name.png"))
                bmp.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, zip)
                zip.closeEntry()
                bmp.recycle()
            }
            zip.putNextEntry(ZipEntry(pdf.name))
            pdf.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(csv.name))
            csv.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return f
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
