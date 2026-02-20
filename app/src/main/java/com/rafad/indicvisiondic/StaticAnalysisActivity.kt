package com.rafad.indicvisiondic

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import android.content.ContentValues
import android.provider.MediaStore
import java.io.OutputStream

class StaticAnalysisActivity : AppCompatActivity() {

    // UI Components
    private lateinit var imgRef: ImageView
    private lateinit var imgDef: ImageView
    private lateinit var overlayRef: OverlayView
    private lateinit var btnLoadRef: Button
    private lateinit var btnLoadDef: Button
    private lateinit var btnFullImage: Button
    private lateinit var btnCalculate: Button
    private lateinit var btnCalculateDisp: Button
    private lateinit var btnCalculateStrain: Button
    private lateinit var tvResult: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var tvRefName: TextView
    private lateinit var tvDefName: TextView
    private lateinit var etSubsetSize: EditText
    private lateinit var etStepSize: EditText
    private lateinit var etStrainWindow: EditText // Added missing declaration
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTimer: TextView
    private lateinit var btnCalculateFullField: Button // New
    // Data Storage
    private var refBytes: ByteArray? = null
    private var defBytes: ByteArray? = null
    private var lastDisplacementData: FloatArray? = null

    // Image Dimensions and ROI
    private var realRefWidth = 0
    private var realRefHeight = 0
    private var roiCenterX = 0
    private var roiCenterY = 0
    private var isRoiSelected = false
    private var startX = 0f
    private var startY = 0f
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_static_analysis)

        // 1. Bind UI Components
        progressBar = findViewById(R.id.pbAnalysis)
        tvTimer = findViewById(R.id.tvTimer)
        imgRef = findViewById(R.id.imgRef)
        imgDef = findViewById(R.id.imgDef)
        overlayRef = findViewById(R.id.overlayRef)
        btnLoadRef = findViewById(R.id.btnLoadRef)
        btnLoadDef = findViewById(R.id.btnLoadDef)
        btnFullImage = findViewById(R.id.btnFullImage)
        btnCalculate = findViewById(R.id.btnCalculate)
        btnCalculateDisp = findViewById(R.id.btnCalculateDisp)
        btnCalculateStrain = findViewById(R.id.btnCalculateStrain)
        tvResult = findViewById(R.id.tvStaticResult)
        tvInstruction = findViewById(R.id.tvInstruction)
        tvRefName = findViewById(R.id.tvRefName)
        tvDefName = findViewById(R.id.tvDefName)
        etSubsetSize = findViewById(R.id.etSubsetSize)
        etStepSize = findViewById(R.id.etStepSize)
        etStrainWindow = findViewById(R.id.etStrainWindow) // Added missing binding
        btnCalculateDisp = findViewById(R.id.btnCalculateDisp)
        btnCalculateFullField = findViewById(R.id.btnCalculateFullField) // New
        // 2. Image Pickers
        val pickRef = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImageSelection(it, isRef = true) }
        }
        val pickDef = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImageSelection(it, isRef = false) }
        }
        btnLoadRef.setOnClickListener { pickRef.launch("image/*") }
        btnLoadDef.setOnClickListener { pickDef.launch("image/*") }

        // 3. Logic: Full Image Center
        btnFullImage.setOnClickListener {
            if (realRefWidth > 0) {
                roiCenterX = realRefWidth / 2
                roiCenterY = realRefHeight / 2
                isRoiSelected = true
                val drawable = imgRef.drawable
                if (drawable != null) {
                    val screenPoint = mapImageToScreen(roiCenterX.toFloat(), roiCenterY.toFloat())
                    overlayRef.drawPoint(screenPoint[0], screenPoint[1])
                }
                tvInstruction.text = "✅ Center ROI Locked: ($roiCenterX, $roiCenterY)"
                checkReady()
            }
        }

        // 4. Logic: Draw Box/ROI
        overlayRef.setOnTouchListener { v, event ->
            if (refBytes == null) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x; startY = event.y
                    overlayRef.clear(); isRoiSelected = false; checkReady()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val rect = RectF(Math.min(startX, event.x), Math.min(startY, event.y), Math.max(startX, event.x), Math.max(startY, event.y))
                    overlayRef.drawRect(rect); true
                }
                MotionEvent.ACTION_UP -> {
                    val midX = (startX + event.x) / 2; val midY = (startY + event.y) / 2
                    overlayRef.drawPoint(midX, midY)
                    val imgPoint = mapScreenToImage(midX, midY)
                    if (imgPoint != null) {
                        roiCenterX = imgPoint[0].toInt(); roiCenterY = imgPoint[1].toInt()
                        isRoiSelected = true
                        tvInstruction.text = "✅ ROI Set at ($roiCenterX, $roiCenterY)"
                        checkReady()
                    }
                    true
                }
                else -> false
            }
        }

        // 5. Logic: Single Point Test
        btnCalculate.setOnClickListener {
            if (refBytes != null && defBytes != null && isRoiSelected) {
                val subset = etSubsetSize.text.toString().toIntOrNull() ?: 61
                Thread {
                    val res = IndicVisionNativeLib.analyzeRawBytes(refBytes!!, defBytes!!, roiCenterX, roiCenterY, subset, realRefWidth, realRefHeight)
                    runOnUiThread {
                        if (res[4].toInt() == 0) tvResult.text = "SUCCESS\nU: %.4f px\nError: %.4f".format(res[0], res[3])
                        else tvResult.text = "Analysis Failed"
                    }
                }.start()
            }
        }

        // 6. Logic: Displacement Profile Scan
        btnCalculateDisp.setOnClickListener {// ... inside btnCalculateDisp.setOnClickListener ...
            if (refBytes != null && defBytes != null) {
                val subset = etSubsetSize.text.toString().toIntOrNull() ?: 41
                val step = etStepSize.text.toString().toIntOrNull() ?: 5

                // 1. DETERMINE Y-COORDINATE
                // If you touched the screen, use that Y. Otherwise, default to center.
                val scanY = if (isRoiSelected) roiCenterY else realRefHeight / 2

                // 2. VISUAL DEBUGGING (CRITICAL)
                // Draw a blue line on the screen where we are about to solve.
                // If this line hits black background, we know why it fails.
                val screenCoords = mapImageToScreen(0f, scanY.toFloat())
                overlayRef.drawScanLine(screenCoords[1])

                // 3. LOGGING
                Log.d("DIC_DIAG", "Real Image Size: $realRefWidth x $realRefHeight")
                Log.d("DIC_DIAG", "User Selected ROI? $isRoiSelected")
                Log.d("DIC_DIAG", "Sending Scan Line Y = $scanY to Engine")
                // ENABLE THE NEW DICe FEATURES
                val useReliabilityGuided = true
                val useFeatureMatching = true
                // --------------------

                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                tvTimer.visibility = View.VISIBLE
                btnCalculateDisp.isEnabled = false
                val startTime = System.currentTimeMillis()

                Thread {
                    val callback = object : ProgressCallback {
                        override fun onProgressUpdate(percentage: Int) {
                            runOnUiThread {
                                progressBar.progress = percentage
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                tvTimer.text = "Elapsed Time: ${elapsed}s ($percentage%)"
                            }
                        }
                    }

                    // CALL THE UPDATED NATIVE FUNCTION
                    val uValues = IndicVisionNativeLib.computeLineProfile(
                        refBytes!!, defBytes!!,
                        50, realRefWidth - 50, scanY,
                        step, subset,
                        useReliabilityGuided,
                        useFeatureMatching,
                        callback
                    )

                    lastDisplacementData = uValues
                    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        tvTimer.text = "Analysis done in %.2f seconds".format(totalTime)
                        btnCalculateDisp.isEnabled = true
                        btnCalculateStrain.visibility = View.VISIBLE
                        saveResultsToCSV(uValues, null, step, 50)
                    }
                }.start()
            }
        }

        // 7. Logic: Separate Strain Calculation (Windowing)
        btnCalculateStrain.setOnClickListener {
            val data = lastDisplacementData ?: return@setOnClickListener
            val step = etStepSize.text.toString().toIntOrNull() ?: 5
            var winSize = etStrainWindow.text.toString().toIntOrNull() ?: 15 // Default 15
            if (winSize % 2 == 0) winSize += 1

            // --- TOGGLE HERE: Change to false for Linear, true for Quadratic ---
            val USE_QUADRATIC = true
            // ------------------------------------------------------------------

            val method = if (USE_QUADRATIC) "Quadratic (Curve Fit)" else "Linear (Line Fit)"
            tvResult.text = "Applying $method VSG (Window $winSize)..."

            Thread {
                val strains = if (USE_QUADRATIC) {
                    calculateVSGStrainQuadratic(data, step, winSize)
                } else {
                    calculateVSGStrainLinear(data, step, winSize)
                }

                runOnUiThread {
                    saveResultsToCSV(data, strains, step, 50)
                    tvResult.text = "✅ Strain Saved ($method, Win $winSize)"
                }
            }.start()
        }
        // --- NEW: FULL FIELD 2D LISTENER ---
        btnCalculateFullField.setOnClickListener {
            if (refBytes != null && defBytes != null) {
                val subset = etSubsetSize.text.toString().toIntOrNull() ?: 41
                val step = etStepSize.text.toString().toIntOrNull() ?: 5 // Use larger step (e.g. 10) for speed if needed

                // Get Settings
                val useReliabilityGuided = true
                val useFeatureMatching = true
                val strainWin = etStrainWindow.text.toString().toIntOrNull() ?: 15

                // Define ROI: Whole Image with 20px margin
                val margin = (subset / 2) + 10
                val rectX = margin
                val rectY = margin
                val rectW = realRefWidth - (2 * margin)
                val rectH = realRefHeight - (2 * margin)

                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                tvTimer.visibility = View.VISIBLE
                tvTimer.text = "Initializing 2D Scan..."

                // Disable buttons
                btnCalculateDisp.isEnabled = false
                btnCalculateFullField.isEnabled = false

                val startTime = System.currentTimeMillis()

                Thread {
                    val callback = object : ProgressCallback {
                        override fun onProgressUpdate(percentage: Int) {
                            runOnUiThread {
                                progressBar.progress = percentage
                                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                                tvTimer.text = "2D Scanning... ${elapsed}s ($percentage%)"
                            }
                        }
                    }

                    // CALL THE 2D NATIVE FUNCTION
                    val rawData = IndicVisionNativeLib.computeFullField(
                        refBytes!!, defBytes!!,
                        rectX, rectY, rectW, rectH,
                        step, subset,strainWin,
                        useReliabilityGuided,
                        useFeatureMatching,
                        callback
                    )

                    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0

                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        tvTimer.text = "Full Field Done in %.2f s. Points: ${rawData.size / 5}".format(totalTime)

                        // Re-enable buttons
                        btnCalculateDisp.isEnabled = true
                        btnCalculateFullField.isEnabled = true

                        // Save to CSV immediately
                        saveFullFieldToCSV(rawData)
                    }
                }.start()
            }
        }
    }

    /**
     * METHOD A: QUADRATIC VSG (The "Sine Wave" Expert)
     * Fits u(x) = ax^2 + bx + c. Returns 'b'.
     * Best for curved signals (like Sample 14) but needs a moderate window (13-15) to avoid over-fitting noise.
     */
    private fun calculateVSGStrainQuadratic(uValues: FloatArray, step: Int, windowSize: Int): FloatArray {
        val strains = FloatArray(uValues.size)
        val half = windowSize / 2

        for (i in 0 until uValues.size) {
            if (i < half || i >= uValues.size - half) { strains[i] = 0f; continue }

            var n = 0.0; var sumX = 0.0; var sumX2 = 0.0; var sumX3 = 0.0; var sumX4 = 0.0
            var sumU = 0.0; var sumXU = 0.0; var sumX2U = 0.0
            var validPoints = 0

            for (j in -half..half) {
                val u = uValues[i + j].toDouble()
                if (u > -900) {
                    val x = (j * step).toDouble()
                    val x2 = x * x
                    n += 1.0; sumX += x; sumX2 += x2; sumX3 += x2 * x; sumX4 += x2 * x2
                    sumU += u; sumXU += x * u; sumX2U += x2 * u
                    validPoints++
                }
            }

            if (validPoints >= 5) {
                val det = n * (sumX2 * sumX4 - sumX3 * sumX3) -
                        sumX * (sumX * sumX4 - sumX3 * sumX2) +
                        sumX2 * (sumX * sumX3 - sumX2 * sumX2)

                if (Math.abs(det) > 1e-9) {
                    // Solves for 'b' using Cramer's Rule (Fixed Typo Version)
                    val detB = n * (sumXU * sumX4 - sumX2U * sumX3) -
                            sumU * (sumX * sumX4 - sumX3 * sumX2) +
                            sumX2 * (sumX * sumX2U - sumX2 * sumXU)
                    strains[i] = (detB / det).toFloat()
                }
            }
        }
        return strains
    }

    /**
     * METHOD B: LINEAR VSG (The "Noise Killer")
     * Fits u(x) = bx + c. Returns 'b'.
     * Best for flat specimens or when noise is very high. Ignores curvature.
     */
    private fun calculateVSGStrainLinear(uValues: FloatArray, step: Int, windowSize: Int): FloatArray {
        val strains = FloatArray(uValues.size)
        val half = windowSize / 2

        for (i in 0 until uValues.size) {
            if (i < half || i >= uValues.size - half) { strains[i] = 0f; continue }

            var sumX2 = 0.0
            var sumXU = 0.0
            var validPoints = 0

            for (j in -half..half) {
                val u = uValues[i + j].toDouble()
                if (u > -900) {
                    val x = (j * step).toDouble()
                    // For linear fit centered at 0, slope = Sum(x*u) / Sum(x^2)
                    sumX2 += x * x
                    sumXU += x * u
                    validPoints++
                }
            }

            if (validPoints > half && Math.abs(sumX2) > 1e-9) {
                strains[i] = (sumXU / sumX2).toFloat()
            }
        }
        return strains
    }

    private fun saveResultsToCSV(uValues: FloatArray, strains: FloatArray?, step: Int, startX: Int) {
        val folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val type = if (strains == null) "Disp" else "Full"
        val fileName = "IndicVision_${type}_${System.currentTimeMillis()}.csv"
        val file = File(folder, fileName)
        try {
            val writer = FileWriter(file)
            writer.append("X_Pixel,U_Displacement,Strain_Exx\n")
            var currentX = startX
            for (i in uValues.indices) {
                val sVal = if (strains != null) strains[i] else 0.0f
                writer.append("$currentX,${uValues[i]},$sVal\n")
                currentX += step
            }
            writer.flush(); writer.close()
            runOnUiThread { tvResult.text = "✅ SAVED: $fileName" }
        } catch (e: Exception) { runOnUiThread { tvResult.text = "❌ CSV Error" } }
    }
    // In StaticAnalysisActivity.kt



    private fun saveFullFieldToCSV(data: FloatArray) {
        val fileName = "IndicVision_2D_${System.currentTimeMillis()}.csv"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }

        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            try {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    val writer = outputStream.bufferedWriter()

                    // UPDATED HEADER for Strains
                    writer.write("X,Y,U_Displacement,V_Displacement,Exx,Eyy,Exy,Correlation\n")

                    // LOOP THROUGH 8 ELEMENTS PER POINT
                    var i = 0
                    while (i < data.size) {
                        val x = data[i]
                        val y = data[i+1]
                        val u = data[i+2]
                        val v = data[i+3]
                        val exx = data[i+4]
                        val eyy = data[i+5]
                        val exy = data[i+6]
                        val c = data[i+7]

                        writer.write("$x,$y,$u,$v,$exx,$eyy,$exy,$c\n")
                        i += 8
                    }
                    writer.flush()
                    writer.close()
                }
                runOnUiThread {
                    tvResult.text = "✅ Saved to Downloads: $fileName"
                    Toast.makeText(this, "File saved successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { tvResult.text = "❌ Save Failed: ${e.message}" }
            }
        }
    }
    // Coordinate Mapping Helpers
    private fun mapScreenToImage(touchX: Float, touchY: Float): FloatArray? {
        val drawable = imgRef.drawable ?: return null
        val inv = Matrix(); imgRef.imageMatrix.invert(inv)
        val pts = floatArrayOf(touchX, touchY)
        inv.mapPoints(pts)
        val scale = realRefWidth.toFloat() / drawable.intrinsicWidth.toFloat()
        return floatArrayOf(pts[0] * scale, pts[1] * scale)
    }

    private fun mapImageToScreen(imgX: Float, imgY: Float): FloatArray {
        val drawable = imgRef.drawable ?: return floatArrayOf(0f, 0f)
        val scale = drawable.intrinsicWidth.toFloat() / realRefWidth.toFloat()
        val pts = floatArrayOf(imgX * scale, imgY * scale)
        imgRef.imageMatrix.mapPoints(pts)
        return pts
    }

    private fun handleImageSelection(uri: Uri, isRef: Boolean) {
        val name = getFileName(uri)
        if (isRef) tvRefName.text = "Selected: $name" else tvDefName.text = "Selected: $name"
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (isRef) {
                    val dims = IndicVisionNativeLib.getImageDimensions(bytes)
                    realRefWidth = dims[0]; realRefHeight = dims[1]
                    refBytes = bytes
                    imgRef.setImageBitmap(IndicVisionNativeLib.getPreviewFromBytes(bytes, 1000))
                } else {
                    defBytes = bytes
                    imgDef.setImageBitmap(IndicVisionNativeLib.getPreviewFromBytes(bytes, 1000))
                }
                checkReady()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
            }
        }
        if (result == null || result!!.matches(Regex("\\d+.*"))) {
            uri.path?.let { path ->
                val cut = path.lastIndexOf('/')
                if (cut != -1) result = path.substring(cut + 1)
            }
        }
        return result ?: "Unknown_File"
    }

    private fun checkReady() {
        val ready = (refBytes != null && defBytes != null)
        btnCalculate.isEnabled = (ready && isRoiSelected)
        btnCalculateDisp.isEnabled = ready
        btnCalculateFullField.isEnabled = ready // New
        if (ready) btnCalculateDisp.setBackgroundColor(android.graphics.Color.parseColor("#0000AA"))
    }
}