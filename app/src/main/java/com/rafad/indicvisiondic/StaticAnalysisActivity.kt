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

// --- THE MISSING IMPORTS FOR THE ROI DRAWING ENGINE ---
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

class StaticAnalysisActivity : AppCompatActivity() {

    private val viewModel: AnalysisViewModel by viewModels()

    // UI Components
    private lateinit var imgRef: ImageView
    private lateinit var imgDef: ImageView
    private lateinit var btnLoadRef: Button
    private lateinit var btnLoadDef: Button
    private lateinit var btnFullImage: Button
    private lateinit var btnDefineRoi: Button
    private lateinit var btnManualRoi: Button
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
        btnManualRoi = findViewById(R.id.btnManualRoi)
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

        restoreUiFromViewModel()

        val pickRef = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImageSelection(it, isRef = true) }
        }
        val pickDef = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleImageSelection(it, isRef = false) }
        }
        val pickMask = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleMaskSelection(it) }
        }

        val roiStudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    viewModel.roiX = data.getIntExtra("ROI_X", 0)
                    viewModel.roiY = data.getIntExtra("ROI_Y", 0)
                    viewModel.roiW = data.getIntExtra("ROI_W", viewModel.realRefWidth)
                    viewModel.roiH = data.getIntExtra("ROI_H", viewModel.realRefHeight)
                    viewModel.hasCustomRoi = true
                    viewModel.roiMaskBytes = null // Clear any complex mask if drawing a box
                    tvInstruction.text = "✅ ROI Set: ${viewModel.roiW} x ${viewModel.roiH} px"
                    checkReady()
                }
            } else {
                tvInstruction.text = "❌ ROI Selection Cancelled"
            }
        }

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

        btnManualRoi.setOnClickListener { showShapeRoiDialog() }

        btnFullImage.setOnClickListener {
            if (viewModel.realRefWidth > 0) {
                viewModel.hasCustomRoi = false
                viewModel.roiMaskBytes = null
                tvInstruction.text = "✅ Using Full Image"
                checkReady()
            }
        }

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

                    // 🚀 1. CALCULATE MAXIMUM POSSIBLE POINTS
                    val gridW = finalRectW / step
                    val gridH = finalRectH / step
                    val maxPoints = gridW * gridH

                    // 🚀 2. ALLOCATE DIRECT SHARED MEMORY
                    // 8 floats per point, 4 bytes per float
                    val byteCapacity = maxPoints * 8 * 4
                    val outputBuffer = java.nio.ByteBuffer.allocateDirect(byteCapacity)
                    outputBuffer.order(java.nio.ByteOrder.nativeOrder())

                    // 🚀 3. CALL C++ TO WRITE DIRECTLY INTO THE BUFFER
                    val validPointsCount = IndicVisionNativeLib.computeFullFieldDirect(
                        viewModel.refBytes!!, viewModel.defBytes!!, maskData,
                        finalRectX, finalRectY, finalRectW, finalRectH,
                        step, subset, strainWin, true, true, applyBlur, useNlvc,
                        outputBuffer, callback
                    )

                    val totalTime = (System.currentTimeMillis() - startTime) / 1000.0

                    runOnUiThread {
                        progressBar.visibility = View.GONE

                        if (validPointsCount <= 0) {
                            tvTimer.text = "Analysis Failed"
                            tvResult.text = "❌ Engine returned no data"
                        } else {
                            tvTimer.text = "Analysis Done in %.2f s. Points: $validPointsCount".format(totalTime)

                            // 🚀 4. EXTRACT ONLY THE VALID BYTES AND SAVE TO DISK
                            val dataFile = File(cacheDir, "analysis_results.bin")
                            val validByteCount = validPointsCount * 8 * 4

                            val exactBytes = ByteArray(validByteCount)
                            outputBuffer.position(0) // Reset buffer pointer to the start
                            outputBuffer.get(exactBytes, 0, validByteCount) // Copy exact payload

                            dataFile.writeBytes(exactBytes) // Save instantly

                            val defFile = File(cacheDir, "temp_def_view.png")
                            viewModel.defBytes?.let { bytes ->
                                val fullResBitmap = IndicVisionNativeLib.getPreviewFromBytes(bytes, viewModel.realRefWidth)
                                defFile.outputStream().use { out ->
                                    fullResBitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                            }

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
            val defFile = File(cacheDir, "temp_def_view.png")
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
                btnLoadRoiMask.setBackgroundColor(Color.parseColor("#00AA00"))
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

        if (ready) btnCalculateFullField.setBackgroundColor(Color.parseColor("#0000AA"))
        if (viewModel.refBytes != null) {
            btnDefineRoi.setBackgroundColor(Color.parseColor("#673AB7"))
            btnManualRoi.setBackgroundColor(Color.parseColor("#009688"))
        }
    }

    private fun showShapeRoiDialog() {
        if (viewModel.refBytes == null) {
            Toast.makeText(this, "Load Reference Image first!", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_roi, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerShape)
        val shapes = arrayOf("Rectangle / Square", "Circle", "Ellipse", "Triangle")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapes)

        val layoutRect = dialogView.findViewById<View>(R.id.layoutRect)
        val layoutCircle = dialogView.findViewById<View>(R.id.layoutCircle)
        val layoutEllipse = dialogView.findViewById<View>(R.id.layoutEllipse)
        val layoutTri = dialogView.findViewById<View>(R.id.layoutTri)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                layoutRect.visibility = if (pos == 0) View.VISIBLE else View.GONE
                layoutCircle.visibility = if (pos == 1) View.VISIBLE else View.GONE
                layoutEllipse.visibility = if (pos == 2) View.VISIBLE else View.GONE
                layoutTri.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // Pre-fill Rectangle info
        dialogView.findViewById<EditText>(R.id.etRectX).setText(viewModel.roiX.toString())
        dialogView.findViewById<EditText>(R.id.etRectY).setText(viewModel.roiY.toString())
        dialogView.findViewById<EditText>(R.id.etRectW).setText(if (viewModel.roiW > 0) viewModel.roiW.toString() else viewModel.realRefWidth.toString())
        dialogView.findViewById<EditText>(R.id.etRectH).setText(if (viewModel.roiH > 0) viewModel.roiH.toString() else viewModel.realRefHeight.toString())

        android.app.AlertDialog.Builder(this)
            .setTitle("Define Mathematical ROI")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                var finalX = 0; var finalY = 0; var finalW = 0; var finalH = 0
                var requiresMask = false
                val imgW = viewModel.realRefWidth
                val imgH = viewModel.realRefHeight

                // Virtual Canvas for Mask Generation
                val maskBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(maskBitmap)
                canvas.drawColor(Color.BLACK) // Black = ignore
                val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL } // White = compute

                when (spinner.selectedItemPosition) {
                    0 -> { // Rectangle
                        finalX = dialogView.findViewById<EditText>(R.id.etRectX).text.toString().toIntOrNull() ?: 0
                        finalY = dialogView.findViewById<EditText>(R.id.etRectY).text.toString().toIntOrNull() ?: 0
                        finalW = dialogView.findViewById<EditText>(R.id.etRectW).text.toString().toIntOrNull() ?: imgW
                        finalH = dialogView.findViewById<EditText>(R.id.etRectH).text.toString().toIntOrNull() ?: imgH
                        viewModel.roiMaskBytes = null // No mask needed for basic rect
                    }
                    1 -> { // Circle
                        requiresMask = true
                        val cx = dialogView.findViewById<EditText>(R.id.etCircCx).text.toString().toFloatOrNull() ?: (imgW/2f)
                        val cy = dialogView.findViewById<EditText>(R.id.etCircCy).text.toString().toFloatOrNull() ?: (imgH/2f)
                        val r = dialogView.findViewById<EditText>(R.id.etCircR).text.toString().toFloatOrNull() ?: 100f
                        canvas.drawCircle(cx, cy, r, paint)

                        finalX = (cx - r).toInt().coerceAtLeast(0)
                        finalY = (cy - r).toInt().coerceAtLeast(0)
                        finalW = (r * 2).toInt()
                        finalH = (r * 2).toInt()
                    }
                    2 -> { // Ellipse
                        requiresMask = true
                        val cx = dialogView.findViewById<EditText>(R.id.etEllCx).text.toString().toFloatOrNull() ?: (imgW/2f)
                        val cy = dialogView.findViewById<EditText>(R.id.etEllCy).text.toString().toFloatOrNull() ?: (imgH/2f)
                        val rx = dialogView.findViewById<EditText>(R.id.etEllRx).text.toString().toFloatOrNull() ?: 150f
                        val ry = dialogView.findViewById<EditText>(R.id.etEllRy).text.toString().toFloatOrNull() ?: 100f
                        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)

                        finalX = (cx - rx).toInt().coerceAtLeast(0)
                        finalY = (cy - ry).toInt().coerceAtLeast(0)
                        finalW = (rx * 2).toInt()
                        finalH = (ry * 2).toInt()
                    }
                    3 -> { // Triangle
                        requiresMask = true
                        val x1 = dialogView.findViewById<EditText>(R.id.etTriX1).text.toString().toFloatOrNull() ?: 0f
                        val y1 = dialogView.findViewById<EditText>(R.id.etTriY1).text.toString().toFloatOrNull() ?: 0f
                        val x2 = dialogView.findViewById<EditText>(R.id.etTriX2).text.toString().toFloatOrNull() ?: 0f
                        val y2 = dialogView.findViewById<EditText>(R.id.etTriY2).text.toString().toFloatOrNull() ?: 0f
                        val x3 = dialogView.findViewById<EditText>(R.id.etTriX3).text.toString().toFloatOrNull() ?: 0f
                        val y3 = dialogView.findViewById<EditText>(R.id.etTriY3).text.toString().toFloatOrNull() ?: 0f

                        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }
                        canvas.drawPath(path, paint)

                        finalX = minOf(x1, x2, x3).toInt().coerceAtLeast(0)
                        finalY = minOf(y1, y2, y3).toInt().coerceAtLeast(0)
                        finalW = (maxOf(x1, x2, x3) - finalX).toInt()
                        finalH = (maxOf(y1, y2, y3) - finalY).toInt()
                    }
                }

                // If a non-rectangular shape was chosen, compress the virtual canvas to bytes for C++
                if (requiresMask) {
                    val stream = java.io.ByteArrayOutputStream()
                    maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    viewModel.roiMaskBytes = stream.toByteArray()
                    tvInstruction.text = "✅ Complex Shape ROI Generated & Applied!"
                } else {
                    tvInstruction.text = "✅ Rectangular ROI Set: $finalW x $finalH px"
                }

                // Save Bounding Box to ViewModel
                viewModel.roiX = finalX
                viewModel.roiY = finalY
                viewModel.roiW = finalW
                viewModel.roiH = finalH
                viewModel.hasCustomRoi = true
                checkReady()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}