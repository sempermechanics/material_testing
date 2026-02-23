package com.rafad.indicvisiondic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class StaticAnalysisActivity : AppCompatActivity() {

    // UI Components
    private lateinit var imgRef: ImageView
    private lateinit var imgDef: ImageView
    private lateinit var btnLoadRef: Button
    private lateinit var btnLoadDef: Button
    private lateinit var btnFullImage: Button
    private lateinit var btnDefineRoi: Button
    private lateinit var btnLoadRoiMask: Button
    private lateinit var tvResult: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var tvRefName: TextView
    private lateinit var tvDefName: TextView
    private lateinit var etSubsetSize: EditText
    private lateinit var etStepSize: EditText
    private lateinit var etStrainWindow: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTimer: TextView
    private lateinit var btnCalculateFullField: Button
    private lateinit var switchBlur: Switch
    private lateinit var rgStrainMethod: RadioGroup

    // --- NEW: View Last Result Button ---
    private lateinit var btnViewResults: Button

    // Data Storage
    private var refBytes: ByteArray? = null
    private var defBytes: ByteArray? = null
    private var roiMaskBytes: ByteArray? = null
    private var refImageUriString: String? = null

    // --- NEW: State Storage for Last Results ---
    private var lastDataPath: String? = null
    private var lastDefPath: String? = null
    private var lastGridW: Int = 0
    private var lastGridH: Int = 0
    private var lastStep: Int = 5

    // Image Dimensions & ROI Bounds
    private var realRefWidth = 0
    private var realRefHeight = 0
    private var roiX = 0
    private var roiY = 0
    private var roiW = 0
    private var roiH = 0
    private var hasCustomRoi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_static_analysis)

        // 1. Bind UI Components
        progressBar = findViewById(R.id.pbAnalysis)
        tvTimer = findViewById(R.id.tvTimer)
        imgRef = findViewById(R.id.imgRef)
        imgDef = findViewById(R.id.imgDef)
        btnLoadRef = findViewById(R.id.btnLoadRef)
        btnLoadDef = findViewById(R.id.btnLoadDef)
        btnFullImage = findViewById(R.id.btnFullImage)
        btnDefineRoi = findViewById(R.id.btnDefineRoi)
        btnLoadRoiMask = findViewById(R.id.btnLoadRoiMask)
        tvResult = findViewById(R.id.tvStaticResult)
        tvInstruction = findViewById(R.id.tvInstruction)
        tvRefName = findViewById(R.id.tvRefName)
        tvDefName = findViewById(R.id.tvDefName)
        etSubsetSize = findViewById(R.id.etSubsetSize)
        etStepSize = findViewById(R.id.etStepSize)
        etStrainWindow = findViewById(R.id.etStrainWindow)
        btnCalculateFullField = findViewById(R.id.btnCalculateFullField)
        switchBlur = findViewById(R.id.switchBlur)
        rgStrainMethod = findViewById(R.id.rgStrainMethod)

        // Bind the new button
        btnViewResults = findViewById(R.id.btnViewResults)
        // 2. Launchers for Images and Mask
        val pickRef = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImageSelection(it, isRef = true) }
        }
        val pickDef = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImageSelection(it, isRef = false) }
        }
        val pickMask = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleMaskSelection(it) }
        }

        // The ROI Studio Launcher
        val roiStudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    roiX = data.getIntExtra("ROI_X", 0)
                    roiY = data.getIntExtra("ROI_Y", 0)
                    roiW = data.getIntExtra("ROI_W", realRefWidth)
                    roiH = data.getIntExtra("ROI_H", realRefHeight)
                    hasCustomRoi = true
                    tvInstruction.text = "✅ ROI Set: $roiW x $roiH px"
                    checkReady()
                }
            } else {
                tvInstruction.text = "❌ ROI Selection Cancelled"
            }
        }

        // Button Listeners
        btnLoadRef.setOnClickListener { pickRef.launch("image/*") }
        btnLoadDef.setOnClickListener { pickDef.launch("image/*") }
        btnLoadRoiMask.setOnClickListener { pickMask.launch("image/*") }

        btnDefineRoi.setOnClickListener {
            if (refBytes != null) {
                val tempFile = File(cacheDir, "temp_roi_ref.bin")
                tempFile.writeBytes(refBytes!!)

                val intent = Intent(this, RoiDrawActivity::class.java)
                intent.putExtra("IMAGE_FILE_PATH", tempFile.absolutePath)
                intent.putExtra("IMAGE_WIDTH", realRefWidth)
                intent.putExtra("IMAGE_HEIGHT", realRefHeight)

                roiStudioLauncher.launch(intent)
            }
        }

        btnFullImage.setOnClickListener {
            if (realRefWidth > 0) {
                hasCustomRoi = false
                tvInstruction.text = "✅ Using Full Image"
                checkReady()
            }
        }

        // 3. Logic: FULL FIELD 2D
        btnCalculateFullField.setOnClickListener {
            if (refBytes != null && defBytes != null) {
                val subset = etSubsetSize.text.toString().toIntOrNull() ?: 41
                val step = etStepSize.text.toString().toIntOrNull() ?: 5
                val strainWin = etStrainWindow.text.toString().toIntOrNull() ?: 15

                // Decide boundaries
                var finalRectX: Int
                var finalRectY: Int
                var finalRectW: Int
                var finalRectH: Int

                if (hasCustomRoi) {
                    finalRectX = roiX
                    finalRectY = roiY
                    finalRectW = roiW
                    finalRectH = roiH
                } else {
                    val margin = (subset / 2) + 10
                    finalRectX = margin
                    finalRectY = margin
                    finalRectW = realRefWidth - (2 * margin)
                    finalRectH = realRefHeight - (2 * margin)
                }

                if (finalRectW < subset || finalRectH < subset) {
                    Toast.makeText(this, "ROI is too small! Must be larger than subset.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (finalRectX < 0 || finalRectY < 0 || finalRectX + finalRectW > realRefWidth || finalRectY + finalRectH > realRefHeight) {
                    Toast.makeText(this, "ROI is out of bounds!", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                Log.d("DIC", "Processing ROI: X:$finalRectX, Y:$finalRectY, W:$finalRectW, H:$finalRectH")

                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                tvTimer.visibility = View.VISIBLE
                tvTimer.text = "Initializing 2D Scan..."

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

                    val applyBlur = switchBlur.isChecked
                    val useNlvc = rgStrainMethod.checkedRadioButtonId == R.id.rbNlvc
                    val maskData = roiMaskBytes ?: ByteArray(0)

                    // CALL THE 2D NATIVE FUNCTION
                    val rawData = IndicVisionNativeLib.computeFullField(
                        refBytes!!, defBytes!!,
                        maskData,
                        finalRectX, finalRectY, finalRectW, finalRectH,
                        step, subset, strainWin,
                        true, true,
                        applyBlur,
                        useNlvc,
                        callback
                    )

                    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0

                    runOnUiThread {
                        progressBar.visibility = View.GONE

                        if (rawData == null || rawData.isEmpty()) {
                            tvTimer.text = "Analysis Failed"
                            tvResult.text = "❌ Engine returned no data"
                        } else {
                            tvTimer.text = "Analysis Done in %.2f s. Points: ${rawData.size / 8}".format(totalTime)

                            // 1. Save raw data to a cache file
                            val dataFile = File(cacheDir, "analysis_results.bin")
                            val buffer = java.nio.ByteBuffer.allocate(rawData.size * 4)
                            buffer.order(java.nio.ByteOrder.nativeOrder())
                            buffer.asFloatBuffer().put(rawData)
                            dataFile.writeBytes(buffer.array())

                            // 2. Save Deformed Image to cache
                            val defFile = File(cacheDir, "temp_def_view.jpg")
                            defBytes?.let { defFile.writeBytes(it) }

                            // --- CRITICAL FIX: Save state for the View button ---
                            lastStep = step
                            roiX = finalRectX
                            roiY = finalRectY
                            // ----------------------------------------------------

                            // 3. Launch Viewer with all necessary data
                            val intent = Intent(this@StaticAnalysisActivity, ResultViewerActivity::class.java)
                            intent.putExtra("DATA_PATH", dataFile.absolutePath)
                            intent.putExtra("IMG_W", realRefWidth)
                            intent.putExtra("IMG_H", realRefHeight)
                            intent.putExtra("STEP", step)
                            intent.putExtra("DEF_PATH", defFile.absolutePath)
                            intent.putExtra("ROI_X", finalRectX)
                            intent.putExtra("ROI_Y", finalRectY)

                            startActivity(intent)
                            btnViewResults.visibility = View.VISIBLE
                        }
                        btnCalculateFullField.isEnabled = true
                    }
                }.start()
            }
        }

        btnViewResults.setOnClickListener {
            val dataFile = File(cacheDir, "analysis_results.bin")
            val defFile = File(cacheDir, "temp_def_view.jpg")

            if (dataFile.exists() && defFile.exists()) {
                val intent = Intent(this@StaticAnalysisActivity, ResultViewerActivity::class.java)
                intent.putExtra("DATA_PATH", dataFile.absolutePath)
                intent.putExtra("IMG_W", realRefWidth)
                intent.putExtra("IMG_H", realRefHeight)
                intent.putExtra("STEP", lastStep)
                intent.putExtra("DEF_PATH", defFile.absolutePath)
                intent.putExtra("ROI_X", roiX)
                intent.putExtra("ROI_Y", roiY)
                startActivity(intent)
            } else {
                Toast.makeText(this, "No previous results found. Please compute first.", Toast.LENGTH_SHORT).show()
                btnViewResults.visibility = View.GONE
            }
        }
    }

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
                    writer.write("X,Y,U_Displacement,V_Displacement,Exx,Eyy,Exy,Correlation\n")

                    var i = 0
                    while (i < data.size) {
                        val x = data[i]; val y = data[i+1]
                        val u = data[i+2]; val v = data[i+3]
                        val exx = data[i+4]; val eyy = data[i+5]; val exy = data[i+6]
                        val c = data[i+7]
                        writer.write("$x,$y,$u,$v,$exx,$eyy,$exy,$c\n")
                        i += 8
                    }
                    writer.flush()
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

    private fun handleImageSelection(uri: Uri, isRef: Boolean) {
        val name = getFileName(uri)
        if (isRef) {
            tvRefName.text = "Ref: $name"
            refImageUriString = uri.toString()
        } else {
            tvDefName.text = "Def: $name"
        }

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

    private fun handleMaskSelection(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                roiMaskBytes = stream.readBytes()
                Toast.makeText(this, "Custom ROI Mask Uploaded!", Toast.LENGTH_SHORT).show()
                btnLoadRoiMask.text = "Mask Uploaded ✅"
                btnLoadRoiMask.setBackgroundColor(android.graphics.Color.parseColor("#00AA00"))
                hasCustomRoi = true
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
        btnCalculateFullField.isEnabled = ready
        btnDefineRoi.isEnabled = (refBytes != null)

        if (ready) btnCalculateFullField.setBackgroundColor(android.graphics.Color.parseColor("#0000AA"))
        if (refBytes != null) btnDefineRoi.setBackgroundColor(android.graphics.Color.parseColor("#673AB7"))
    }
}