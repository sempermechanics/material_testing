package com.rafad.indicvisiondic.ui.analysis
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.IndicVisionNativeLib
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.SupabaseManager
import com.rafad.indicvisiondic.ui.auth.AuthActivity
import com.rafad.indicvisiondic.ui.viewer.ResultViewerActivity

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
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

import androidx.activity.OnBackPressedCallback
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.button.MaterialButtonToggleGroup
import com.rafad.indicvisiondic.ui.Motion
import com.rafad.indicvisiondic.ui.Insets


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

    // Prominent progress overlay (compute + video extraction)
    private lateinit var computeOverlay: View
    private lateinit var overlayTitle: TextView
    private lateinit var overlayProgress: ProgressBar
    private lateinit var overlayPercent: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var overlayElapsed: TextView
    private val elapsedHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val elapsedTicker = object : Runnable {
        override fun run() {
            val secs = (System.currentTimeMillis() - processingStartTime) / 1000
            overlayElapsed.text = "Elapsed ${secs}s"
            elapsedHandler.postDelayed(this, 1000)
        }
    }
    private lateinit var switchBlur: SwitchMaterial
    private lateinit var rgStrainMethod: MaterialButtonToggleGroup
    private lateinit var rgInterpolator: MaterialButtonToggleGroup // 🚀 ADDED
    private lateinit var btnViewResults: Button
    private lateinit var btnLogout: Button // Added for Secure Exit

    // Live value labels for the parameter sliders
    private lateinit var tvSubsetValue: TextView
    private lateinit var tvStepValue: TextView
    private lateinit var tvStrainValue: TextView

    // Two-step wizard: page 1 = load images, page 2 = settings + run
    private lateinit var scrollStepImages: View
    private lateinit var scrollStepSettings: View
    private lateinit var tvStepChip1: TextView
    private lateinit var tvStepChip2: TextView
    private lateinit var btnNext: Button
    private lateinit var btnBack: Button

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
                    Toast.makeText(this@StaticAnalysisActivity, R.string.analysis_running_back_blocked, Toast.LENGTH_SHORT).show()
                } else if (viewModel.wizardStep == 2) {
                    // On the settings page, back returns to the images page
                    goToStep(1, animate = true)
                } else if (viewModel.refBytes != null || viewModel.defFilePaths.isNotEmpty()) {
                    AlertDialog.Builder(this@StaticAnalysisActivity)
                        .setTitle(R.string.exit_indic_title)
                        .setMessage(R.string.exit_indic_message)
                        .setPositiveButton(R.string.exit) { _, _ ->
                            finish()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    finish()
                }
            }
        })
        // ---------------------------------------------

        // Bind UI Components
        progressBar = findViewById(R.id.pbAnalysis)
        tvTimer = findViewById(R.id.tvTimer)
        computeOverlay = findViewById(R.id.computeOverlay)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlayProgress = findViewById(R.id.overlayProgress)
        overlayPercent = findViewById(R.id.overlayPercent)
        overlayStatus = findViewById(R.id.overlayStatus)
        overlayElapsed = findViewById(R.id.overlayElapsed)
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

        // --- Parameter sliders: live value labels ---
        tvSubsetValue = findViewById(R.id.tvSubsetValue)
        tvStepValue = findViewById(R.id.tvStepValue)
        tvStrainValue = findViewById(R.id.tvStrainValue)
        setupParameterControls()

        // --- Two-step wizard wiring ---
        scrollStepImages = findViewById(R.id.scrollStepImages)
        scrollStepSettings = findViewById(R.id.scrollStepSettings)
        tvStepChip1 = findViewById(R.id.tvStepChip1)
        tvStepChip2 = findViewById(R.id.tvStepChip2)
        btnNext = findViewById(R.id.btnNext)
        btnBack = findViewById(R.id.btnBack)

        btnNext.setOnClickListener { goToStep(2, animate = true) }
        btnBack.setOnClickListener { goToStep(1, animate = true) }
        goToStep(viewModel.wizardStep, animate = false)

        // Edge-to-edge (targetSdk 36): push the app bar below the status bar
        // and keep the wizard nav above the nav-bar gesture area so the top
        // controls aren't in the system swipe-down zone.
        Insets.padTop(findViewById(R.id.toolbar))
        Insets.padBottom(findViewById(R.id.bottomNav))

        // Gentle entrance: cards cascade in on first show only (not on rotation)
        if (savedInstanceState == null) {
            Motion.enterStaggered(findViewById(R.id.contentColumn))
        }

        restoreUiFromViewModel()

        // --- SECURE EXIT LISTENER ---
        btnLogout.setOnClickListener {
            if (isProcessing) {
                Toast.makeText(this, R.string.logout_wait_analysis, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, R.string.no_images_selected, Toast.LENGTH_SHORT).show()
            }
        }

        val pickMask = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleMaskSelection(it) }
        }

        // Video picker: frame 0 → reference, remaining sampled frames → deformed
        val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { handleVideo(it) }
        }

        val roiStudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    viewModel.roiX = data.getIntExtra(DicKeys.ROI_X, 0)
                    viewModel.roiY = data.getIntExtra(DicKeys.ROI_Y, 0)
                    viewModel.roiW = data.getIntExtra(DicKeys.ROI_W, viewModel.realRefWidth)
                    viewModel.roiH = data.getIntExtra(DicKeys.ROI_H, viewModel.realRefHeight)

                    // 🚀 PIPELINE FIX: Actually read the mask file sent by RoiDrawActivity!
                    val maskPath = data.getStringExtra(DicKeys.MASK_FILE_PATH)
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
        findViewById<Button>(R.id.btnLoadVideo).setOnClickListener { pickVideo.launch("video/*") }

        btnDefineRoi.setOnClickListener {
            if (viewModel.refBytes != null) {
                val tempFile = File(cacheDir, "temp_roi_ref.bin")
                try {
                    tempFile.writeBytes(viewModel.refBytes!!)
                    val intent = Intent(this, RoiDrawActivity::class.java)
                    intent.putExtra(DicKeys.IMAGE_FILE_PATH, tempFile.absolutePath)
                    intent.putExtra(DicKeys.IMAGE_WIDTH, viewModel.realRefWidth)
                    intent.putExtra(DicKeys.IMAGE_HEIGHT, viewModel.realRefHeight)
                    roiStudioLauncher.launch(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, R.string.failed_save_temp_file, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, R.string.load_image_first, Toast.LENGTH_SHORT).show()
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
            .setTitle(R.string.logout_title)
            .setMessage(R.string.logout_message)
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
                intent.putExtra(DicKeys.ROUTING_ERROR, "You have been successfully logged out.")

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
            Toast.makeText(this, R.string.jpeg_warning_ref, Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this, R.string.failed_decode_raw, Toast.LENGTH_SHORT).show()
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
        } catch (e: Exception) {
            Log.e("StaticAnalysisActivity", "Failed to load reference image", e)
            Toast.makeText(this, R.string.failed_load_reference, Toast.LENGTH_LONG).show()
        }
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
                            Toast.makeText(this@StaticAnalysisActivity, R.string.jpeg_warning_batch, Toast.LENGTH_LONG).show()
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
                    Toast.makeText(this@StaticAnalysisActivity, getString(R.string.error_loading_images, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Video input. Frame 0 of the chosen segment becomes the reference;
    // the rest become the deformed sequence, feeding the exact same
    // refBytes / defFilePaths state as the image flow.
    //
    // Step 1: read metadata → show resolution/fps/length + sampling options.
    // Step 2: extract at the chosen frame rate over the chosen time segment.
    // ------------------------------------------------------------------
    private data class VideoMeta(
        val durationMs: Long,
        val fps: Double,
        val fpsKnown: Boolean,
        val width: Int,
        val height: Int
    )

    private fun formatClock(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun handleVideo(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            var meta = VideoMeta(0L, 30.0, false, 0, 0)
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@StaticAnalysisActivity, uri)
                fun m(key: Int) = retriever.extractMetadata(key)
                val durationMs = m(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var w = m(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = m(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = m(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t } // display orientation
                val frameCountMeta = m(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                var fps = 30.0
                var fpsKnown = false
                if (frameCountMeta != null && frameCountMeta > 0 && durationMs > 0) {
                    fps = frameCountMeta / (durationMs / 1000.0)
                    fpsKnown = true
                }
                meta = VideoMeta(durationMs, fps, fpsKnown, w, h)
            } catch (e: Exception) {
                Log.e("StaticAnalysis", "Video metadata read failed", e)
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }

            if (meta.durationMs <= 0L) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StaticAnalysisActivity, R.string.video_read_failed, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) { showVideoSamplingDialog(uri, meta) }
        }
    }

    /** Sampling by extraction frame rate + time segment, with a metadata summary. */
    private fun showVideoSamplingDialog(uri: Uri, meta: VideoMeta) {
        val view = layoutInflater.inflate(R.layout.dialog_video_sampling, null)
        val tvInfo = view.findViewById<TextView>(R.id.tvVideoInfo)
        val sliderFps = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderFps)
        val tvFps = view.findViewById<TextView>(R.id.tvFpsValue)
        val range = view.findViewById<com.google.android.material.slider.RangeSlider>(R.id.rangeSegment)
        val tvSegment = view.findViewById<TextView>(R.id.tvSegmentValue)
        val tvEstimate = view.findViewById<TextView>(R.id.tvEstimate)

        // --- Metadata summary: only show parts the file actually reported ---
        val info = mutableListOf<String>()
        if (meta.width > 0 && meta.height > 0) info.add("${meta.width}×${meta.height}")
        if (meta.fpsKnown) info.add("%.0f fps".format(meta.fps))
        info.add(formatClock(meta.durationMs))
        tvInfo.text = info.joinToString("   ·   ")

        // --- Frame-rate selector (capped at the source rate when known) ---
        val maxFps = (if (meta.fpsKnown) Math.ceil(meta.fps).toInt() else 30).coerceIn(2, 60)
        sliderFps.valueFrom = 1f
        sliderFps.valueTo = maxFps.toFloat()
        sliderFps.value = minOf(10, maxFps).toFloat()
        tvFps.text = "${sliderFps.value.toInt()} fps"

        // --- Time-segment selector (seconds) ---
        val durationSec = (meta.durationMs / 1000.0).toFloat().coerceAtLeast(0.1f)
        range.valueFrom = 0f
        range.valueTo = durationSec
        range.values = listOf(0f, durationSec)
        tvSegment.text = "${formatClock(0)} – ${formatClock(meta.durationMs)}"

        val maxFrames = 300
        fun estimate(): Int {
            val startS = range.values.first()
            val endS = range.values.last()
            val segSec = (endS - startS).coerceAtLeast(0f)
            return (segSec * sliderFps.value + 1f).toInt().coerceIn(1, maxFrames)
        }
        fun refreshEstimate() {
            val n = estimate()
            val capped = if (n >= maxFrames) " (capped)" else ""
            tvEstimate.text = "≈ $n frame(s): 1 reference + ${(n - 1).coerceAtLeast(0)} deformed$capped"
        }

        sliderFps.addOnChangeListener { _, v, _ -> tvFps.text = "${v.toInt()} fps"; refreshEstimate() }
        range.addOnChangeListener { s, _, _ ->
            val startMs = (s.values.first() * 1000).toLong()
            val endMs = (s.values.last() * 1000).toLong()
            tvSegment.text = "${formatClock(startMs)} – ${formatClock(endMs)}"
            refreshEstimate()
        }
        refreshEstimate()

        AlertDialog.Builder(this)
            .setTitle(R.string.video_sampling_title)
            .setView(view)
            .setPositiveButton("Extract") { _, _ ->
                val fpsExtract = sliderFps.value.toDouble().coerceAtLeast(0.1)
                val startMs = (range.values.first() * 1000).toLong()
                val endMs = (range.values.last() * 1000).toLong()
                extractVideoFrames(uri, fpsExtract, startMs, endMs)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Extracts frames at [fpsExtract] over [startMs, endMs] with the progress overlay. */
    private fun extractVideoFrames(uri: Uri, fpsExtract: Double, startMs: Long, endMs: Long) {
        processingStartTime = System.currentTimeMillis()
        showComputeOverlay(title = "Extracting Frames", status = "Reading video…")

        lifecycleScope.launch(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@StaticAnalysisActivity, uri)

                val stepMs = 1000.0 / fpsExtract
                val maxFrames = 300
                val span = (endMs - startMs).coerceAtLeast(0L)
                val count = ((span / stepMs).toInt() + 1).coerceIn(1, maxFrames)

                val tempDir = File(cacheDir, "temp_deformed")
                if (!tempDir.exists()) tempDir.mkdirs()
                tempDir.listFiles()?.forEach { it.delete() }
                viewModel.clearPreviousResults()

                val defPaths = mutableListOf<String>()
                var refPreview: Bitmap? = null
                var firstDefPreview: Bitmap? = null

                for (i in 0 until count) {
                    val timeMs = startMs + i * stepMs
                    if (timeMs > endMs + stepMs / 2) break
                    val frame = retriever.getFrameAtTime(
                        (timeMs * 1000).toLong(), android.media.MediaMetadataRetriever.OPTION_CLOSEST
                    ) ?: continue

                    val png = java.io.ByteArrayOutputStream().use { out ->
                        frame.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.toByteArray()
                    }

                    if (i == 0) {
                        viewModel.realRefWidth = frame.width
                        viewModel.realRefHeight = frame.height
                        viewModel.refBytes = png
                        viewModel.refName = "Ref: video @ ${formatClock(startMs)}"
                        refPreview = frame
                        if (!viewModel.hasCustomRoi) {
                            viewModel.roiX = 0; viewModel.roiY = 0
                            viewModel.roiW = frame.width; viewModel.roiH = frame.height
                        }
                    } else {
                        val f = File(tempDir, String.format("%04d_frame.png", i))
                        f.writeBytes(png)
                        defPaths.add(f.absolutePath)
                        if (i == 1) firstDefPreview = frame
                    }

                    setComputeProgress((i + 1) * 100 / count)
                    setComputeStatus("Extracting frame ${i + 1} of $count")
                }

                if (viewModel.refBytes == null || defPaths.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        hideComputeOverlay()
                        Toast.makeText(this@StaticAnalysisActivity, R.string.video_extract_insufficient, Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                viewModel.defFilePaths = defPaths.sorted()

                withContext(Dispatchers.Main) {
                    hideComputeOverlay()
                    refPreview?.let { imgRef.setImageBitmap(it) }
                    firstDefPreview?.let { imgDef.setImageBitmap(it) }
                    tvRefName.text = viewModel.refName
                    tvDefName.text = viewModel.getDefDisplayName()
                    checkReady()
                    Toast.makeText(
                        this@StaticAnalysisActivity,
                        getString(R.string.video_loaded_frames, defPaths.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("StaticAnalysis", "Error extracting video frames", e)
                withContext(Dispatchers.Main) {
                    hideComputeOverlay()
                    Toast.makeText(this@StaticAnalysisActivity, getString(R.string.video_read_error, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                try { retriever.release() } catch (_: Exception) {}
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
            Toast.makeText(this, R.string.roi_too_small, Toast.LENGTH_LONG).show()
            return
        }

        isProcessing = true
        checkReady()
        processingStartTime = System.currentTimeMillis()
        showComputeOverlay()
        // Keep the legacy inline indicators in sync (hidden behind the overlay)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvTimer.visibility = View.VISIBLE
        tvTimer.text = "Initializing Engine..."

        // Disable logout during heavy C++ processing to prevent memory leaks/crashes
        btnLogout.isEnabled = false

        val applyBlur = switchBlur.isChecked
        val useNlvc = currentUseNlvc()
        val use6x6 = currentUseKeysInterpolator()
        val maskData = viewModel.roiMaskBytes ?: ByteArray(0)

        val debugDir = File(cacheDir, "dic_debug")
        if (!debugDir.exists()) debugDir.mkdirs()
        IndicVisionNativeLib.setDebugOutputDir(debugDir.absolutePath)

        lifecycleScope.launch(viewModel.nativeExecutor.asCoroutineDispatcher()) {
            try {
                val params = AnalysisViewModel.BatchAnalysisParams(
                    cacheDir = cacheDir,
                    subset = subset,
                    step = step,
                    strainWin = strainWin,
                    finalRectX = finalRectX,
                    finalRectY = finalRectY,
                    finalRectW = finalRectW,
                    finalRectH = finalRectH,
                    applyBlur = applyBlur,
                    useNlvc = useNlvc,
                    use6x6 = use6x6,
                    maskData = maskData,
                    debugDir = debugDir,
                    processingStartTime = processingStartTime
                )

                val outcome = viewModel.runBatchAnalysis(applicationContext, params) { progress ->
                    setComputeProgress(progress.percent)
                    setComputeStatus(progress.status)
                    runOnUiThread {
                        progressBar.progress = progress.percent
                        tvTimer.text = progress.timerText
                    }
                }

                val totalTime = outcome.executionTimeMs / 1000.0

                withContext(Dispatchers.Main) {
                    isProcessing = false
                    hideComputeOverlay()
                    progressBar.visibility = View.GONE
                    btnLogout.isEnabled = true

                    if (outcome.engineErrorCode < 0) {
                        val errorMsg = when (outcome.engineErrorCode) {
                            -1 -> "Feature Extraction Failed (AKAZE). The speckle pattern might be too fine, out of focus, or destroyed by scaling."
                            -2 -> "Invalid ROI. The mask excluded the entire specimen (0 valid points)."
                            -3 -> "Engine Initialization Failed (Null Pointers or Corrupt Image)."
                            else -> "Unknown Engine Error (${outcome.engineErrorCode})"
                        }
                        tvTimer.text = "Analysis Aborted"
                        tvResult.text = "❌ Error: $errorMsg"

                        android.app.AlertDialog.Builder(this@StaticAnalysisActivity)
                            .setTitle(R.string.analysis_failed_title)
                            .setMessage(errorMsg)
                            .setPositiveButton("OK", null)
                            .show()
                    } else if (outcome.firstFrameValidPoints <= 0) {
                        tvTimer.text = "Analysis Failed"
                        tvResult.text = "❌ Engine returned no data"
                    } else {
                        tvTimer.text = "Batch Done in %.2f s".format(totalTime)
                        tvResult.text = "✅ Computed ${outcome.totalFrames} frames!"

                        viewModel.lastDefPath = viewModel.defFilePaths.firstOrNull() ?: ""
                        viewModel.lastBatchDirPath = outcome.batchDirPath
                        viewModel.hasCompletedAnalysis = true
                        checkReady()
                        openResultViewer()
                    }
                }
            } catch (e: Exception) {
                Log.e("StaticAnalysis", "Batch processing failed", e)
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    hideComputeOverlay()
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
            putExtra(DicKeys.IMG_W, viewModel.realRefWidth)
            putExtra(DicKeys.IMG_H, viewModel.realRefHeight)
            putExtra(DicKeys.STEP, viewModel.lastStep)
            putExtra(DicKeys.REF_NAME, viewModel.refName.removePrefix("Ref: "))

            // 🚀 THE FIX: Pass the dynamic timestamped path stored in the ViewModel
            putExtra(DicKeys.REF_PATH, viewModel.lastRefPath ?: "")

            putExtra(DicKeys.DEF_PATH, viewModel.lastDefPath)
            putExtra(DicKeys.BATCH_DIR_PATH, viewModel.lastBatchDirPath)
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, ArrayList(viewModel.defFilePaths.map { it.substringAfterLast('/') }))

            // 🚀 PDF GENERATOR DATA
            putExtra(DicKeys.SESSION_ID, viewModel.currentSessionId)
            putExtra(DicKeys.SUBSET_SIZE, currentSubsetSize())
            putExtra(DicKeys.STRAIN_WINDOW, currentStrainWindow())
            putExtra(DicKeys.STRAIN_METHOD, if (currentUseNlvc()) "NLVC" else "VSG")
            putExtra(DicKeys.ENGINE_STATS, viewModel.engineStatsArray)

            // 🚀 NEW: PASSING ROI DATA FOR THE PDF REPORT
            putExtra(DicKeys.ROI_X, viewModel.roiX)
            putExtra(DicKeys.ROI_Y, viewModel.roiY)
            putExtra(DicKeys.ROI_W, viewModel.roiW)
            putExtra(DicKeys.ROI_H, viewModel.roiH)
        }
        startActivity(intent)
    }

    private fun handleMaskSelection(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                viewModel.roiMaskBytes = stream.readBytes()
                Toast.makeText(this, R.string.mask_uploaded, Toast.LENGTH_SHORT).show()
                btnLoadRoiMask.text = getString(R.string.mask_uploaded_button)
                // Success state via the design-system color, keeping the
                // outlined Material shape intact.
                (btnLoadRoiMask as? com.google.android.material.button.MaterialButton)?.let {
                    it.setStrokeColorResource(R.color.semantic_success)
                    it.setTextColor(getColor(R.color.semantic_success))
                }
                viewModel.hasCustomRoi = true
                checkReady()
            }
        } catch (e: Exception) {
            Log.e("StaticAnalysisActivity", "Failed to load ROI mask", e)
            Toast.makeText(this, R.string.failed_load_mask, Toast.LENGTH_LONG).show()
        }
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
    // Parameter sliders: keep the live "N px" labels in sync. Values are
    // read via slider.value everywhere the old EditTexts were parsed.
    // ------------------------------------------------------------------
    private fun setupParameterControls() {
        val updateLabels = {
            tvSubsetValue.text = "${etSubsetSize.value.toInt()} px"
            tvStepValue.text = "${etStepSize.value.toInt()} px"
            tvStrainValue.text = "${etStrainWindow.value.toInt()} px"
        }
        updateLabels()

        etSubsetSize.addOnChangeListener { _, _, _ -> updateLabels() }
        etStepSize.addOnChangeListener { _, _, _ -> updateLabels() }
        etStrainWindow.addOnChangeListener { _, _, _ -> updateLabels() }
    }

    // ------------------------------------------------------------------
    // Wizard navigation: page 1 (images) ⇄ page 2 (settings + run)
    // ------------------------------------------------------------------
    private fun goToStep(step: Int, animate: Boolean) {
        val forward = step == 2
        viewModel.wizardStep = step

        // Reaching the settings page counts as reviewing the parameters —
        // they are all visible here — which satisfies the Compute gate.
        if (forward) viewModel.settingsReviewed = true

        val showing = if (forward) scrollStepSettings else scrollStepImages
        val hiding = if (forward) scrollStepImages else scrollStepSettings

        hiding.visibility = View.GONE
        showing.visibility = View.VISIBLE
        if (animate) {
            showing.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(
                    this, if (forward) R.anim.slide_in_right else R.anim.slide_in_left
                )
            )
        }

        // Step indicator chips
        tvStepChip1.setBackgroundResource(
            if (forward) R.drawable.bg_chip_step_inactive else R.drawable.bg_pill_accent)
        tvStepChip1.setTextColor(getColor(
            if (forward) R.color.sky_on_container else R.color.text_on_primary))
        tvStepChip2.setBackgroundResource(
            if (forward) R.drawable.bg_pill_accent else R.drawable.bg_chip_step_inactive)
        tvStepChip2.setTextColor(getColor(
            if (forward) R.color.text_on_primary else R.color.sky_on_container))

        // Bottom nav: Next drives page 1, Back appears on page 2
        btnNext.visibility = if (forward) View.GONE else View.VISIBLE
        btnBack.visibility = if (forward) View.VISIBLE else View.GONE

        checkReady()
    }

    // ------------------------------------------------------------------
    // Compute progress overlay control
    // ------------------------------------------------------------------
    private fun showComputeOverlay(
        title: String = "Computing Strain Field",
        status: String = "Initializing engine…"
    ) {
        overlayTitle.text = title
        overlayProgress.progress = 0
        overlayPercent.text = "0%"
        overlayStatus.text = status
        overlayElapsed.text = "Elapsed 0s"
        computeOverlay.visibility = View.VISIBLE
        elapsedHandler.removeCallbacks(elapsedTicker)
        elapsedHandler.post(elapsedTicker)
    }

    private fun hideComputeOverlay() {
        computeOverlay.visibility = View.GONE
        elapsedHandler.removeCallbacks(elapsedTicker)
    }

    /** Update the overlay's ring + percentage. Safe to call from any thread. */
    private fun setComputeProgress(percent: Int) {
        runOnUiThread {
            val p = percent.coerceIn(0, 100)
            overlayProgress.progress = p
            overlayPercent.text = "$p%"
        }
    }

    /** Update the overlay's status line (e.g. "Processing frame 2/5"). */
    private fun setComputeStatus(text: String) {
        runOnUiThread { overlayStatus.text = text }
    }

    private fun checkReady() {
        val ready = viewModel.isReadyToCompute()

        // Page-1 gate: Next stays disabled + visibly faded until both images are set
        val nextEnabled = ready && !isProcessing
        btnNext.isEnabled = nextEnabled
        btnNext.alpha = if (nextEnabled) 1.0f else 0.4f

        // Page-2 gate: Compute needs images AND the settings page visited
        val computeEnabled = ready && viewModel.settingsReviewed && !isProcessing
        btnCalculateFullField.isEnabled = computeEnabled
        btnCalculateFullField.alpha = if (computeEnabled) 1.0f else 0.4f

        btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        btnManualRoi.isEnabled = !isProcessing
        btnLoadRef.isEnabled = !isProcessing
        btnLoadDef.isEnabled = !isProcessing
        btnBack.isEnabled = !isProcessing
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
    private fun showShapeRoiDialog() {
        if (viewModel.refBytes == null) {
            Toast.makeText(this, R.string.manual_roi_load_ref_first, Toast.LENGTH_SHORT).show()
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

        val etRectX = dialogView.findViewById<TextInputEditText>(R.id.etRectX)
        val etRectY = dialogView.findViewById<TextInputEditText>(R.id.etRectY)
        val etRectW = dialogView.findViewById<TextInputEditText>(R.id.etRectW)
        val etRectH = dialogView.findViewById<TextInputEditText>(R.id.etRectH)

        etRectX.setText(viewModel.roiX.toString())
        etRectY.setText(viewModel.roiY.toString())
        etRectW.setText(if (viewModel.roiW > 0) viewModel.roiW.toString() else imgW.toString())
        etRectH.setText(if (viewModel.roiH > 0) viewModel.roiH.toString() else imgH.toString())

        dialogView.findViewById<TextInputLayout>(R.id.tilRectW).helperText =
            getString(R.string.manual_roi_max_width, imgW)
        dialogView.findViewById<TextInputLayout>(R.id.tilRectH).helperText =
            getString(R.string.manual_roi_max_height, imgH)

        val shapes = resources.getStringArray(R.array.manual_roi_shapes)
        spinner.adapter = ArrayAdapter(this, R.layout.spinner_item_white, shapes).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_white)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                layoutRect.visibility = if (pos == 0) View.VISIBLE else View.GONE
                layoutCircle.visibility = if (pos == 1) View.VISIBLE else View.GONE
                layoutEllipse.visibility = if (pos == 2) View.VISIBLE else View.GONE
                layoutTri.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_roi_title)
            .setView(dialogView)
            .setPositiveButton(R.string.apply, null)
            .setNegativeButton(R.string.cancel, null)
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
                    val cx = dialogView.findViewById<TextInputEditText>(R.id.etCircCx).text.toString().toFloatOrNull() ?: (imgW/2f)
                    val cy = dialogView.findViewById<TextInputEditText>(R.id.etCircCy).text.toString().toFloatOrNull() ?: (imgH/2f)
                    val r = dialogView.findViewById<TextInputEditText>(R.id.etCircR).text.toString().toFloatOrNull() ?: 100f

                    canvas.drawCircle(cx, cy, r, paint)

                    finalX = (cx - r).toInt()
                    finalY = (cy - r).toInt()
                    finalW = (r * 2).toInt()
                    finalH = (r * 2).toInt()
                }
                2 -> {
                    requiresMask = true
                    val cx = dialogView.findViewById<TextInputEditText>(R.id.etEllCx).text.toString().toFloatOrNull() ?: (imgW/2f)
                    val cy = dialogView.findViewById<TextInputEditText>(R.id.etEllCy).text.toString().toFloatOrNull() ?: (imgH/2f)
                    val rx = dialogView.findViewById<TextInputEditText>(R.id.etEllRx).text.toString().toFloatOrNull() ?: 150f
                    val ry = dialogView.findViewById<TextInputEditText>(R.id.etEllRy).text.toString().toFloatOrNull() ?: 100f

                    canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)

                    finalX = (cx - rx).toInt()
                    finalY = (cy - ry).toInt()
                    finalW = (rx * 2).toInt()
                    finalH = (ry * 2).toInt()
                }
                3 -> {
                    requiresMask = true
                    val x1 = dialogView.findViewById<TextInputEditText>(R.id.etTriX1).text.toString().toFloatOrNull() ?: 0f
                    val y1 = dialogView.findViewById<TextInputEditText>(R.id.etTriY1).text.toString().toFloatOrNull() ?: 0f
                    val x2 = dialogView.findViewById<TextInputEditText>(R.id.etTriX2).text.toString().toFloatOrNull() ?: 0f
                    val y2 = dialogView.findViewById<TextInputEditText>(R.id.etTriY2).text.toString().toFloatOrNull() ?: 0f
                    val x3 = dialogView.findViewById<TextInputEditText>(R.id.etTriX3).text.toString().toFloatOrNull() ?: 0f
                    val y3 = dialogView.findViewById<TextInputEditText>(R.id.etTriY3).text.toString().toFloatOrNull() ?: 0f

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
                    getString(R.string.manual_roi_dims_positive)
                } else {
                    getString(
                        R.string.manual_roi_out_of_bounds,
                        imgW, imgH, finalX + finalW, finalY + finalH
                    )
                }
                Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
            } else {
                if (requiresMask) {
                    val stream = java.io.ByteArrayOutputStream()
                    maskBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    viewModel.roiMaskBytes = stream.toByteArray()
                    tvInstruction.text = getString(R.string.manual_roi_complex_applied)
                } else {
                    tvInstruction.text = getString(R.string.manual_roi_rect_set, finalW, finalH)
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