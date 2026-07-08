package com.rafad.indicvisiondic.ui.viewer
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.report.ReportBuilder
import com.rafad.indicvisiondic.report.VisualizationEngine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * File exports for analysis results (CSV / batch ZIP / batch CSV / annotated
 * PNG), extracted from [ResultViewerActivity]. Pure data-in → MediaStore-out:
 * the activity keeps UI concerns (preconditions, progress dialogs) and hands
 * over an immutable snapshot of what to export.
 */
class ResultExporter(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /** Snapshot of viewer state shared by the batch exports. */
    data class BatchSnapshot(
        val batchFiles: List<File>,
        val originalDefNames: List<String>,
        val refName: String?,
    )

    private fun toast(resId: Int, long: Boolean = false) {
        Toast.makeText(context, resId, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private suspend fun toastOnMain(resId: Int, long: Boolean = false) =
        withContext(Dispatchers.Main) { toast(resId, long) }

    /** Shared MediaStore insert; returns null if the resolver refuses. */
    private fun insertMediaStore(
        collection: Uri,
        fileName: String,
        mimeType: String,
        relativePath: String,
    ): Pair<Uri, OutputStream>? {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, contentValues) ?: return null
        val stream = resolver.openOutputStream(uri) ?: return null
        return uri to stream
    }

    private fun frameLabel(defNames: List<String>, frameIndex: Int): String =
        defNames.getOrNull(frameIndex)?.substringBeforeLast(".") ?: "Frame_${frameIndex + 1}"

    // ── Single-frame CSV ─────────────────────────────────────────────────

    fun exportCsv(data: FloatArray, defNames: List<String>, frameIndex: Int) {
        toast(R.string.saving_csv)

        scope.launch(Dispatchers.IO) {
            val fileName = "IndicVision_${frameLabel(defNames, frameIndex)}.csv"
            try {
                val target = insertMediaStore(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, fileName,
                    "text/csv", Environment.DIRECTORY_DOWNLOADS + "/IndicVision"
                ) ?: return@launch

                target.second.use { outputStream ->
                    val writer = outputStream.bufferedWriter()
                    writer.write("X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n")
                    writeCsvRows(writer, data, prefix = null)
                    writer.flush()
                }
                toastOnMain(R.string.csv_saved, long = true)
            } catch (e: Exception) {
                Log.e("ResultExporter", "CSV export failed", e)
                toastOnMain(R.string.csv_save_failed)
            }
        }
    }

    private fun writeCsvRows(writer: java.io.BufferedWriter, data: FloatArray, prefix: String?) {
        var i = 0
        while (i < data.size) {
            val c = data[i + DicResult.IDX_ZNSSD]
            if (DicResult.isSolvedPoint(c)) {
                val row = "${data[i]},${data[i + 1]}," +
                    "${data[i + DicResult.IDX_U]},${data[i + DicResult.IDX_V]}," +
                    "${data[i + DicResult.IDX_EXX]},${data[i + DicResult.IDX_EYY]}," +
                    "${data[i + DicResult.IDX_EXY]},$c\n"
                writer.write(if (prefix != null) "$prefix,$row" else row)
            }
            i += DicResult.STRIDE
        }
    }

    // ── Batch heatmap-image ZIP ──────────────────────────────────────────

    fun exportAllImagesZip(
        snapshot: BatchSnapshot,
        baseImage: Bitmap,
        imgW: Int,
        imgH: Int,
        dataIndex: Int,
        step: Int,
        typeString: String,
    ) {
        toast(R.string.saving_images_zip, long = true)

        scope.launch(Dispatchers.IO) {
            val refName = snapshot.refName ?: "Batch"
            val fileName = "IndicVision_Images_${typeString}_${refName}.zip"
            try {
                val target = insertMediaStore(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, fileName,
                    "application/zip", Environment.DIRECTORY_DOWNLOADS + "/IndicVision"
                ) ?: return@launch

                target.second.use { outputStream ->
                    ZipOutputStream(outputStream).use { zipOut ->
                        val alphaPaint = Paint().apply { alpha = 180 }

                        for (index in snapshot.batchFiles.indices) {
                            val file = snapshot.batchFiles[index]
                            val data = DicResult.decodeDatBytes(file.readBytes()) ?: continue

                            val (heatmap, _, _) = VisualizationEngine.generateHeatmap(
                                data, imgW, imgH, dataIndex, step
                            )

                            val mergedBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(mergedBitmap)
                            canvas.drawBitmap(baseImage, 0f, 0f, null)
                            canvas.drawBitmap(heatmap, 0f, 0f, alphaPaint)

                            val trueFrameIndex = file.nameWithoutExtension.substringAfterLast("_").toIntOrNull() ?: index
                            val imgName = snapshot.originalDefNames.getOrNull(trueFrameIndex) ?: "Frame_${trueFrameIndex + 1}"

                            zipOut.putNextEntry(ZipEntry("IndicVision_${typeString}_${imgName}.png"))
                            mergedBitmap.compress(Bitmap.CompressFormat.PNG, 100, zipOut)
                            zipOut.closeEntry()

                            heatmap.recycle()
                            mergedBitmap.recycle()
                        }
                    }
                }
                toastOnMain(R.string.images_zip_saved, long = true)
            } catch (e: Exception) {
                Log.e("ResultExporter", "ZIP export failed", e)
                toastOnMain(R.string.images_zip_failed)
            }
        }
    }

    // ── Batch master CSV ─────────────────────────────────────────────────

    fun exportAllDataCsv(snapshot: BatchSnapshot) {
        toast(R.string.saving_master_csv, long = true)

        scope.launch(Dispatchers.IO) {
            val refName = snapshot.refName ?: "Batch"
            val fileName = "IndicVision_BatchData_${refName}.csv"
            try {
                val target = insertMediaStore(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, fileName,
                    "text/csv", Environment.DIRECTORY_DOWNLOADS + "/IndicVision"
                ) ?: return@launch

                target.second.use { outputStream ->
                    outputStream.bufferedWriter().use { writer ->
                        writer.write("Image_Name,X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n")

                        for (index in snapshot.batchFiles.indices) {
                            val file = snapshot.batchFiles[index]
                            val data = DicResult.decodeDatBytes(file.readBytes()) ?: continue

                            val trueFrameIndex = file.nameWithoutExtension.substringAfterLast("_").toIntOrNull() ?: index
                            val imgName = snapshot.originalDefNames.getOrNull(trueFrameIndex) ?: "Frame_${trueFrameIndex + 1}"
                            writeCsvRows(writer, data, prefix = imgName)
                        }
                    }
                }
                toastOnMain(R.string.master_csv_saved, long = true)
            } catch (e: Exception) {
                Log.e("ResultExporter", "Master CSV export failed", e)
                toastOnMain(R.string.master_csv_failed)
            }
        }
    }

    // ── Annotated single-frame PNG ───────────────────────────────────────

    fun exportMergedImage(
        base: Bitmap,
        overlay: Bitmap,
        data: FloatArray,
        imgW: Int,
        imgH: Int,
        dataIndex: Int,
        typeString: String,
        heatmapMin: Float,
        heatmapMax: Float,
        maxIdx: Int,
        minIdx: Int,
        defNames: List<String>,
        frameIndex: Int,
    ) {
        toast(R.string.saving_image)

        scope.launch(Dispatchers.Default) {
            try {
                val mergedBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(mergedBitmap)

                canvas.drawBitmap(base, 0f, 0f, null)
                canvas.drawBitmap(overlay, 0f, 0f, Paint().apply { alpha = 180 })

                val isStrain = DicResult.isStrainFieldIndex(dataIndex)
                val unit = if (isStrain) "mε" else "px"
                ReportBuilder.bakeAnnotationsToCanvas(
                    canvas, imgW, imgH, heatmapMin, heatmapMax,
                    typeString, unit, maxIdx, minIdx, data
                )

                val fileName = "IndicVision_${typeString}_${frameLabel(defNames, frameIndex)}.png"

                withContext(Dispatchers.IO) {
                    insertMediaStore(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, fileName,
                        "image/png", Environment.DIRECTORY_PICTURES + "/IndicVision"
                    )?.second?.use { outputStream ->
                        mergedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                    mergedBitmap.recycle()
                }
                toastOnMain(R.string.image_saved, long = true)
            } catch (e: Exception) {
                Log.e("ResultExporter", "Image export failed", e)
                toastOnMain(R.string.image_save_failed)
            }
        }
    }
}
