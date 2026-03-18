package com.rafad.indicvisiondic

import android.content.Context
import android.graphics.*
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DicUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val userEmail = inputData.getString("USER_EMAIL") ?: "Unknown_User"
        val userId = inputData.getString("USER_ID") ?: return@withContext Result.failure()

        val refPath = inputData.getString("REF_PATH") ?: return@withContext Result.failure()
        val defPath = inputData.getString("DEF_PATH") ?: return@withContext Result.failure()
        val datPath = inputData.getString("DAT_PATH") ?: return@withContext Result.failure()
        val frameName = inputData.getString("FRAME_NAME") ?: "Frame"

        Log.d("inDIC_Diag", "========================================")
        Log.d("inDIC_Diag", "👻 GHOST WORKER WOKE UP (NETWORK DETECTED)!")

        try {
            // 🚀 STEP 1: CREATE THE DATABASE ROW FIRST
            Log.d("inDIC_Diag", "-> Step 1: Connecting to Supabase Database...")
            val sessionData = AnalysisSessionInsert(
                userId = userId,
                specimenIdentifier = inputData.getString("REF_NAME") ?: "Target",
                pointsConverged = inputData.getInt("POINTS_CONVERGED", 0),
                avgIterations = inputData.getFloat("AVG_ITERS", 0f),
                executionTimeMs = inputData.getInt("EXEC_TIME", 0)
            )

            val insertedRow = SupabaseManager.client.postgrest["analysis_sessions"]
                .insert(sessionData) { select() }
                .decodeSingle<AnalysisSessionResponse>()

            val trueSessionId = insertedRow.sessionId
            val cloudFolder = "$userEmail/Session_$trueSessionId"
            Log.d("inDIC_Diag", "   ✅ Row Created! Target Folder: $cloudFolder")

            val storageBucket = SupabaseManager.client.storage["session_artifacts"]

            // 🚀 STEP 2: UPLOAD REFERENCE
            Log.d("inDIC_Diag", "-> Step 2: Uploading Reference Image...")
            val refFile = File(refPath)
            if (refFile.exists()) {
                storageBucket.upload("$cloudFolder/Reference.png", refFile.readBytes()) { upsert = true }
                Log.d("inDIC_Diag", "   ✅ Reference Uploaded.")
            }

            // 🚀 STEP 3: UPLOAD DEFORMED
            Log.d("inDIC_Diag", "-> Step 3: Uploading Deformed Image...")
            val defFile = File(defPath)
            if (defFile.exists()) {
                storageBucket.upload("$cloudFolder/Deformed_$frameName.png", defFile.readBytes()) { upsert = true }
                Log.d("inDIC_Diag", "   ✅ Deformed Uploaded.")
            }

            // 🚀 STEP 4: GENERATE & UPLOAD CSV
            Log.d("inDIC_Diag", "-> Step 4: Generating CSV...")
            val datFile = File(datPath)
            var publicCsvUrl = ""
            var rawFloatData: FloatArray? = null

            if (datFile.exists()) {
                val bytes = datFile.readBytes()
                val data = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(data)
                rawFloatData = data

                val csvContent = StringBuilder("X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n")
                var i = 0
                while (i < data.size) {
                    val c = data[i + 7]
                    if (c != 0f) csvContent.append("${data[i]},${data[i + 1]},${data[i + 2]},${data[i + 3]},${data[i + 4]},${data[i + 5]},${data[i + 6]},$c\n")
                    i += 8
                }
                val csvCloudPath = "$cloudFolder/Data_$frameName.csv"
                storageBucket.upload(csvCloudPath, csvContent.toString().toByteArray()) { upsert = true }
                publicCsvUrl = storageBucket.publicUrl(csvCloudPath)
                Log.d("inDIC_Diag", "   ✅ CSV Uploaded.")
            }

            // 🚀 STEP 5: HEADLESS PDF GENERATOR
            Log.d("inDIC_Diag", "-> Step 5: Generating PDF in Background...")
            var publicPdfUrl = ""
            if (rawFloatData != null && refFile.exists() && defFile.exists()) {
                // IMPORTANT: We pass the TRUE Session ID to the PDF generator so it prints the real ID on the report!
                publicPdfUrl = generateHeadlessPdfAndUpload(rawFloatData, refFile, defFile, cloudFolder, storageBucket, frameName, trueSessionId)
                Log.d("inDIC_Diag", "   ✅ PDF Uploaded.")
            }

            // 🚀 STEP 6: UPDATE DATABASE LEDGER
            Log.d("inDIC_Diag", "-> Step 6: Updating Database Ledger...")
            if (publicCsvUrl.isNotEmpty() || publicPdfUrl.isNotEmpty()) {
                val updateMap = mutableMapOf<String, String>()
                if (publicCsvUrl.isNotEmpty()) updateMap["summary_csv_path"] = publicCsvUrl
                if (publicPdfUrl.isNotEmpty()) updateMap["heatmap_png_path"] = publicPdfUrl

                SupabaseManager.client.postgrest["analysis_sessions"].update(updateMap) {
                    filter { eq("session_id", trueSessionId) }
                }
                Log.d("inDIC_Diag", "   ✅ Ledger Updated.")
            }

            Log.d("inDIC_Diag", "👻 GHOST WORKER FINISHED SUCCESSFULLY!")
            Log.d("inDIC_Diag", "========================================")
            return@withContext Result.success()

        } catch (e: Exception) {
            Log.e("inDIC_Diag", "========================================")
            Log.e("inDIC_Diag", "❌ CRITICAL ERROR IN GHOST WORKER")
            Log.e("inDIC_Diag", "Error Message: ${e.message}")
            Log.e("inDIC_Diag", "========================================")
            return@withContext Result.retry() // OS will automatically backoff and retry later!
        }
    }

    // =========================================================================
    // HEADLESS PDF GENERATOR ENGINE
    // =========================================================================
    private suspend fun generateHeadlessPdfAndUpload(
        data: FloatArray, refFile: File, defFile: File, cloudFolder: String,
        storageBucket: io.github.jan.supabase.storage.BucketApi, frameName: String, trueSessionId: String
    ): String = withContext(Dispatchers.Default) {

        val imgW = inputData.getInt("IMG_W", 1000)
        val imgH = inputData.getInt("IMG_H", 1000)
        val step = inputData.getInt("STEP", 5)

        // 🚀 THE FIX: Load at FULL RESOLUTION to match the Heatmap Matrix perfectly!
        val originalBaseImg = BitmapFactory.decodeFile(refFile.absolutePath) ?: return@withContext ""
        val originalDefImg = BitmapFactory.decodeFile(defFile.absolutePath) ?: originalBaseImg

        // Force them to exactly match the Engine's dimensions
        val baseImg = Bitmap.createScaledBitmap(originalBaseImg, imgW, imgH, true)
        val defImg = Bitmap.createScaledBitmap(originalDefImg, imgW, imgH, true)

        val fieldNames = listOf("U Displacement", "V Displacement", "Exx Strain", "Eyy Strain", "Exy Shear", "ZNSSD (Correlation Quality)")
        val fieldKeys = listOf("U", "V", "Exx", "Eyy", "Exy", "ZNSSD")
        val fieldResults = mutableListOf<FieldResult>()
        var correlationHeatmap: Bitmap? = null

        for (fieldIndex in 0..5) {
            val dataIndex = fieldIndex + 2
            val isStrain = dataIndex in 4..6
            val isCorrelation = dataIndex == 7

            val multiplier = if (isStrain) 1000f else 1f
            val unit = if (isStrain) "mε" else if (isCorrelation) "" else "px"
            val meanTypeString = if (isStrain) "Mean Absolute" else "Simple Mean"

            var maxV = -Float.MAX_VALUE; var minV = Float.MAX_VALUE
            var maxIdx = -1; var minIdx = -1
            val validValues = mutableListOf<Float>()

            for (i in data.indices step 8) {
                val corr = data[i + 7]
                if (isCorrelation || (corr != 0f && corr <= 0.15f)) {
                    val rawVal = data[i + dataIndex]
                    validValues.add(if (isStrain) kotlin.math.abs(rawVal) else rawVal)
                }
            }
            if (validValues.isEmpty()) continue

            validValues.sort()
            val p02 = validValues[(validValues.size * 0.02).toInt().coerceIn(0, validValues.size - 1)]
            val p98 = validValues[(validValues.size * 0.98).toInt().coerceIn(0, validValues.size - 1)]

            for (i in data.indices step 8) {
                val corr = data[i + 7]
                if (isCorrelation || (corr != 0f && corr <= 0.15f)) {
                    val valToCheck = if (isStrain) kotlin.math.abs(data[i + dataIndex]) else data[i + dataIndex]
                    if (valToCheck in p02..p98) {
                        if (valToCheck > maxV) { maxV = valToCheck; maxIdx = i }
                        if (valToCheck < minV) { minV = valToCheck; minIdx = i }
                    }
                }
            }
            if (maxIdx == -1 || minIdx == -1) {
                for (i in data.indices step 8) {
                    val corr = data[i + 7]
                    if (isCorrelation || (corr != 0f && corr <= 0.15f)) {
                        val valToCheck = if (isStrain) kotlin.math.abs(data[i + dataIndex]) else data[i + dataIndex]
                        if (valToCheck > maxV) { maxV = valToCheck; maxIdx = i }
                        if (valToCheck < minV) { minV = valToCheck; minIdx = i }
                    }
                }
            }

            val mean = validValues.average().toFloat()
            val stdDev = kotlin.math.sqrt(validValues.map { (it - mean) * (it - mean) }.average()).toFloat()

            val (heatmapBmp, actualMin, actualMax) = VisualizationEngine.generateHeatmap(data, imgW, imgH, dataIndex, step, null, null)

            // 🚀 Bake them at FULL resolution, then compress for the PDF
            val bakedHeatmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888).also { bmp ->
                val tempCanvas = Canvas(bmp)
                tempCanvas.drawBitmap(baseImg, 0f, 0f, null)
                tempCanvas.drawBitmap(heatmapBmp, 0f, 0f, Paint().apply { alpha = 180 })
                bakeAnnotationsToCanvas(tempCanvas, imgW, imgH, actualMin, actualMax, fieldKeys[fieldIndex], unit, maxIdx, minIdx, data)
            }.compressForPdf()

            heatmapBmp.recycle()

            if (isCorrelation) {
                correlationHeatmap = bakedHeatmap
            } else {
                fieldResults.add(FieldResult(
                    fieldName = fieldNames[fieldIndex], fieldKey = fieldKeys[fieldIndex], unit = unit,
                    minValue = actualMin * multiplier, maxValue = actualMax * multiplier,
                    meanValue = mean * multiplier, stdDevValue = stdDev * multiplier,
                    meanType = meanTypeString,
                    minCoordX = data[minIdx].toInt(), minCoordY = data[minIdx + 1].toInt(),
                    maxCoordX = data[maxIdx].toInt(), maxCoordY = data[maxIdx + 1].toInt(),
                    bakedHeatmap = bakedHeatmap
                ))
            }
        }

        var totalZnssd = 0.0f; var validPointCount = 0
        for (i in data.indices step 8) {
            val corr = data[i + 7]
            if (corr != 0f && corr <= 0.15f) { totalZnssd += corr; validPointCount++ }
        }

        val statsArray = inputData.getFloatArray("ENGINE_STATS") ?: FloatArray(16)
        val reportData = ReportData(
            sessionId = trueSessionId,
            specimenName = inputData.getString("REF_NAME") ?: "Target",
            analysisDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
            subsetSize = inputData.getInt("SUBSET", 41),
            stepSize = step,
            strainWindow = inputData.getInt("STRAIN_WIN", 15),
            strainMethod = inputData.getString("STRAIN_METHOD") ?: "VSG",
            roiData = RoiData(
                inputData.getInt("ROI_X", 0), inputData.getInt("ROI_Y", 0),
                inputData.getInt("ROI_W", imgW), inputData.getInt("ROI_H", imgH)
            ),
            // 🚀 Compress the cover images strictly for Page 1
            referenceImage = baseImg.compressForPdf(),
            deformedImage = defImg.compressForPdf(),
            referenceImageName = "Baseline",
            deformedImageName = frameName,
            fieldResults = fieldResults,
            engineStats = EngineStats.fromArray(statsArray),
            znssdHeatmap = correlationHeatmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            solverPathMap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            globalAvgZnssd = if (validPointCount > 0) totalZnssd / validPointCount else 0.0f
        )

        val tempPdfFile = File(applicationContext.cacheDir, "headless_master_report.pdf")
        tempPdfFile.outputStream().use { stream ->
            PdfReportGenerator.generate(reportData, stream).collect { }
        }

        val pdfCloudPath = "$cloudFolder/Master_Report_$frameName.pdf"
        storageBucket.upload(pdfCloudPath, tempPdfFile.readBytes()) { upsert = true }

        fieldResults.forEach { it.bakedHeatmap.recycle() }
        correlationHeatmap?.recycle(); baseImg.recycle(); defImg.recycle(); originalBaseImg.recycle(); originalDefImg.recycle()
        tempPdfFile.delete()

        return@withContext storageBucket.publicUrl(pdfCloudPath)
    }

    private fun formatMetric(value: Float): String {
        val absVal = kotlin.math.abs(value)
        return if (absVal > 0f && (absVal < 0.001f || absVal >= 10000f)) String.format("%.2e", value) else String.format("%.5f", value)
    }

    private fun Bitmap.compressForPdf(maxWidth: Int = 600): Bitmap {
        val ratio = maxWidth.toFloat() / this.width
        val newWidth = if (this.width > maxWidth) maxWidth else this.width
        val newHeight = (this.height * ratio).toInt()
        val scaled = Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
        val strippedBmp = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(strippedBmp)
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled != this) scaled.recycle()
        return strippedBmp
    }

    private fun bakeAnnotationsToCanvas(canvas: Canvas, width: Int, height: Int, minValRaw: Float, maxValRaw: Float,
                                        typeString: String, unit: String, maxIdx: Int, minIdx: Int, dataArray: FloatArray) {
        val multiplier = if (unit == "mε") 1000f else 1f
        val maxVal = maxValRaw * multiplier
        val minVal = minValRaw * multiplier

        val textSize = width * 0.025f; val padding = width * 0.02f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(4f, 2f, 2f, Color.BLACK) }
        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

        val infoText = arrayOf("inDIC Analysis Report", "Field: $typeString [$unit]", "Max: ${formatMetric(maxVal)}", "Min: ${formatMetric(minVal)}")
        var maxTextWidth = 0f
        for (line in infoText) { val w = textPaint.measureText(line); if (w > maxTextWidth) maxTextWidth = w }

        canvas.drawRect(padding * 0.5f, padding * 0.5f, padding * 1.5f + maxTextWidth, padding + (infoText.size * (textSize * 1.4f)) + padding, bgPaint)
        var currentY = padding + textSize
        for (line in infoText) { canvas.drawText(line, padding, currentY, textPaint); currentY += textSize * 1.4f }

        val barWidth = width * 0.03f; val barHeight = height * 0.5f
        val barLeft = width - padding - barWidth - (textSize * 4.5f); val barTop = (height - barHeight) / 2f
        val barRight = barLeft + barWidth; val barBottom = barTop + barHeight

        val jetColors = intArrayOf(Color.rgb(127, 0, 0), Color.rgb(255, 0, 0), Color.rgb(255, 255, 0), Color.rgb(0, 255, 255), Color.rgb(0, 0, 255), Color.rgb(0, 0, 127))
        canvas.drawRect(barLeft, barTop, barRight, barBottom, Paint().apply { shader = LinearGradient(0f, barTop, 0f, barBottom, jetColors, null, Shader.TileMode.CLAMP) })
        canvas.drawRect(barLeft, barTop, barRight, barBottom, Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f })

        val scaleTextPaint = Paint(textPaint).apply { textAlign = Paint.Align.LEFT; clearShadowLayer(); color = Color.BLACK }
        val whiteBgPaint = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        fun drawScaleLabel(text: String, y: Float) {
            val w = scaleTextPaint.measureText(text)
            canvas.drawRect(barRight + padding * 0.5f - 5f, y - textSize, barRight + padding * 0.5f + w + 5f, y + (textSize * 0.3f), whiteBgPaint)
            canvas.drawText(text, barRight + padding * 0.5f, y, scaleTextPaint)
        }

        drawScaleLabel(formatMetric(maxVal), barTop + (textSize * 0.3f))
        drawScaleLabel(formatMetric((maxVal + minVal) / 2f), barTop + (barHeight / 2f) + (textSize * 0.3f))
        drawScaleLabel(formatMetric(minVal), barBottom)

        if (maxIdx != -1 && minIdx != -1) {
            val maxX = dataArray[maxIdx]; val maxY = dataArray[maxIdx + 1]
            val targetRadius = width * 0.015f; val crosshairLen = targetRadius * 1.5f
            val whiteOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f }
            val markerTextPaint = Paint(textPaint).apply { this.textSize = width * 0.018f }

            canvas.drawCircle(maxX, maxY, targetRadius, whiteOutline)
            canvas.drawLine(maxX - crosshairLen, maxY, maxX + crosshairLen, maxY, whiteOutline)
            canvas.drawLine(maxX, maxY - crosshairLen, maxX, maxY + crosshairLen, whiteOutline)
            val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f }
            canvas.drawCircle(maxX, maxY, targetRadius, corePaint)
            canvas.drawText("MAX", maxX + targetRadius + 5f, maxY - targetRadius - 5f, markerTextPaint)
        }
    }
}