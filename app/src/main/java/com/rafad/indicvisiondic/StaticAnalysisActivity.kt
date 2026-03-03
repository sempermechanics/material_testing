package com.rafad.indicvisiondic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import java.io.File

// --- ROI DRAWING ENGINE IMPORTS ---
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // State
    private var isProcessing = false
    private var processingStartTime: Long = 0

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
            uri?.let { handleReferenceImage(it) }
        }

        // Multi-image picker for deformed images
        val pickDefBatch = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                handleDeformedBatch(uris)
            } else {
                Toast.makeText(this, "No images selected", Toast.LENGTH_SHORT).show()
            }
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
        btnLoadDef.setOnClickListener { pickDefBatch.launch("image/*") }
        btnLoadRoiMask.setOnClickListener { pickMask.launch("image/*") }

        btnDefineRoi.setOnClickListener {
            if (viewModel.refBytes != null) {
                val tempFile = File(cacheDir, "temp_roi_ref.bin")
                try {
                    tempFile.writeBytes(viewModel.refBytes!!)
                    val intent = Intent(this, RoiDrawActivity::class.java)
                    intent.putExtra("IMAGE_FILE_PATH", tempFile.absolutePath)
                    intent.putExtra("IMAGE_WIDTH", viewModel.realRefWidth)
                    intent.putExtra("IMAGE_HEIGHT", viewModel.realRefHeight)
                    roiStudioLauncher.launch(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Failed to save temp file", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Load an image first!", Toast.LENGTH_SHORT).show()
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
            startBatchAnalysis()
        }

        btnViewResults.setOnClickListener {
            openResultViewer()
        }
    }

    private fun handleReferenceImage(uri: Uri) {
        val name = getFileName(uri)
        
        if (name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)) {
            Toast.makeText(this, "⚠️ WARNING: JPEG artifacts severely reduce DIC accuracy. Lossless PNG, TIFF, or RAW formats are recommended!", Toast.LENGTH_LONG).show()
        }
        
        val isRaw = name.endsWith(".dng", true) || name.endsWith(".raw", true)

        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                var bytes: ByteArray
                var previewBmp: Bitmap? = null
                
                if (isRaw) {
                    val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                    if (bitmap != null) {
                        viewModel.realRefWidth = bitmap.width
                        viewModel.realRefHeight = bitmap.height
                        
                        val buffer = java.nio.ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
                        bitmap.copyPixelsToBuffer(buffer)
                        bytes = buffer.array()
                        
                        val ratio = 1000f / bitmap.width
                        previewBmp = android.graphics.Bitmap.createScaledBitmap(bitmap, 1000, (bitmap.height * ratio).toInt(), true)
                    } else {
                        Toast.makeText(this, "Failed to decode RAW image.", Toast.LENGTH_SHORT).show()
                        return
                    }
                } else {
                    bytes = stream.readBytes()
                    val dims = IndicVisionNativeLib.getImageDimensions(bytes)
                    viewModel.realRefWidth = dims[0]
                    viewModel.realRefHeight = dims[1]
                    previewBmp = IndicVisionNativeLib.getPreviewFromBytes(bytes, 1000)
                }

                viewModel.refName = "Ref: $name"
                viewModel.refBytes = bytes
                imgRef.setImageBitmap(previewBmp)
                tvRefName.text = viewModel.refName

                if (!viewModel.hasCustomRoi) {
                    viewModel.roiX = 0
                    viewModel.roiY = 0
                    viewModel.roiW = viewModel.realRefWidth
                    viewModel.roiH = viewModel.realRefHeight
                }
                checkReady()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun handleDeformedBatch(uris: List<Uri>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "temp_deformed")
                if (!tempDir.exists()) tempDir.mkdirs()
                tempDir.listFiles()?.forEach { it.delete() }

                // ✅ FIX: Clear OLD result metadata BEFORE building the new selection,
                // so we start fresh without wiping the paths we are about to set.
                viewModel.clearPreviousResults()

                val filePaths = mutableListOf<String>()

                withContext(Dispatchers.Main) {
                    tvResult.text = "Caching images..."
                }

                for ((index, uri) in uris.withIndex()) {
                    var bytes: ByteArray? = null
                    var previewBmp: Bitmap? = null
                    
                    val originalName = getFileName(uri)
                    
                    if (index == 0 && (originalName.endsWith(".jpg", true) || originalName.endsWith(".jpeg", true))) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@StaticAnalysisActivity, "⚠️ WARNING: JPEG artifact compression detected in batch. This will reduce accuracy.", Toast.LENGTH_LONG).show()
                        }
                    }
                    
                    val isRaw = originalName.endsWith(".dng", true) || originalName.endsWith(".raw", true)

                    contentResolver.openInputStream(uri)?.use { stream ->
                        if (isRaw) {
                            val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                            if (bitmap != null) {
                                val buffer = java.nio.ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
                                bitmap.copyPixelsToBuffer(buffer)
                                bytes = buffer.array()
                                
                                if (index == 0) {
                                    val ratio = 1000f / bitmap.width
                                    previewBmp = android.graphics.Bitmap.createScaledBitmap(bitmap, 1000, (bitmap.height * ratio).toInt(), true)
                                }
                            }
                        } else {
                            bytes = stream.readBytes()
                            if (index == 0 && bytes != null) {
                                previewBmp = IndicVisionNativeLib.getPreviewFromBytes(bytes, 1000)
                            }
                        }
                    }
                    
                    if (bytes == null) continue

                    val sanitizedName = originalName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                    val filename = String.format("%04d_%s", index, sanitizedName)
                    val file = File(tempDir, filename)
                    file.writeBytes(bytes)
                    filePaths.add(file.absolutePath)

                    if (index == 0) {
                        withContext(Dispatchers.Main) {
                            imgDef.setImageBitmap(previewBmp)
                        }
                    }
                }

                // ✅ FIX: Assign defFilePaths AFTER clearPreviousResults() — it is now safe.
                val sortedPaths = filePaths.sorted()
                viewModel.defFilePaths = sortedPaths

                withContext(Dispatchers.Main) {
                    tvDefName.text = viewModel.getDefDisplayName()
                    tvResult.text = ""
                    checkReady()
                    val message = if (sortedPaths.size == 1) "1 image selected" else "${sortedPaths.size} images selected"
                    Toast.makeText(this@StaticAnalysisActivity, message, Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("StaticAnalysis", "Error handling batch", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StaticAnalysisActivity, "Error loading images: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 🚀 CRITICAL: Batch Analysis Execution Pipeline
    private fun startBatchAnalysis() {
        if (!viewModel.isReadyToCompute()) return

        val subset = etSubsetSize.text.toString().toIntOrNull() ?: 41
        val step = etStepSize.text.toString().toIntOrNull() ?: 5
        val strainWin = etStrainWindow.text.toString().toIntOrNull() ?: 15

        var finalRectX = viewModel.roiX
        var finalRectY = viewModel.roiY
        var finalRectW = viewModel.roiW
        var finalRectH = viewModel.roiH

        if (!viewModel.hasCustomRoi) {
            val margin = (subset / 2) + 10
            finalRectX = margin
            finalRectY = margin
            finalRectW = viewModel.realRefWidth - (2 * margin)
            finalRectH = viewModel.realRefHeight - (2 * margin)
        }

        if (finalRectW < subset || finalRectH < subset) {
            Toast.makeText(this, "ROI is too small! Must be larger than subset.", Toast.LENGTH_LONG).show()
            return
        }

        isProcessing = true
        checkReady()
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvTimer.visibility = View.VISIBLE
        tvTimer.text = "Initializing Engine..."

        processingStartTime = System.currentTimeMillis()

        val batchDir = File(cacheDir, "batch_results")
        if (!batchDir.exists()) batchDir.mkdirs()
        batchDir.listFiles()?.forEach { it.delete() }

        viewModel.lastBatchDirPath = batchDir.absolutePath
        viewModel.lastStep = step

        val applyBlur = switchBlur.isChecked
        val useNlvc = rgStrainMethod.checkedRadioButtonId == R.id.rbNlvc
        val maskData = viewModel.roiMaskBytes ?: ByteArray(0)

        // 🚀 LAUNCH ON THE DEDICATED NATIVE THREAD
        // All JNI calls (initializeReference + computeFullFieldDirect) MUST run on the
        // same OS thread so that the LLVM OpenMP runtime’s TLS master-thread registration
        // is always valid. Using Dispatchers.IO would risk Kotlin resuming on a different
        // worker thread after each suspension point, causing __kmp_invoke_microtask to
        // dereference a null kmp_thread_t* → SIGSEGV. The ViewModel’s nativeExecutor
        // is a SingleThreadExecutor: one persistent OS thread, same identity every time.
        lifecycleScope.launch(viewModel.nativeExecutor.asCoroutineDispatcher()) {
            try {
                val totalFrames = viewModel.defFilePaths.size
                val refBytes = viewModel.refBytes ?: throw IllegalStateException("Reference missing")
                var firstFrameValidPoints = 0

                // 🟠 BUG 2 FIX: INITIALIZE NATIVE REFERENCE ONCE
                // This prevents C++ from rebuilding the heavy 48MB reference image on every loop!
                // ✅ Non-suspending UI update — keeps coroutine on the native thread.
                runOnUiThread { tvTimer.text = "Caching Reference in Native Engine..." }
                IndicVisionNativeLib.initializeReference(refBytes, viewModel.realRefWidth, viewModel.realRefHeight, applyBlur)

                val gridW = finalRectW / step
                val gridH = finalRectH / step
                val maxPoints = gridW * gridH
                val byteCapacity = maxPoints * 8 * 4
                val outputBuffer = java.nio.ByteBuffer.allocateDirect(byteCapacity)
                outputBuffer.order(java.nio.ByteOrder.nativeOrder())

                for ((frameIndex, defPath) in viewModel.defFilePaths.withIndex()) {

                    // ✅ FIX: Build UI strings on the IO thread BEFORE loading bytes.
                    // We MUST NOT call withContext(Dispatchers.Main) inside this loop.
                    // Doing so suspends the coroutine, which Kotlin may then resume on a
                    // DIFFERENT IO thread. The LLVM OpenMP runtime has thread-local state
                    // (task scheduler, TLS pool) bound to the original thread — invoking
                    // #pragma omp parallel from a new thread causes a null-ptr SIGSEGV
                    // inside __kmp_invoke_microtask. Fix: post UI updates non-suspendingly
                    // via runOnUiThread (fire-and-forget), keeping the coroutine on one thread.
                    val frameLabel = "Processing Frame ${frameIndex + 1}/$totalFrames..."
                    runOnUiThread { tvTimer.text = frameLabel }

                    val defBytes = File(defPath).readBytes()

                    val callback = object : ProgressCallback {
                        override fun onProgressUpdate(percentage: Int) {
                            runOnUiThread {
                                val frameProgress = (frameIndex.toFloat() / totalFrames) * 100
                                val overallProgress = frameProgress + (percentage.toFloat() / totalFrames)
                                progressBar.progress = overallProgress.toInt()
                            }
                        }
                    }

                    outputBuffer.clear()

                    // 🚀 RUN C++ ENGINE — coroutine stays on the same IO thread throughout.
                    val validPointsCount = IndicVisionNativeLib.computeFullFieldDirect(
                        refBytes, defBytes, maskData,
                        finalRectX, finalRectY, finalRectW, finalRectH,
                        step, subset, strainWin, true, true, applyBlur, useNlvc,
                        outputBuffer, callback
                    )

                    if (frameIndex == 0) firstFrameValidPoints = validPointsCount
                    if (validPointsCount <= 0) continue

                    val outputFile = File(batchDir, String.format("frame_%04d.dat", frameIndex))
                    outputFile.outputStream().use { fos ->
                        val bytes = ByteArray(validPointsCount * 8 * 4)
                        outputBuffer.position(0)
                        outputBuffer.get(bytes, 0, bytes.size)
                        fos.write(bytes)
                    }

                    @Suppress("ExplicitGarbageCollectionCall")
                    System.gc()
                }

                val totalTime = (System.currentTimeMillis() - processingStartTime) / 1000.0

                withContext(Dispatchers.Main) {
                    isProcessing = false
                    progressBar.visibility = View.GONE

                    if (firstFrameValidPoints <= 0) {
                        tvTimer.text = "Analysis Failed"
                        tvResult.text = "❌ Engine returned no data"
                    } else {
                        tvTimer.text = "Batch Done in %.2f s".format(totalTime)
                        tvResult.text = "✅ Computed $totalFrames frames!"

                        val defFile = File(cacheDir, "temp_def_view.png")
                        // 🚀 Lagrangian Alignment Fix: Plot results on the Reference Image background
                        // to ensure the (x,y) grid matches the material base exactly.
                        val refBytes = viewModel.refBytes ?: throw IllegalStateException("Reference missing")
                        val fullResBitmap = IndicVisionNativeLib.getPreviewFromBytes(refBytes, viewModel.realRefWidth)
                        defFile.outputStream().use { out ->
                            fullResBitmap?.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        viewModel.lastDefPath = defFile.absolutePath

                        viewModel.hasCompletedAnalysis = true
                        checkReady()
                        openResultViewer()
                    }
                }

            } catch (e: Exception) {
                Log.e("StaticAnalysis", "Batch processing failed", e)
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    progressBar.visibility = View.GONE
                    tvTimer.text = "Engine Error"
                    tvResult.text = "❌ Error: ${e.message}"
                    checkReady()
                }
            }
        }
    }

    private fun openResultViewer() {
        if (!viewModel.hasCompletedAnalysis) return

        val intent = Intent(this, ResultViewerActivity::class.java).apply {
            putExtra("BATCH_DIR_PATH", viewModel.lastBatchDirPath)
            putExtra("DEF_PATH", viewModel.lastDefPath)
            putExtra("IMG_W", viewModel.realRefWidth)
            putExtra("IMG_H", viewModel.realRefHeight)
            putExtra("STEP", viewModel.lastStep)
            putExtra("ROI_X", viewModel.roiX)
            putExtra("ROI_Y", viewModel.roiY)
            putExtra("REF_NAME", viewModel.refName.removePrefix("Ref: "))
            
            // Pass the nice names of the files for the UI to display instead of abstract "Frame 1"
            val fileNames = viewModel.defFilePaths.map { path ->
                val fullName = path.substringAfterLast('/')
                // remove the '0000_' prefix which was added for sorting cache
                if (fullName.length > 5 && fullName[4] == '_') fullName.substring(5) else fullName
            }
            putStringArrayListExtra("DEF_FILE_NAMES", ArrayList(fileNames))
        }
        startActivity(intent)
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
        btnCalculateFullField.isEnabled = ready && !isProcessing
        btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        btnManualRoi.isEnabled = !isProcessing
        btnLoadRef.isEnabled = !isProcessing
        btnLoadDef.isEnabled = !isProcessing
        btnViewResults.visibility = if (viewModel.hasCompletedAnalysis && !isProcessing) View.VISIBLE else View.GONE

        if (ready && !isProcessing) btnCalculateFullField.setBackgroundColor(Color.parseColor("#0000AA"))
        else btnCalculateFullField.setBackgroundColor(Color.GRAY)

        if (viewModel.refBytes != null) {
            btnDefineRoi.setBackgroundColor(Color.parseColor("#673AB7"))
            btnManualRoi.setBackgroundColor(Color.parseColor("#009688"))
        }
    }

    private fun restoreUiFromViewModel() {
        tvRefName.text = viewModel.refName
        tvDefName.text = viewModel.getDefDisplayName()

        viewModel.refBytes?.let { imgRef.setImageBitmap(IndicVisionNativeLib.getPreviewFromBytes(it, 1000)) }

        if (viewModel.defFilePaths.isNotEmpty()) {
            try {
                val firstBytes = File(viewModel.defFilePaths[0]).readBytes()
                imgDef.setImageBitmap(IndicVisionNativeLib.getPreviewFromBytes(firstBytes, 1000))
            } catch (e: Exception) { e.printStackTrace() }
        }
        checkReady()
    }

    private fun showShapeRoiDialog() {
        if (viewModel.refBytes == null) {
            Toast.makeText(this, "Load Reference Image first!", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_manual_roi, null)
        val imgW = viewModel.realRefWidth
        val imgH = viewModel.realRefHeight

        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerShape)
        val layoutRect = dialogView.findViewById<View>(R.id.layoutRect)
        val layoutCircle = dialogView.findViewById<View>(R.id.layoutCircle)
        val layoutEllipse = dialogView.findViewById<View>(R.id.layoutEllipse)
        val layoutTri = dialogView.findViewById<View>(R.id.layoutTri)

        val etRectX = dialogView.findViewById<EditText>(R.id.etRectX)
        val etRectY = dialogView.findViewById<EditText>(R.id.etRectY)
        val etRectW = dialogView.findViewById<EditText>(R.id.etRectW)
        val etRectH = dialogView.findViewById<EditText>(R.id.etRectH)

        etRectX.setText(viewModel.roiX.toString())
        etRectY.setText(viewModel.roiY.toString())
        etRectW.setText(if (viewModel.roiW > 0) viewModel.roiW.toString() else imgW.toString())
        etRectH.setText(if (viewModel.roiH > 0) viewModel.roiH.toString() else imgH.toString())

        etRectW.hint = "Max: $imgW"
        etRectH.hint = "Max: $imgH"

        val shapes = arrayOf("Rectangle / Square", "Circle", "Ellipse", "Triangle")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, shapes)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                layoutRect.visibility = if (pos == 0) View.VISIBLE else View.GONE
                layoutCircle.visibility = if (pos == 1) View.VISIBLE else View.GONE
                layoutEllipse.visibility = if (pos == 2) View.VISIBLE else View.GONE
                layoutTri.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Define Mathematical ROI")
            .setView(dialogView)
            .setPositiveButton("Apply", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            var finalX = 0; var finalY = 0; var finalW = 0; var finalH = 0
            var requiresMask = false

            val maskBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(maskBitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }

            when (spinner.selectedItemPosition) {
                0 -> {
                    finalX = etRectX.text.toString().toIntOrNull() ?: 0
                    finalY = etRectY.text.toString().toIntOrNull() ?: 0
                    finalW = etRectW.text.toString().toIntOrNull() ?: 0
                    finalH = etRectH.text.toString().toIntOrNull() ?: 0
                    viewModel.roiMaskBytes = null
                }
                1 -> {
                    requiresMask = true
                    val cx = dialogView.findViewById<EditText>(R.id.etCircCx).text.toString().toFloatOrNull() ?: (imgW/2f)
                    val cy = dialogView.findViewById<EditText>(R.id.etCircCy).text.toString().toFloatOrNull() ?: (imgH/2f)
                    val r = dialogView.findViewById<EditText>(R.id.etCircR).text.toString().toFloatOrNull() ?: 100f

                    canvas.drawCircle(cx, cy, r, paint)

                    finalX = (cx - r).toInt()
                    finalY = (cy - r).toInt()
                    finalW = (r * 2).toInt()
                    finalH = (r * 2).toInt()
                }
                2 -> {
                    requiresMask = true
                    val cx = dialogView.findViewById<EditText>(R.id.etEllCx).text.toString().toFloatOrNull() ?: (imgW/2f)
                    val cy = dialogView.findViewById<EditText>(R.id.etEllCy).text.toString().toFloatOrNull() ?: (imgH/2f)
                    val rx = dialogView.findViewById<EditText>(R.id.etEllRx).text.toString().toFloatOrNull() ?: 150f
                    val ry = dialogView.findViewById<EditText>(R.id.etEllRy).text.toString().toFloatOrNull() ?: 100f

                    canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)

                    finalX = (cx - rx).toInt()
                    finalY = (cy - ry).toInt()
                    finalW = (rx * 2).toInt()
                    finalH = (ry * 2).toInt()
                }
                3 -> {
                    requiresMask = true
                    val x1 = dialogView.findViewById<EditText>(R.id.etTriX1).text.toString().toFloatOrNull() ?: 0f
                    val y1 = dialogView.findViewById<EditText>(R.id.etTriY1).text.toString().toFloatOrNull() ?: 0f
                    val x2 = dialogView.findViewById<EditText>(R.id.etTriX2).text.toString().toFloatOrNull() ?: 0f
                    val y2 = dialogView.findViewById<EditText>(R.id.etTriY2).text.toString().toFloatOrNull() ?: 0f
                    val x3 = dialogView.findViewById<EditText>(R.id.etTriX3).text.toString().toFloatOrNull() ?: 0f
                    val y3 = dialogView.findViewById<EditText>(R.id.etTriY3).text.toString().toFloatOrNull() ?: 0f

                    val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2); lineTo(x3, y3); close() }
                    canvas.drawPath(path, paint)

                    finalX = minOf(x1, x2, x3).toInt()
                    finalY = minOf(y1, y2, y3).toInt()
                    finalW = (maxOf(x1, x2, x3) - finalX).toInt()
                    finalH = (maxOf(y1, y2, y3) - finalY).toInt()
                }
            }

            if (finalX < 0 || finalY < 0 || (finalX + finalW) > imgW || (finalY + finalH) > imgH || finalW <= 0 || finalH <= 0) {
                val errorMsg = if (finalW <= 0 || finalH <= 0) {
                    "Dimensions must be positive!"
                } else {
                    "Shape is out of bounds!\nMax Size: ${imgW}x${imgH}.\nYour Shape Ends at: ${finalX+finalW}x${finalY+finalH}"
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            } else {
                if (requiresMask) {
                    val stream = java.io.ByteArrayOutputStream()
                    maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    viewModel.roiMaskBytes = stream.toByteArray()
                    tvInstruction.text = "✅ Complex Shape ROI Applied!"
                } else {
                    tvInstruction.text = "✅ Rectangular ROI Set: $finalW x $finalH px"
                }

                viewModel.roiX = finalX; viewModel.roiY = finalY
                viewModel.roiW = finalW; viewModel.roiH = finalH
                viewModel.hasCustomRoi = true

                checkReady()
                dialog.dismiss()
            }
        }
    }
}