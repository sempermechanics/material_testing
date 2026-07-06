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
import androidx.appcompat.app.AlertDialog // Added for Logout Popup
import androidx.appcompat.app.AppCompatActivity
import java.io.File

// --- ROI DRAWING ENGINE IMPORTS ---
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.auth.auth // Added for Session Destruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.activity.OnBackPressedCallback
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.auth.auth

// --- Material controls + motion for the redesigned UI ---
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.button.MaterialButtonToggleGroup
import com.rafad.indicvisiondic.ui.Motion
import com.rafad.indicvisiondic.ui.Insets


// Payload for Supabase 'analysis_sessions' table
@Serializable
data class AnalysisSessionInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("user_email") val userEmail: String, // 🚀 NEW!
    @SerialName("specimen_identifier") val specimenIdentifier: String,
    @SerialName("points_converged") val pointsConverged: Int,
    @SerialName("avg_iterations") val avgIterations: Float,
    @SerialName("execution_time_ms") val executionTimeMs: Int
)
@Serializable
data class AnalysisSessionResponse(
    @SerialName("session_id") val sessionId: String
)
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
    private lateinit var etSubsetSize: Slider
    private lateinit var etStepSize: Slider
    private lateinit var etStrainWindow: Slider
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTimer: TextView
    private lateinit var btnCalculateFullField: Button
    private lateinit var switchBlur: SwitchMaterial
    private lateinit var rgStrainMethod: MaterialButtonToggleGroup
    private lateinit var rgInterpolator: MaterialButtonToggleGroup // 🚀 ADDED
    private lateinit var btnViewResults: Button
    private lateinit var btnLogout: Button // Added for Secure Exit

    // Live value labels + collapsible parameters card
    private lateinit var tvSubsetValue: TextView
    private lateinit var tvStepValue: TextView
    private lateinit var tvStrainValue: TextView
    private lateinit var tvParamsSummary: TextView
    private lateinit var advancedCard: android.view.ViewGroup
    private lateinit var advancedHeader: View
    private lateinit var advancedContent: View
    private lateinit var ivAdvancedChevron: ImageView
    private var advancedExpanded = false

    // State
    private var isProcessing = false
    private var processingStartTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_static_analysis)
        // --- BACK BUTTON INTERCEPTOR (SAFETY LOCK) ---
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isProcessing) {
                    // Block the back button completely if the C++ engine is running
                    Toast.makeText(this@StaticAnalysisActivity, "Analysis running! Please wait or cancel first.", Toast.LENGTH_SHORT).show()
                } else {
                    // Show a warning popup before destroying the setup
                    AlertDialog.Builder(this@StaticAnalysisActivity)
                        .setTitle("Exit inDIC Engine?")
                        .setMessage("Are you sure you want to leave? All uncalculated setup and ROI definitions will be lost.")
                        .setPositiveButton("Exit") { _, _ ->
                            finish() // Actually close the screen
                        }
                        .setNegativeButton("Cancel", null) // Do nothing, stay on screen
                        .show()
                }
            }
        })
        // ---------------------------------------------

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
        rgInterpolator = findViewById(R.id.rgInterpolator) // 🚀 BOUND
        btnViewResults = findViewById(R.id.btnViewResults)
        btnLogout = findViewById(R.id.btnLogout) // Bind Logout Button

        // --- Redesigned parameter panel: live labels + collapsible card ---
        tvSubsetValue = findViewById(R.id.tvSubsetValue)
        tvStepValue = findViewById(R.id.tvStepValue)
        tvStrainValue = findViewById(R.id.tvStrainValue)
        tvParamsSummary = findViewById(R.id.tvParamsSummary)
        advancedCard = findViewById(R.id.advancedCard)
        advancedHeader = findViewById(R.id.advancedHeader)
        advancedContent = findViewById(R.id.advancedContent)
        ivAdvancedChevron = findViewById(R.id.ivAdvancedChevron)
        setupParameterControls()

        // Edge-to-edge (targetSdk 36): push the app bar below the status bar
        // and lift the scrollable content above the nav-bar gesture area so
        // the top controls aren't in the system swipe-down zone.
        Insets.padTop(findViewById(R.id.toolbar))
        Insets.padBottom(findViewById(R.id.contentColumn))

        // Gentle entrance: cards cascade in on first show only (not on rotation)
        if (savedInstanceState == null) {
            Motion.enterStaggered(findViewById(R.id.contentColumn))
        }

        restoreUiFromViewModel()

        // --- SECURE EXIT LISTENER ---
        btnLogout.setOnClickListener {
            if (isProcessing) {
                Toast.makeText(this, "Please wait for analysis to finish before logging out.", Toast.LENGTH_SHORT).show()
            } else {
                showLogoutConfirmation()
            }
        }

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

                    // 🚀 PIPELINE FIX: Actually read the mask file sent by RoiDrawActivity!
                    val maskPath = data.getStringExtra("MASK_FILE_PATH")
                    if (maskPath != null) {
                        val file = File(maskPath)
                        if (file.exists()) {
                            viewModel.roiMaskBytes = file.readBytes()
                        }
                    }

                    // 🚀 FIX FULL IMAGE OVERRIDE: If it's exactly the image bounds, unset custom ROI
                    if (viewModel.roiW == viewModel.realRefWidth && viewModel.roiH == viewModel.realRefHeight) {
                        viewModel.hasCustomRoi = false
                        tvInstruction.text = "✅ Full Image Analysis Set"
                    } else {
                        viewModel.hasCustomRoi = true
                        tvInstruction.text = "✅ ROI Set: ${viewModel.roiW} x ${viewModel.roiH} px"
                    }

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

    // ==========================================
    // --- SECURE EXIT PROTOCOL FUNCTIONS ---
    // ==========================================

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Log Out?")
            .setMessage("Are you sure you want to log out of inDIC on this device?")
            .setPositiveButton("Log Out") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        // Show loading state on the button
        btnLogout.text = "Logging out..."
        btnLogout.isEnabled = false

        lifecycleScope.launch {
            try {
                // 1. Destroy the session on the server and local vault
                SupabaseManager.client.auth.signOut()
            } catch (e: Exception) {
                // Force exit even if network fails
                Log.e("inDIC_Auth", "Server logout failed, forcing local exit.", e)
            } finally {
                // 2. Burn the bridge and route back to Zone 2 (AuthActivity)
                val intent = Intent(this@StaticAnalysisActivity, AuthActivity::class.java)
                intent.putExtra("ROUTING_ERROR", "You have been successfully logged out.")

                // CRITICAL: Wipe the backstack so they can't press 'Back' to return to the engine
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    // ==========================================
    // --- NATIVE ENGINE FUNCTIONS (UNTOUCHED) ---
    // ==========================================

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

    private fun currentSubsetSize(): Int = etSubsetSize.value.toInt()
    private fun currentStepSize(): Int = etStepSize.value.toInt()
    private fun currentStrainWindow(): Int = etStrainWindow.value.toInt()
    private fun currentUseNlvc(): Boolean = rgStrainMethod.checkedButtonId == R.id.rbNlvc
    private fun currentUseKeysInterpolator(): Boolean = rgInterpolator.checkedButtonId == R.id.rbKeys

    private fun startBatchAnalysis() {
        if (!viewModel.isReadyToCompute()) return

        val subset = currentSubsetSize()
        val step = currentStepSize()
        val strainWin = currentStrainWindow()

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

        // Disable logout during heavy C++ processing to prevent memory leaks/crashes
        btnLogout.isEnabled = false

        processingStartTime = System.currentTimeMillis()

        val batchDir = File(cacheDir, "batch_results")
        if (!batchDir.exists()) batchDir.mkdirs()
        batchDir.listFiles()?.forEach { it.delete() }

        viewModel.lastBatchDirPath = batchDir.absolutePath
        viewModel.lastStep = step

        val applyBlur = switchBlur.isChecked
        val useNlvc = currentUseNlvc()
        val use6x6 = currentUseKeysInterpolator()
        val maskData = viewModel.roiMaskBytes ?: ByteArray(0)

        val debugDir = File(cacheDir, "dic_debug")
        if (!debugDir.exists()) debugDir.mkdirs()
        IndicVisionNativeLib.setDebugOutputDir(debugDir.absolutePath)

        lifecycleScope.launch(viewModel.nativeExecutor.asCoroutineDispatcher()) {
            try {
                val totalFrames = viewModel.defFilePaths.size
                val refBytes = viewModel.refBytes ?: throw IllegalStateException("Reference missing")

                var firstFrameValidPoints = 0
                var firstFrameAvgIters = 0.0f
                var engineErrorCode = 0 // 🚀 PRIORITY 3: Track the negative return codes

                runOnUiThread { tvTimer.text = "Caching Reference in Native Engine..." }
                // 🚀 DICe PARITY: Pass the maskData (or empty array) to build the Ghost Wall globally!
                val safeMaskData = viewModel.roiMaskBytes ?: ByteArray(0)
                IndicVisionNativeLib.initializeReference(refBytes, safeMaskData, viewModel.realRefWidth, viewModel.realRefHeight, applyBlur)

                val gridW = finalRectW / step
                val gridH = finalRectH / step
                val maxPoints = gridW * gridH
                val byteCapacity = maxPoints * 8 * 4
                val outputBuffer = java.nio.ByteBuffer.allocateDirect(byteCapacity)
                outputBuffer.order(java.nio.ByteOrder.nativeOrder())

                for ((frameIndex, defPath) in viewModel.defFilePaths.withIndex()) {
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
                    val metricsCatcher = FloatArray(16)

                    val validPointsCount = IndicVisionNativeLib.computeFullFieldDirect(
                        refBytes, defBytes, maskData,
                        finalRectX, finalRectY, finalRectW, finalRectH,
                        step, subset, strainWin, true, true, false, applyBlur, useNlvc,
                        use6x6, // 🚀 PASS TOGGLE TO JNI
                        outputBuffer, callback,
                        metricsCatcher
                    )

                    // 🚀 PRIORITY 3: Catch the fatal error and abort the batch loop immediately
                    if (validPointsCount < 0) {
                        engineErrorCode = validPointsCount
                        break
                    }

                    if (frameIndex == 0) {
                        firstFrameValidPoints = validPointsCount
                        viewModel.engineStatsArray = metricsCatcher.clone()
                        firstFrameAvgIters = metricsCatcher[8]
                    }

                    if (validPointsCount == 0) continue

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

                val executionTimeMs = (System.currentTimeMillis() - processingStartTime).toInt()
                val totalTime = executionTimeMs / 1000.0

                // 🚀 THE TRUE ADMIN STEALTH QUEUE (OFFLINE-FIRST)
                var generatedRefPath = ""
                var generatedDefPath = "" // 🚀 ADDED THIS

                if (firstFrameValidPoints > 0) {
                    withContext(Dispatchers.IO) {
                        Log.d("inDIC_Diag", "========================================")
                        Log.d("inDIC_Diag", "1. ENGINE FINISHED. PREPARING BATCH OFFLINE QUEUE.")

                        var generatedRefPath = ""
                        var refBmp: Bitmap? = null

                        try {
                            // 🚀 1. PROCESS THE REFERENCE IMAGE ONCE
                            refBmp = IndicVisionNativeLib.getPreviewFromBytes(refBytes, viewModel.realRefWidth)
                            val refPngFile = File(cacheDir, "temp_ref_${System.currentTimeMillis()}.png")
                            refPngFile.outputStream().use { out ->
                                refBmp?.compress(Bitmap.CompressFormat.PNG, 100, out)
                            }
                            generatedRefPath = refPngFile.absolutePath

                            // 🚀 THE FIX: Save this dynamic path so the Result Viewer knows where it is!
                            viewModel.lastRefPath = generatedRefPath

                            // 🚀 SAFE OFFLINE AUTH CHECK (Runs once for the batch)
                            val currentUser = SupabaseManager.client.auth.currentUserOrNull()
                            val userEmail = currentUser?.email ?: "Offline_User"
                            val userId = currentUser?.id ?: "Offline_ID"

                            // 🚀 2. LOOP THROUGH EVERY DEFORMED IMAGE IN THE BATCH
                            for ((frameIndex, rawDefPath) in viewModel.defFilePaths.withIndex()) {

                                var generatedDefPath = ""
                                var defBmp: Bitmap? = null

                                try {
                                    // 🛡️ MEMORY SHIELD: Process this specific frame
                                    val rawFile = File(rawDefPath)
                                    if (rawFile.exists() && rawFile.length() < 50_000_000) {
                                        val defBytes = rawFile.readBytes()
                                        defBmp = IndicVisionNativeLib.getPreviewFromBytes(defBytes, viewModel.realRefWidth)

                                        val defPngFile = File(cacheDir, "temp_def_${System.currentTimeMillis()}_frame_$frameIndex.png")
                                        defPngFile.outputStream().use { out ->
                                            defBmp?.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        }
                                        generatedDefPath = defPngFile.absolutePath
                                    }

                                    // Grab the correct math data for THIS specific frame
                                    val datFile = File(batchDir, String.format("frame_%04d.dat", frameIndex))

                                    // Assign a unique session ID for this specific frame
                                    viewModel.currentSessionId = "Pending_Cloud_Sync_" + java.util.UUID.randomUUID().toString().take(8)

                                    // 🚀 QUEUE THE WORKER FOR THIS SPECIFIC FRAME
                                    val uploadData = androidx.work.Data.Builder()
                                        .putString("USER_ID", userId)
                                        .putString("USER_EMAIL", userEmail)
                                        .putString("REF_PATH", generatedRefPath)
                                        .putString("DEF_PATH", generatedDefPath)
                                        .putString("DAT_PATH", datFile.absolutePath)
                                        .putString("FRAME_NAME", "Frame_${frameIndex + 1}") // e.g., Frame_1, Frame_2...
                                        .putString("REF_NAME", viewModel.refName.removePrefix("Ref: "))
                                        .putInt("IMG_W", viewModel.realRefWidth)
                                        .putInt("IMG_H", viewModel.realRefHeight)
                                        .putInt("STEP", step)
                                        .putInt("SUBSET", subset)
                                        .putInt("STRAIN_WIN", strainWin)
                                        .putString("STRAIN_METHOD", if (useNlvc) "NLVC" else "VSG")
                                        .putInt("ROI_X", finalRectX)
                                        .putInt("ROI_Y", finalRectY)
                                        .putInt("ROI_W", finalRectW)
                                        .putInt("ROI_H", finalRectH)
                                        .putFloatArray("ENGINE_STATS", viewModel.engineStatsArray ?: FloatArray(16))
                                        .putInt("POINTS_CONVERGED", firstFrameValidPoints)
                                        .putFloat("AVG_ITERS", firstFrameAvgIters)
                                        .putInt("EXEC_TIME", executionTimeMs)
                                        .build()

                                    val uploadWork = androidx.work.OneTimeWorkRequestBuilder<DicUploadWorker>()
                                        .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                                        .setInputData(uploadData)
                                        .build()

                                    androidx.work.WorkManager.getInstance(applicationContext).enqueue(uploadWork)
                                    Log.d("inDIC_Diag", "-> SUCCESS! Worker queued for Frame ${frameIndex + 1}.")

                                } finally {
                                    // EXTREMELY CRITICAL: Recycle the deformed image RAM immediately before the loop moves to the next frame
                                    defBmp?.recycle()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("inDIC_Diag", "❌ LOCAL CATCH: Failed to enqueue batch workers", e)
                        } finally {
                            // Recycle the reference image once the entire loop is finished
                            refBmp?.recycle()
                            Log.d("inDIC_Diag", "========================================")
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    isProcessing = false
                    progressBar.visibility = View.GONE
                    btnLogout.isEnabled = true

                    // 🚀 PRIORITY 3: Handle the UI Contract based on the specific error
                    if (engineErrorCode < 0) {
                        val errorMsg = when(engineErrorCode) {
                            -1 -> "Feature Extraction Failed (AKAZE). The speckle pattern might be too fine, out of focus, or destroyed by scaling."
                            -2 -> "Invalid ROI. The mask excluded the entire specimen (0 valid points)."
                            -3 -> "Engine Initialization Failed (Null Pointers or Corrupt Image)."
                            else -> "Unknown Engine Error ($engineErrorCode)"
                        }
                        tvTimer.text = "Analysis Aborted"
                        tvResult.text = "❌ Error: $errorMsg"

                        android.app.AlertDialog.Builder(this@StaticAnalysisActivity)
                            .setTitle("Analysis Failed")
                            .setMessage(errorMsg)
                            .setPositiveButton("OK", null)
                            .show()

                    } else if (firstFrameValidPoints <= 0) {
                        tvTimer.text = "Analysis Failed"
                        tvResult.text = "❌ Engine returned no data"
                    } else {
                        tvTimer.text = "Batch Done in %.2f s".format(totalTime)
                        tvResult.text = "✅ Computed $totalFrames frames!"

                        viewModel.lastDefPath = viewModel.defFilePaths.firstOrNull() ?: ""
                        viewModel.lastBatchDirPath = batchDir.absolutePath

                        viewModel.hasCompletedAnalysis = true
                        checkReady()

                        // 🚀 FIRING EXACTLY ONCE!
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
                    btnLogout.isEnabled = true
                    checkReady()
                }
            }
        }
    }

    private fun openResultViewer() {
        val intent = Intent(this, ResultViewerActivity::class.java).apply {
            putExtra("IMG_W", viewModel.realRefWidth)
            putExtra("IMG_H", viewModel.realRefHeight)
            putExtra("STEP", viewModel.lastStep)
            putExtra("REF_NAME", viewModel.refName.removePrefix("Ref: "))

            // 🚀 THE FIX: Pass the dynamic timestamped path stored in the ViewModel
            putExtra("REF_PATH", viewModel.lastRefPath ?: "")

            putExtra("DEF_PATH", viewModel.lastDefPath)
            putExtra("BATCH_DIR_PATH", viewModel.lastBatchDirPath)
            putStringArrayListExtra("DEF_FILE_NAMES", ArrayList(viewModel.defFilePaths.map { it.substringAfterLast('/') }))

            // 🚀 PDF GENERATOR DATA
            putExtra("SESSION_ID", viewModel.currentSessionId)
            putExtra("SUBSET_SIZE", currentSubsetSize())
            putExtra("STRAIN_WINDOW", currentStrainWindow())
            putExtra("STRAIN_METHOD", if (currentUseNlvc()) "NLVC" else "VSG")
            putExtra("ENGINE_STATS", viewModel.engineStatsArray)

            // 🚀 NEW: PASSING ROI DATA FOR THE PDF REPORT
            putExtra("ROI_X", viewModel.roiX)
            putExtra("ROI_Y", viewModel.roiY)
            putExtra("ROI_W", viewModel.roiW)
            putExtra("ROI_H", viewModel.roiH)
        }
        startActivity(intent)
    }

    private fun handleMaskSelection(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                viewModel.roiMaskBytes = stream.readBytes()
                Toast.makeText(this, "Custom ROI Mask Uploaded!", Toast.LENGTH_SHORT).show()
                btnLoadRoiMask.text = "Mask Uploaded ✅"
                // Success state via the design-system color, keeping the
                // outlined Material shape intact.
                (btnLoadRoiMask as? com.google.android.material.button.MaterialButton)?.let {
                    it.setStrokeColorResource(R.color.semantic_success)
                    it.setTextColor(getColor(R.color.semantic_success))
                }
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

    // ------------------------------------------------------------------
    // Redesigned parameter panel: sliders with live labels + a
    // collapsible "Analysis Parameters" card. Values are read via
    // slider.value everywhere the old EditTexts were parsed.
    // ------------------------------------------------------------------
    private fun setupParameterControls() {
        advancedHeader.setOnClickListener { toggleAdvanced() }

        val updateLabels = {
            val subset = etSubsetSize.value.toInt()
            val step = etStepSize.value.toInt()
            val win = etStrainWindow.value.toInt()
            tvSubsetValue.text = "$subset px"
            tvStepValue.text = "$step px"
            tvStrainValue.text = "$win px"
            // Collapsed-state summary so users see settings at a glance
            tvParamsSummary.text = "Subset $subset · Step $step · Window $win"
        }
        updateLabels()

        etSubsetSize.addOnChangeListener { _, _, _ -> updateLabels() }
        etStepSize.addOnChangeListener { _, _, _ -> updateLabels() }
        etStrainWindow.addOnChangeListener { _, _, _ -> updateLabels() }
    }

    private fun toggleAdvanced() {
        advancedExpanded = !advancedExpanded
        // Tween the card's bounds while the content fades in/out
        Motion.animateExpandCollapse(advancedCard)
        advancedContent.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        ivAdvancedChevron.animate()
            .rotation(if (advancedExpanded) 180f else 0f)
            .setDuration(240)
            .start()
    }

    private fun checkReady() {
        val ready = viewModel.isReadyToCompute()
        btnCalculateFullField.isEnabled = ready && !isProcessing
        btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        btnManualRoi.isEnabled = !isProcessing
        btnLoadRef.isEnabled = !isProcessing
        btnLoadDef.isEnabled = !isProcessing
        btnViewResults.visibility = if (viewModel.hasCompletedAnalysis && !isProcessing) View.VISIBLE else View.GONE
        // Enabled/disabled visuals are handled by the Material theme —
        // no more hand-painted setBackgroundColor state juggling.
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
    private fun loadHardcodedDebugImage() {
        try {
            // LOAD THE LOSSLESS PNG!
            val inputStream = assets.open("oht_cfrp_00.png")
            val bytes = inputStream.readBytes()
            inputStream.close()

            // ==========================================
            // 🛑 KOTLIN INTERCEPT DUMP
            // ==========================================
            val sb = java.lang.StringBuilder("KOTLIN BYTE DUMP: ")
            for (i in 0 until 10) {
                // Convert signed byte to unsigned int (0-255) for accurate printing
                val unsignedVal = bytes[i].toInt() and 0xFF
                sb.append("$unsignedVal ")
            }
            Log.d("IndicVisionJNI", sb.toString())
            // ==========================================

            viewModel.realRefWidth = 400
            viewModel.realRefHeight = 1040
            viewModel.refBytes = bytes
            viewModel.refName = "Ref: oht_cfrp_00.png"

            IndicVisionNativeLib.initializeReference(bytes, ByteArray(0), 400, 1040, false)
            Toast.makeText(this, "Raw PNG Loaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                    finalY = (cy - rx).toInt()
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