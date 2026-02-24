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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class StaticAnalysisActivity : AppCompatActivity() {

    // --- CRITICAL FIX: The ViewModel Vault ---
    // This survives screen rotations!
    private val viewModel: AnalysisViewModel by viewModels()

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
    private lateinit var btnViewResults: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_static_analysis)

        // Bind UI Components
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
        btnViewResults = findViewById(R.id.btnViewResults)

        // --- RESTORE STATE AFTER ROTATION ---
        restoreUiFromViewModel()

        // Launchers for Images and Mask
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
                    viewModel.roiX = data.getIntExtra("ROI_X", 0)
                    viewModel.roiY = data.getIntExtra("ROI_Y", 0)
                    viewModel.roiW = data.getIntExtra("ROI_W", viewModel.realRefWidth)
                    viewModel.roiH = data.getIntExtra("ROI_H", viewModel.realRefHeight)
                    viewModel.hasCustomRoi = true
                    tvInstruction.text = "✅ ROI Set: ${viewModel.roiW} x ${viewModel.roiH} px"
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
            if (viewModel.refBytes != null) {
                val tempFile = File(cacheDir, "temp_roi_ref.bin")
                tempFile.writeBytes(viewModel.refBytes!!)

                val intent = Intent(this, RoiDrawActivity::class.java)
                intent.putExtra("IMAGE_FILE_PATH", tempFile.absolutePath)
                intent.putExtra("IMAGE_WIDTH", viewModel.realRefWidth)
                intent.putExtra("IMAGE_HEIGHT", viewModel.realRefHeight)
                roiStudioLauncher.launch(intent)
            }
        }

        btnFullImage.setOnClickListener {
            if (viewModel.realRefWidth > 0) {
                viewModel.hasCustomRoi = false
                tvInstruction.text = "✅ Using Full Image"
                checkReady()
            }
        }

        // Logic: FULL FIELD 2D
        btnCalculateFullField.setOnClickListener {
            if (viewModel.isReadyToCompute()) {
                val subset = etSubsetSize.text.toString().toIntOrNull() ?: 41
                val step = etStepSize.text.toString().toIntOrNull() ?: 5
                val strainWin = etStrainWindow.text.toString().toIntOrNull() ?: 15

                var finalRectX: Int
                var finalRectY: Int
                var finalRectW: Int
                var finalRectH: Int

                if (viewModel.hasCustomRoi) {
                    finalRectX = viewModel.roiX
                    finalRectY = viewModel.roiY
                    finalRectW = viewModel.roiW
                    finalRectH = viewModel.roiH
                } else {
                    val margin = (subset / 2) + 10
                    finalRectX = margin
                    finalRectY = margin
                    finalRectW = viewModel.realRefWidth - (2 * margin)
                    finalRectH = viewModel.realRefHeight - (2 * margin)
                }

                if (finalRectW < subset || finalRectH < subset) {
                    Toast.makeText(this, "ROI is too small! Must be larger than subset.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

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
                    val maskData = viewModel.roiMaskBytes ?: ByteArray(0)

                    val rawData = IndicVisionNativeLib.computeFullField(
                        viewModel.refBytes!!, viewModel.defBytes!!, maskData,
                        finalRectX, finalRectY, finalRectW, finalRectH,
                        step, subset, strainWin, true, true, applyBlur, useNlvc, callback
                    )

                    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0

                    runOnUiThread {
                        progressBar.visibility = View.GONE

                        if (rawData == null || rawData.isEmpty()) {
                            tvTimer.text = "Analysis Failed"
                            tvResult.text = "❌ Engine returned no data"
                        } else {
                            tvTimer.text = "Analysis Done in %.2f s. Points: ${rawData.size / 8}".format(totalTime)

                            val dataFile = File(cacheDir, "analysis_results.bin")
                            val buffer = java.nio.ByteBuffer.allocate(rawData.size * 4)
                            buffer.order(java.nio.ByteOrder.nativeOrder())
                            buffer.asFloatBuffer().put(rawData)
                            dataFile.writeBytes(buffer.array())

                            val defFile = File(cacheDir, "temp_def_view.jpg")
                            viewModel.defBytes?.let { defFile.writeBytes(it) }

                            // Save state to ViewModel
                            viewModel.lastStep = step
                            viewModel.roiX = finalRectX
                            viewModel.roiY = finalRectY
                            viewModel.hasCompletedAnalysis = true

                            launchResultViewer(dataFile.absolutePath, defFile.absolutePath)
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
                launchResultViewer(dataFile.absolutePath, defFile.absolutePath)
            } else {
                Toast.makeText(this, "No previous results found.", Toast.LENGTH_SHORT).show()
                btnViewResults.visibility = View.GONE
            }
        }
    }

    private fun launchResultViewer(dataPath: String, defPath: String) {
        val intent = Intent(this, ResultViewerActivity::class.java).apply {
            putExtra("DATA_PATH", dataPath)
            putExtra("DEF_PATH", defPath)
            putExtra("IMG_W", viewModel.realRefWidth)
            putExtra("IMG_H", viewModel.realRefHeight)
            putExtra("STEP", viewModel.lastStep)
            putExtra("ROI_X", viewModel.roiX)
            putExtra("ROI_Y", viewModel.roiY)
        }
        startActivity(intent)
    }

    // --- REBUILDS UI FROM VIEWMODEL ---
    private fun restoreUiFromViewModel() {
        tvRefName.text = viewModel.refName
        tvDefName.text = viewModel.defName

        viewModel.refBytes?.let { imgRef.setImageBitmap(IndicVisionNativeLib.getPreviewFromBytes(it, 1000)) }
        viewModel.defBytes?.let { imgDef.setImageBitmap(IndicVisionNativeLib.getPreviewFromBytes(it, 1000)) }

        if (viewModel.hasCompletedAnalysis) {
            btnViewResults.visibility = View.VISIBLE
        }
        checkReady()
    }

    private fun handleImageSelection(uri: Uri, isRef: Boolean) {
        val name = getFileName(uri)
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (isRef) {
                    viewModel.refName = "Ref: $name"
                    viewModel.refBytes = bytes
                    val dims = IndicVisionNativeLib.getImageDimensions(bytes)
                    viewModel.realRefWidth = dims[0]
                    viewModel.realRefHeight = dims[1]
                    imgRef.setImageBitmap(IndicVisionNativeLib.getPreviewFromBytes(bytes, 1000))
                    tvRefName.text = viewModel.refName
                } else {
                    viewModel.defName = "Def: $name"
                    viewModel.defBytes = bytes
                    imgDef.setImageBitmap(IndicVisionNativeLib.getPreviewFromBytes(bytes, 1000))
                    tvDefName.text = viewModel.defName
                }
                checkReady()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun handleMaskSelection(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                viewModel.roiMaskBytes = stream.readBytes()
                Toast.makeText(this, "Custom ROI Mask Uploaded!", Toast.LENGTH_SHORT).show()
                btnLoadRoiMask.text = "Mask Uploaded ✅"
                btnLoadRoiMask.setBackgroundColor(android.graphics.Color.parseColor("#00AA00"))
                viewModel.hasCustomRoi = true
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
        return result ?: "Image_File"
    }

    private fun checkReady() {
        val ready = viewModel.isReadyToCompute()
        btnCalculateFullField.isEnabled = ready
        btnDefineRoi.isEnabled = (viewModel.refBytes != null)

        if (ready) btnCalculateFullField.setBackgroundColor(android.graphics.Color.parseColor("#0000AA"))
        if (viewModel.refBytes != null) btnDefineRoi.setBackgroundColor(android.graphics.Color.parseColor("#673AB7"))
    }
}