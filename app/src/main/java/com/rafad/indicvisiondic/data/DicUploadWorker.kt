package com.rafad.indicvisiondic.data
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.report.EngineStats
import com.rafad.indicvisiondic.report.PdfReportGenerator
import com.rafad.indicvisiondic.report.ReportBuilder
import com.rafad.indicvisiondic.report.RoiData
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline-first cloud sync. Enqueued after each analysis with a network
 * constraint: when connectivity exists it creates the `analysis_sessions`
 * ledger row and uploads raw images, CSV, and the PDF report to Supabase
 * storage. See docs/BACKEND.md for the bucket layout.
 */
class DicUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userEmail = inputData.getString(DicKeys.USER_EMAIL) ?: "Unknown_User"
        val userId = inputData.getString(DicKeys.USER_ID) ?: return@withContext Result.failure()

        val refPath = inputData.getString(DicKeys.REF_PATH) ?: return@withContext Result.failure()
        val defPath = inputData.getString(DicKeys.DEF_PATH) ?: return@withContext Result.failure()
        val datPath = inputData.getString(DicKeys.DAT_PATH) ?: return@withContext Result.failure()
        val frameName = inputData.getString(DicKeys.FRAME_NAME) ?: "Frame"

        Log.d("inDIC_Diag", "========================================")
        Log.d("inDIC_Diag", "Upload worker started (network available)")

        try {
            Log.d("inDIC_Diag", "-> Step 1: Connecting to Supabase Database...")
            val sessionData = AnalysisSessionInsert(
                userId = userId,
                userEmail = userEmail,
                specimenIdentifier = inputData.getString(DicKeys.REF_NAME) ?: "Target",
                pointsConverged = inputData.getInt(DicKeys.POINTS_CONVERGED, 0),
                avgIterations = inputData.getFloat(DicKeys.AVG_ITERS, 0f),
                executionTimeMs = inputData.getInt(DicKeys.EXEC_TIME, 0),
            )

            val insertedRow = SupabaseManager.client.postgrest["analysis_sessions"]
                .insert(sessionData) { select() }
                .decodeSingle<AnalysisSessionResponse>()

            val trueSessionId = insertedRow.sessionId
            val cloudFolder = "$userEmail/Session_$trueSessionId"
            Log.d("inDIC_Diag", "Row Created! Target Folder: $cloudFolder")

            val storageBucket = SupabaseManager.client.storage["session_artifacts"]

            Log.d("inDIC_Diag", "-> Step 2: Uploading Reference Image...")
            val refFile = File(refPath)
            if (refFile.exists()) {
                storageBucket.upload("$cloudFolder/Reference.png", refFile.readBytes()) { upsert = true }
                Log.d("inDIC_Diag", "Reference Uploaded.")
            }

            Log.d("inDIC_Diag", "-> Step 3: Uploading Deformed Image...")
            val defFile = File(defPath)
            if (defFile.exists()) {
                storageBucket.upload("$cloudFolder/Deformed_$frameName.png", defFile.readBytes()) { upsert = true }
                Log.d("inDIC_Diag", "Deformed Uploaded.")
            }

            Log.d("inDIC_Diag", "-> Step 4: Generating CSV...")
            val datFile = File(datPath)
            var publicCsvUrl = ""
            var rawFloatData: FloatArray? = null

            if (datFile.exists()) {
                val data = DicResult.decodeDatBytes(datFile.readBytes())
                if (data != null) {
                    rawFloatData = data
                    val csvContent = StringBuilder(
                        "X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n",
                    )
                    var i = 0
                    while (i < data.size) {
                        val c = data[i + DicResult.IDX_ZNSSD]
                        if (DicResult.isSolvedPoint(c)) {
                            csvContent.append(
                                "${data[i]},${data[i + 1]},${data[i + 2]},${data[i + 3]}," +
                                    "${data[i + 4]},${data[i + 5]},${data[i + 6]},$c\n",
                            )
                        }
                        i += DicResult.STRIDE
                    }
                    val csvCloudPath = "$cloudFolder/Data_$frameName.csv"
                    storageBucket.upload(csvCloudPath, csvContent.toString().toByteArray()) { upsert = true }
                    publicCsvUrl = storageBucket.publicUrl(csvCloudPath)
                    Log.d("inDIC_Diag", "CSV Uploaded.")
                }
            }

            Log.d("inDIC_Diag", "-> Step 5: Generating PDF in Background...")
            var publicPdfUrl = ""
            if (rawFloatData != null && refFile.exists() && defFile.exists()) {
                publicPdfUrl = generateHeadlessPdfAndUpload(
                    rawFloatData,
                    refFile,
                    defFile,
                    cloudFolder,
                    storageBucket,
                    frameName,
                    trueSessionId,
                )
                Log.d("inDIC_Diag", "PDF Uploaded.")
            }

            Log.d("inDIC_Diag", "-> Step 6: Updating Database Ledger...")
            if (publicCsvUrl.isNotEmpty() || publicPdfUrl.isNotEmpty()) {
                val updateMap = mutableMapOf<String, String>()
                if (publicCsvUrl.isNotEmpty()) updateMap["summary_csv_path"] = publicCsvUrl
                if (publicPdfUrl.isNotEmpty()) updateMap["heatmap_png_path"] = publicPdfUrl

                SupabaseManager.client.postgrest["analysis_sessions"].update(updateMap) {
                    filter { eq("session_id", trueSessionId) }
                }
                Log.d("inDIC_Diag", "Ledger Updated.")
            }

            Log.d("inDIC_Diag", "Upload worker finished successfully")
            Log.d("inDIC_Diag", "========================================")
            Result.success()
        } catch (e: Exception) {
            Log.e("inDIC_Diag", "========================================")
            Log.e("inDIC_Diag", "Upload worker failed; will retry")
            Log.e("inDIC_Diag", "Error Message: ${e.message}")
            Log.e("inDIC_Diag", "========================================")
            Result.retry()
        }
    }

    private suspend fun generateHeadlessPdfAndUpload(
        data: FloatArray,
        refFile: File,
        defFile: File,
        cloudFolder: String,
        storageBucket: io.github.jan.supabase.storage.BucketApi,
        frameName: String,
        trueSessionId: String,
    ): String = withContext(Dispatchers.Default) {
        val imgW = inputData.getInt(DicKeys.IMG_W, 1000)
        val imgH = inputData.getInt(DicKeys.IMG_H, 1000)
        val step = inputData.getInt(DicKeys.STEP, 5)

        val originalBaseImg = BitmapFactory.decodeFile(refFile.absolutePath) ?: return@withContext ""
        // Null when the deformed frame can't be decoded — the cover card then
        // reuses baseImg, and cleanup below must not recycle it twice.
        val originalDefImg = BitmapFactory.decodeFile(defFile.absolutePath)

        val baseImg = Bitmap.createScaledBitmap(originalBaseImg, imgW, imgH, true)
        val defImg = Bitmap.createScaledBitmap(originalDefImg ?: originalBaseImg, imgW, imgH, true)

        val statsArray = inputData.getFloatArray(DicKeys.ENGINE_STATS) ?: FloatArray(16)
        val reportData = ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = baseImg,
                defImgForCover = defImg,
                imgW = imgW,
                imgH = imgH,
                step = step,
                sessionId = trueSessionId,
                specimenName = inputData.getString(DicKeys.REF_NAME) ?: "Target",
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = inputData.getInt(DicKeys.SUBSET, 41),
                strainWindow = inputData.getInt(DicKeys.STRAIN_WIN, 15),
                strainMethod = inputData.getString(DicKeys.STRAIN_METHOD) ?: "VSG",
                roiData = RoiData(
                    inputData.getInt(DicKeys.ROI_X, 0),
                    inputData.getInt(DicKeys.ROI_Y, 0),
                    inputData.getInt(DicKeys.ROI_W, imgW),
                    inputData.getInt(DicKeys.ROI_H, imgH),
                ),
                engineStats = EngineStats.fromArray(statsArray),
                referenceImageName = "Baseline",
                deformedImageName = frameName,
                drawMinMarker = false,
            ),
        )

        val tempPdfFile = File(applicationContext.cacheDir, "headless_master_report.pdf")
        tempPdfFile.outputStream().use { stream ->
            PdfReportGenerator.generate(reportData, stream).collect { }
        }

        val pdfCloudPath = "$cloudFolder/Master_Report_$frameName.pdf"
        storageBucket.upload(pdfCloudPath, tempPdfFile.readBytes()) { upsert = true }

        reportData.fieldResults.forEach { it.bakedHeatmap.recycle() }
        reportData.znssdHeatmap.recycle()
        baseImg.recycle()
        defImg.recycle()
        originalBaseImg.recycle()
        originalDefImg?.recycle()
        tempPdfFile.delete()

        storageBucket.publicUrl(pdfCloudPath)
    }
}
