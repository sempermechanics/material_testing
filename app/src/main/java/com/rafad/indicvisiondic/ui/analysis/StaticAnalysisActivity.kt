package com.rafad.indicvisiondic.ui.analysis
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.IndicVisionNativeLib
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.DicSettings
import com.rafad.indicvisiondic.data.SessionStore
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.ui.common.Insets
import com.rafad.indicvisiondic.ui.common.MediaSourceChooser
import com.rafad.indicvisiondic.ui.common.Motion
import com.rafad.indicvisiondic.ui.limit.SessionLimitActivity
import com.rafad.indicvisiondic.ui.viewer.ResultViewerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * The analysis setup wizard: page 1 loads reference/deformed images (or
 * extracts frames from a video), page 2 sets parameters + ROI and launches
 * the batch solve via [AnalysisViewModel]. Results open in ResultViewerActivity.
 */
class StaticAnalysisActivity : AppCompatActivity() {

    private companion object {
        /** Subset shown before a reference image is available to measure. */
        const val FALLBACK_SUBSET_SIZE = 41

        /** Index of the height in the `[x, y, w, h]` array [resolveRoi] returns. */
        const val ROI_H_INDEX = 3

        /** Width of the subset window a fresh sweep suggests, centred on the recommendation. */
        const val SUGGESTED_SUBSET_SPAN = 20

        /** Suggested Max VSG, as a multiple of the largest subset in the sweep. */
        const val VSG_SUGGESTION_FACTOR = 3

        /** Hard bounds on Max VSG — the guardrail against a mistyped huge number. */
        const val VSG_MIN_INPUT = 21
        const val VSG_MAX_INPUT = 501

        // Native engine failure codes, shared with the single-analysis path.
        const val ENGINE_ERROR_FEATURES = -1
        const val ENGINE_ERROR_ROI = -2
        const val ENGINE_ERROR_INIT = -3

        /**
         * Clearance the engine demands around a grid point on top of half its
         * subset: 4 px of interpolation buffer plus a 15 px deformation buffer
         * (IndicVisionJNI.cpp, `absolute_boundary_buffer`). Points inside it are
         * dropped, and a solve with no points left returns [ENGINE_ERROR_ROI].
         */
        const val ENGINE_EDGE_BUFFER_PX = 19

        /** Slack [resolveRoi] adds beyond half a subset when insetting a frame. */
        const val ROI_MARGIN_SLACK_PX = 10
    }

    private val viewModel: AnalysisViewModel by viewModels()

    // UI Components
    private lateinit var btnFullImage: Button
    private lateinit var btnDefineRoi: Button
    private lateinit var tvResult: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var tvRefName: TextView
    private lateinit var tvDefName: TextView
    private lateinit var etSubsetSize: Slider
    private lateinit var etStepSize: Slider
    private lateinit var etStrainWindow: Slider
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTimer: TextView
    private var updateAdvancedSummary: (() -> Unit)? = null

    // Wireframe slots (load-frames page + confirm-settings page)
    private lateinit var refDropzone: View
    private lateinit var refCard: View
    private lateinit var ivRefThumb: ImageView
    private lateinit var tvRefMeta: TextView
    private lateinit var defDropzone: View
    private lateinit var defCard: View
    private lateinit var ivDefIcon: ImageView
    private lateinit var tvDefMeta: TextView
    private lateinit var tvDefDropHint: TextView
    private lateinit var jpegWarnRow: View
    private lateinit var tvNextReason: TextView
    private lateinit var ivInputsThumb: ImageView
    private lateinit var tvInputsTitle: TextView
    private lateinit var tvInputsMeta: TextView
    private var refPreviewBmp: android.graphics.Bitmap? = null
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
    private lateinit var rgInterpolator: MaterialButtonToggleGroup

    // Editable value fields for the parameter sliders (typing and dragging
    // both drive the same slider value)
    private lateinit var tvSubsetValue: EditText
    private lateinit var tvStepValue: EditText
    private lateinit var tvStrainValue: EditText

    /** Inline note carrying the SSSIG-based subset suggestion. */
    private lateinit var tvSubsetHint: TextView

    // Parameter-sweep controls (see [VsgStudy])
    private lateinit var rgAnalysisMode: MaterialButtonToggleGroup
    private lateinit var advancedParamsCard: View
    private lateinit var sweepBody: View
    private lateinit var rangeSubset: RangeSlider
    private lateinit var sliderVsgMax: Slider
    private lateinit var sliderSubsetSamples: Slider
    private lateinit var sliderVsgSamples: Slider
    private lateinit var sliderStepDepth: Slider
    private lateinit var etSubsetMinValue: EditText
    private lateinit var etSubsetMaxValue: EditText
    private lateinit var etVsgMaxValue: EditText
    private lateinit var tvSubsetSamplesValue: TextView
    private lateinit var tvVsgSamplesValue: TextView
    private lateinit var tvStepDepthValue: TextView
    private lateinit var rgLineCutAxis: MaterialButtonToggleGroup
    private lateinit var btnPickSweepFrame: Button
    private lateinit var tvSweepPlan: TextView
    private lateinit var lineCutPreview: LineCutPreviewView
    private lateinit var sweepLatticePreview: VsgLatticeView
    private lateinit var btnRunSweep: Button

    /** True while a suggestion/clamp is driving the sweep sliders, not the user. */
    private var bindingSweep = false

    /**
     * Set once the user edits any sweep control. Until then the three sweep
     * inputs — min subset, max subset, Max VSG — follow the app's suggestions,
     * which track the SSSIG recommendation. After it, the user is in charge and
     * the app only clamps their input to safe bounds.
     */
    private var sweepUserModified = false

    // Three-step wizard: images → settings → (sweep setup when Parameter sweep)
    private lateinit var scrollStepImages: View
    private lateinit var scrollStepSettings: View
    private lateinit var scrollStepSweep: View
    private lateinit var btnNext: Button
    private lateinit var btnBack: Button

    // State
    private var isProcessing = false
    private var processingStartTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_static_analysis)
        // --- BACK BUTTON INTERCEPTOR (SAFETY LOCK) ---
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isProcessing) {
                        // Block the back button completely if the C++ engine is running
                        Toast.makeText(this@StaticAnalysisActivity, R.string.analysis_running_back_blocked, Toast.LENGTH_SHORT).show()
                    } else if (viewModel.wizardStep > 1) {
                        goToStep(viewModel.wizardStep - 1, animate = true)
                    } else if (viewModel.refBytes != null || viewModel.defFilePaths.isNotEmpty()) {
                        AlertDialog.Builder(this@StaticAnalysisActivity)
                            .setTitle(R.string.exit_analysis_title)
                            .setMessage(R.string.exit_analysis_message)
                            .setPositiveButton(R.string.exit) { _, _ ->
                                finish()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    } else {
                        finish()
                    }
                }
            },
        )

        progressBar = findViewById(R.id.pbAnalysis)
        tvTimer = findViewById(R.id.tvTimer)
        computeOverlay = findViewById(R.id.computeOverlay)
        overlayTitle = findViewById(R.id.overlayTitle)
        overlayProgress = findViewById(R.id.overlayProgress)
        overlayPercent = findViewById(R.id.overlayPercent)
        overlayStatus = findViewById(R.id.overlayStatus)
        overlayElapsed = findViewById(R.id.overlayElapsed)
        btnFullImage = findViewById(R.id.btnFullImage)
        btnDefineRoi = findViewById(R.id.btnDefineRoi)
        tvResult = findViewById(R.id.tvStaticResult)
        refDropzone = findViewById(R.id.refDropzone)
        refCard = findViewById(R.id.refCard)
        ivRefThumb = findViewById(R.id.ivRefThumb)
        tvRefMeta = findViewById(R.id.tvRefMeta)
        defDropzone = findViewById(R.id.defDropzone)
        defCard = findViewById(R.id.defCard)
        ivDefIcon = findViewById(R.id.ivDefIcon)
        tvDefMeta = findViewById(R.id.tvDefMeta)
        tvDefDropHint = findViewById(R.id.tvDefDropHint)
        jpegWarnRow = findViewById(R.id.jpegWarnRow)
        tvNextReason = findViewById(R.id.tvNextReason)
        ivInputsThumb = findViewById(R.id.ivInputsThumb)
        tvInputsTitle = findViewById(R.id.tvInputsTitle)
        tvInputsMeta = findViewById(R.id.tvInputsMeta)
        tvInstruction = findViewById(R.id.tvInstruction)
        tvRefName = findViewById(R.id.tvRefName)
        tvDefName = findViewById(R.id.tvDefName)
        etSubsetSize = findViewById(R.id.etSubsetSize)
        etStepSize = findViewById(R.id.etStepSize)
        etStrainWindow = findViewById(R.id.etStrainWindow)
        btnCalculateFullField = findViewById(R.id.btnCalculateFullField)
        rgInterpolator = findViewById(R.id.rgInterpolator) // BOUND

        // --- Parameter sliders: live value labels ---
        tvSubsetValue = findViewById(R.id.tvSubsetValue)
        tvStepValue = findViewById(R.id.tvStepValue)
        tvStrainValue = findViewById(R.id.tvStrainValue)
        tvSubsetHint = findViewById(R.id.tvSubsetHint)
        setupParameterControls()

        // --- Wizard wiring: images → settings → optional sweep page ---
        scrollStepImages = findViewById(R.id.scrollStepImages)
        scrollStepSettings = findViewById(R.id.scrollStepSettings)
        scrollStepSweep = findViewById(R.id.scrollStepSweep)
        btnNext = findViewById(R.id.btnNext)
        btnBack = findViewById(R.id.btnBack)

        // After the wizard views exist: the sweep controls call checkReady().
        setupSweepControls()

        btnNext.setOnClickListener {
            when (viewModel.wizardStep) {
                1 -> goToStep(2, animate = true)
                2 -> if (viewModel.sweepMode) goToStep(3, animate = true)
            }
        }
        btnBack.setOnClickListener {
            if (viewModel.wizardStep > 1) goToStep(viewModel.wizardStep - 1, animate = true)
        }
        goToStep(viewModel.wizardStep, animate = false)

        // Hand-off from Home's media picker: the selection type already
        // decided the branch — image becomes the reference, video enters
        // the extract-frames flow. Consumed once.
        intent.getStringExtra(DicKeys.PICKED_REF_URI)?.let {
            intent.removeExtra(DicKeys.PICKED_REF_URI)
            handleReferenceImage(Uri.parse(it))
        }
        intent.getStringExtra(DicKeys.PICKED_VIDEO_URI)?.let {
            intent.removeExtra(DicKeys.PICKED_VIDEO_URI)
            handleVideo(Uri.parse(it))
        }

        // Edge-to-edge (targetSdk 36): push the app bar below the status bar
        // and keep the wizard nav above the nav-bar gesture area so the top
        // controls aren't in the system swipe-down zone.
        Insets.padTop(findViewById(R.id.toolbar))
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            title = getString(R.string.new_analysis_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        tvDefDropHint.text = getString(R.string.def_formats_hint_fmt, DicSettings.maxFrames(this))
        Insets.padBottom(findViewById(R.id.bottomNav))

        // Gentle entrance: cards cascade in on first show only (not on rotation)
        if (savedInstanceState == null) {
            Motion.enterStaggered(findViewById(R.id.contentColumn))
        }

        restoreUiFromViewModel()

        // Reference: one image, from either source. Files (SAF) is the route that
        // reaches DNG/RAW, which the Photo Picker does not index.
        val pickRefPhotos =
            registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                uri?.let { handleReferenceImage(it) }
            }
        val pickRefFiles =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { handleReferenceImage(it) }
            }

        // Deformed frames: multi-select from either source. handleDeformedBatch
        // enforces the per-analysis frame cap, so both launchers stay uncapped here.
        val pickDefPhotos =
            registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                onDeformedPicked(uris)
            }
        val pickDefFiles =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                onDeformedPicked(uris)
            }

        val roiStudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                if (data != null) {
                    viewModel.roiX = data.getIntExtra(DicKeys.ROI_X, 0)
                    viewModel.roiY = data.getIntExtra(DicKeys.ROI_Y, 0)
                    viewModel.roiW = data.getIntExtra(DicKeys.ROI_W, viewModel.realRefWidth)
                    viewModel.roiH = data.getIntExtra(DicKeys.ROI_H, viewModel.realRefHeight)

                    // PIPELINE FIX: Actually read the mask file sent by RoiDrawActivity!
                    val maskPath = data.getStringExtra(DicKeys.MASK_FILE_PATH)
                    if (maskPath != null) {
                        val file = File(maskPath)
                        if (file.exists()) {
                            viewModel.roiMaskBytes = file.readBytes()
                        }
                    }

                    // FIX FULL IMAGE OVERRIDE: If it's exactly the image bounds, unset custom ROI
                    if (viewModel.roiW == viewModel.realRefWidth && viewModel.roiH == viewModel.realRefHeight) {
                        viewModel.hasCustomRoi = false
                        tvInstruction.text = "✅ Full Image Analysis Set"
                    } else {
                        viewModel.hasCustomRoi = true
                        tvInstruction.text = "✅ ROI Set: ${viewModel.roiW} x ${viewModel.roiH} px"
                    }

                    refreshLineCutPreview()
                    checkReady()
                    requestSubsetRecommendation()
                }
            } else {
                tvInstruction.text = "❌ ROI Selection Cancelled"
            }
        }

        val launchRefPicker = {
            MediaSourceChooser.show(
                activity = this,
                titleRes = R.string.reference_image,
                captionRes = R.string.ref_formats_hint,
                onPhotos = {
                    pickRefPhotos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onFiles = { pickRefFiles.launch(arrayOf("image/*")) },
            )
        }
        refDropzone.setOnClickListener { launchRefPicker() }
        findViewById<View>(R.id.btnRefChange).setOnClickListener { launchRefPicker() }

        val launchDefPicker = {
            MediaSourceChooser.show(
                activity = this,
                titleRes = R.string.deformed_frames,
                captionRes = R.string.picker_select_deformed,
                onPhotos = {
                    pickDefPhotos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onFiles = { pickDefFiles.launch(arrayOf("image/*")) },
            )
        }
        defDropzone.setOnClickListener { launchDefPicker() }
        findViewById<View>(R.id.btnDefChange).setOnClickListener { launchDefPicker() }

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

        btnFullImage.setOnClickListener {
            if (viewModel.realRefWidth > 0) {
                viewModel.hasCustomRoi = false
                viewModel.roiMaskBytes = null
                tvInstruction.text = "✅ Using Full Image"
                refreshLineCutPreview()
                checkReady()
                requestSubsetRecommendation()
            }
        }

        btnCalculateFullField.setOnClickListener {
            // A field still holding focus has not committed its typed value yet.
            commitParamFields()
            if (!viewModel.sweepMode) startBatchAnalysis()
        }
    }

    private fun handleReferenceImage(uri: Uri) {
        val name = getFileName(uri)

        val isRaw = name.endsWith(".dng", true) || name.endsWith(".raw", true)

        lifecycleScope.launch {
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
                            Toast.makeText(
                                this@StaticAnalysisActivity,
                                R.string.failed_decode_raw,
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }
                    } else {
                        bytes = stream.readBytes()
                        val result = withContext(IndicVisionNativeLib.nativeDispatcher) {
                            val dims = IndicVisionNativeLib.getImageDimensions(bytes)
                            val preview = IndicVisionNativeLib.getPreviewFromBytes(bytes, 1000)
                            dims to preview
                        }
                        viewModel.realRefWidth = result.first[0]
                        viewModel.realRefHeight = result.first[1]
                        previewBmp = result.second
                    }

                    viewModel.refName = "Ref: $name"
                    viewModel.refBytes = bytes
                    refPreviewBmp = previewBmp
                    refreshRefSlot()

                    if (!viewModel.hasCustomRoi) {
                        viewModel.roiX = 0
                        viewModel.roiY = 0
                        viewModel.roiW = viewModel.realRefWidth
                        viewModel.roiH = viewModel.realRefHeight
                    }
                    // Frames may have been loaded before this reference.
                    validateFrameSizes()
                    checkReady()
                    requestSubsetRecommendation()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load reference image")
                Toast.makeText(this@StaticAnalysisActivity, R.string.failed_load_reference, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Shared result path for the deformed-frame pickers (Photos and Files). */
    private fun onDeformedPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            handleDeformedBatch(uris)
        } else {
            Toast.makeText(this, R.string.no_images_selected, Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // legacy import pipeline; slated for P5 split
    private fun handleDeformedBatch(rawUris: List<Uri>) {
        // Frame cap (Home settings drawer): keep the first N and say so.
        val cap = DicSettings.maxFrames(this)
        val uris = if (rawUris.size > cap) {
            Toast.makeText(this, getString(R.string.frames_capped_fmt, cap), Toast.LENGTH_LONG).show()
            rawUris.take(cap)
        } else {
            rawUris
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "temp_deformed")
                if (!tempDir.exists()) tempDir.mkdirs()
                tempDir.listFiles()?.forEach { it.delete() }

                viewModel.clearPreviousResults()

                val filePaths = mutableListOf<String>()
                // Temp path → original picked filename, kept so exports can use the
                // user's real (default) names instead of the sanitized temp names.
                val originalByPath = mutableMapOf<String, String>()
                // Temp path → pixel size, so the reference-match check is free later.
                val sizeByPath = mutableMapOf<String, Pair<Int, Int>>()

                withContext(Dispatchers.Main) {
                    tvResult.text = "Caching images..."
                }

                for ((index, uri) in uris.withIndex()) {
                    var bytes: ByteArray? = null
                    var previewBmp: Bitmap? = null
                    var frameSize: Pair<Int, Int>? = null

                    val originalName = getFileName(uri)

                    val isRaw = originalName.endsWith(".dng", true) || originalName.endsWith(".raw", true)

                    contentResolver.openInputStream(uri)?.use { stream ->
                        if (isRaw) {
                            val bitmap = android.graphics.BitmapFactory.decodeStream(stream)
                            if (bitmap != null) {
                                val buffer = java.nio.ByteBuffer.allocate(bitmap.width * bitmap.height * 4)
                                bitmap.copyPixelsToBuffer(buffer)
                                bytes = buffer.array()
                                frameSize = bitmap.width to bitmap.height

                                if (index == 0) {
                                    val ratio = 1000f / bitmap.width
                                    previewBmp = android.graphics.Bitmap.createScaledBitmap(bitmap, 1000, (bitmap.height * ratio).toInt(), true)
                                }
                            }
                        } else {
                            val frameBytes = stream.readBytes()
                            bytes = frameBytes
                            // JNI must stay on the pinned native thread.
                            withContext(IndicVisionNativeLib.nativeDispatcher) {
                                val dims = IndicVisionNativeLib.getImageDimensions(frameBytes)
                                frameSize = dims[0] to dims[1]
                                if (index == 0) {
                                    previewBmp = IndicVisionNativeLib.getPreviewFromBytes(frameBytes, 1000)
                                }
                            }
                        }
                    }

                    if (bytes == null) continue

                    val sanitizedName = originalName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                    val filename = String.format("%04d_%s", index, sanitizedName)
                    val file = File(tempDir, filename)
                    file.writeBytes(bytes)
                    filePaths.add(file.absolutePath)
                    originalByPath[file.absolutePath] = originalName
                    frameSize?.let { sizeByPath[file.absolutePath] = it }
                }

                val sortedPaths = filePaths.sorted()
                viewModel.defFilePaths = sortedPaths
                viewModel.defOriginalNames = sortedPaths.map { originalByPath[it] ?: File(it).name }
                viewModel.defFrameSizes = sizeByPath
                viewModel.defFromVideo = false

                withContext(Dispatchers.Main) {
                    tvResult.text = ""
                    refreshDefSlot()
                    validateFrameSizes()
                    checkReady()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling batch")
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
    // Step 1: read metadata  show resolution/fps/length + sampling options.
    // Step 2: extract at the chosen frame rate over the chosen time segment.
    // ------------------------------------------------------------------
    private data class VideoMeta(
        val durationMs: Long,
        val fps: Double,
        val fpsKnown: Boolean,
        val width: Int,
        val height: Int,
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
                if (rot == 90 || rot == 270) {
                    val t = w
                    w = h
                    h = t
                } // display orientation
                val frameCountMeta = m(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                var fps = 30.0
                var fpsKnown = false
                if (frameCountMeta != null && frameCountMeta > 0 && durationMs > 0) {
                    fps = frameCountMeta / (durationMs / 1000.0)
                    fpsKnown = true
                }
                meta = VideoMeta(durationMs, fps, fpsKnown, w, h)
            } catch (e: Exception) {
                Timber.e(e, "Video metadata read failed")
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
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

        val maxFrames = DicSettings.maxFrames(this@StaticAnalysisActivity)
        fun estimate(): Int {
            val startS = range.values.first()
            val endS = range.values.last()
            val segSec = (endS - startS).coerceAtLeast(0f)
            return (segSec * sliderFps.value + 1f).toInt().coerceIn(1, maxFrames)
        }
        val btnExtract = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExtractFrames)
        fun refreshEstimate() {
            val n = estimate()
            val capped = if (n >= maxFrames) getString(R.string.video_capped_suffix) else ""
            tvEstimate.text = "≈ $n frame(s): 1 reference + ${(n - 1).coerceAtLeast(0)} deformed$capped"
            btnExtract.text = getString(R.string.extract_n_frames_fmt, n)
        }

        sliderFps.addOnChangeListener { _, v, _ ->
            tvFps.text = "${v.toInt()} fps"
            refreshEstimate()
        }
        range.addOnChangeListener { s, _, _ ->
            val startMs = (s.values.first() * 1000).toLong()
            val endMs = (s.values.last() * 1000).toLong()
            tvSegment.text = "${formatClock(startMs)} – ${formatClock(endMs)}"
            refreshEstimate()
        }
        refreshEstimate()

        // Bottom sheet (wireframe 05b): the primary button states the outcome.
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        sheet.setContentView(view)
        btnExtract.setOnClickListener {
            sheet.dismiss()
            val fpsExtract = sliderFps.value.toDouble().coerceAtLeast(0.1)
            val startMs = (range.values.first() * 1000).toLong()
            val endMs = (range.values.last() * 1000).toLong()
            extractVideoFrames(uri, fpsExtract, startMs, endMs)
        }
        sheet.show()
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
                val maxFrames = DicSettings.maxFrames(this@StaticAnalysisActivity)
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
                        (timeMs * 1000).toLong(),
                        android.media.MediaMetadataRetriever.OPTION_CLOSEST,
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
                            viewModel.roiX = 0
                            viewModel.roiY = 0
                            viewModel.roiW = frame.width
                            viewModel.roiH = frame.height
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

                val sortedDefPaths = defPaths.sorted()
                viewModel.defFilePaths = sortedDefPaths
                viewModel.defOriginalNames =
                    sortedDefPaths.mapIndexed { idx, _ -> String.format("frame_%04d.png", idx + 1) }
                // Every frame comes out of the same decoder, so they all share
                // the reference's size by construction — recorded so the check
                // has data for this path too.
                val videoFrameSize = viewModel.realRefWidth to viewModel.realRefHeight
                viewModel.defFrameSizes = sortedDefPaths.associateWith { videoFrameSize }
                viewModel.defFromVideo = true

                withContext(Dispatchers.Main) {
                    hideComputeOverlay()
                    refPreview?.let { refPreviewBmp = it }
                    refreshRefSlot()
                    refreshDefSlot()
                    validateFrameSizes()
                    checkReady()
                    requestSubsetRecommendation()
                    Toast.makeText(
                        this@StaticAnalysisActivity,
                        getString(R.string.video_loaded_frames, defPaths.size),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error extracting video frames")
                withContext(Dispatchers.Main) {
                    hideComputeOverlay()
                    Toast.makeText(this@StaticAnalysisActivity, getString(R.string.video_read_error, e.message), Toast.LENGTH_LONG).show()
                }
            } finally {
                try {
                    retriever.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun currentSubsetSize(): Int = etSubsetSize.value.toInt()
    private fun currentStepSize(): Int = etStepSize.value.toInt()
    private fun currentStrainWindow(): Int = etStrainWindow.value.toInt()
    private fun currentUseKeysInterpolator(): Boolean = rgInterpolator.checkedButtonId == R.id.rbKeys

    // ------------------------------------------------------------------
    // Initial subset size from the SSSIG criterion (Pan et al., Opt. Express
    // 16, 7037 (2008)) — see [SubsetRecommender]. The reference speckle decides
    // it, so it is measured whenever the reference image or the ROI changes,
    // and stops seeding the slider once the user sets a size of their own.
    // ------------------------------------------------------------------

    /**
     * Every deformed frame must match the reference pixel for pixel. The engine
     * clamps its AKAZE search window to the reference size and then indexes the
     * deformed image with it, so a mismatch throws inside OpenCV — and the JNI
     * layer swallows that exception, leaving a silently under-seeded solve.
     * Catching it here turns a bad result into a clear, fixable message.
     *
     * Costs nothing: the sizes were measured during import.
     */
    private fun validateFrameSizes() {
        val refW = viewModel.realRefWidth
        val refH = viewModel.realRefHeight
        val sizes = viewModel.defFrameSizes
        viewModel.frameSizeError = if (refW <= 0 || refH <= 0 || sizes.isEmpty()) {
            null
        } else {
            val mismatched = viewModel.defFilePaths.count { path ->
                val size = sizes[path]
                size != null && size != (refW to refH)
            }
            if (mismatched == 0) {
                null
            } else {
                getString(R.string.frames_size_mismatch_fmt, mismatched, refW, refH)
            }
        }
    }

    /** Region the recommendation samples: the ROI when set, else the frame. */
    private fun currentSamplingRoi(): android.graphics.Rect? {
        val w = viewModel.realRefWidth
        val h = viewModel.realRefHeight
        if (w <= 0 || h <= 0) return null
        return if (viewModel.hasCustomRoi && viewModel.roiW > 0 && viewModel.roiH > 0) {
            android.graphics.Rect(
                viewModel.roiX,
                viewModel.roiY,
                viewModel.roiX + viewModel.roiW,
                viewModel.roiY + viewModel.roiH,
            )
        } else {
            android.graphics.Rect(0, 0, w, h)
        }
    }

    @Suppress("ReturnCount")
    private fun requestSubsetRecommendation() {
        val bytes = viewModel.refBytes ?: return
        val roi = currentSamplingRoi() ?: return
        val key = "${viewModel.refName}|${bytes.size}|${roi.toShortString()}"
        if (key == viewModel.subsetRecommendationKey) {
            applySubsetRecommendation()
            return
        }
        viewModel.subsetRecommendationKey = key
        viewModel.subsetRecommendation = null
        tvSubsetHint.visibility = View.VISIBLE
        tvSubsetHint.text = getString(R.string.subset_recommend_running)

        // Read off the slider here: the measurement runs on the native thread,
        // which must not touch views.
        val sizes = etSubsetSize.valueFrom.toInt()..etSubsetSize.valueTo.toInt()

        lifecycleScope.launch(IndicVisionNativeLib.nativeDispatcher) {
            val result = runCatching {
                SubsetRecommender.recommend(
                    refBytes = bytes,
                    imgW = viewModel.realRefWidth,
                    imgH = viewModel.realRefHeight,
                    roi = roi,
                    sizes = sizes,
                )
            }.onFailure { Timber.w(it, "Subset recommendation failed") }.getOrNull()

            // A newer reference/ROI landed while we were measuring.
            withContext(Dispatchers.Main) {
                if (viewModel.subsetRecommendationKey != key) return@withContext
                viewModel.subsetRecommendation = result
                applySubsetRecommendation()
            }
        }
    }

    /**
     * The subset size an untouched form shows: the SSSIG recommendation for
     * the loaded reference image, or the historical 41 px before one exists.
     */
    private fun defaultSubsetSize(): Int {
        val rec = viewModel.subsetRecommendation ?: return FALLBACK_SUBSET_SIZE
        return snapToSlider(etSubsetSize, rec.subsetSize)
    }

    /** Seeds the slider (until the user overrides it) and shows the note. */
    private fun applySubsetRecommendation() {
        val rec = viewModel.subsetRecommendation
        if (rec == null) {
            tvSubsetHint.visibility = View.GONE
            return
        }
        if (!viewModel.subsetUserModified) {
            val snapped = snapToSlider(etSubsetSize, rec.subsetSize)
            if (etSubsetSize.value.toInt() != snapped) {
                commitParamFields()
                etSubsetSize.value = snapped.toFloat()
            }
        }
        tvSubsetHint.visibility = View.VISIBLE
        tvSubsetHint.text = if (rec.lowTexture) {
            getString(R.string.subset_recommend_low_texture_fmt, rec.subsetSize)
        } else {
            getString(R.string.subset_recommend_fmt, rec.subsetSize)
        }
        updateAdvancedSummary?.invoke()
        // A new recommendation re-seeds the sweep's suggested inputs (unless the
        // user has already set their own).
        onRecommendationChanged()
    }

    /**
     * The rectangle the engine solves over, as `[x, y, w, h]`: the drawn ROI,
     * or the whole frame inset by half a subset (plus slack) so no subset hangs
     * off the edge. Null — with the user told why — when it cannot hold one
     * subset.
     */
    private fun resolveRoi(subset: Int): IntArray? {
        val roi = if (viewModel.hasCustomRoi) {
            intArrayOf(viewModel.roiX, viewModel.roiY, viewModel.roiW, viewModel.roiH)
        } else {
            val margin = (subset / 2) + ROI_MARGIN_SLACK_PX
            intArrayOf(
                margin,
                margin,
                viewModel.realRefWidth - (2 * margin),
                viewModel.realRefHeight - (2 * margin),
            )
        }
        if (roi[2] < subset || roi[3] < subset) {
            Toast.makeText(this, R.string.roi_too_small, Toast.LENGTH_LONG).show()
            return null
        }
        return roi
    }

    private fun startBatchAnalysis() {
        if (!viewModel.isReadyToCompute()) return

        val subset = currentSubsetSize()
        val step = currentStepSize()
        val strainWin = currentStrainWindow()

        val roi = resolveRoi(subset) ?: return
        val (finalRectX, finalRectY, finalRectW) = roi
        val finalRectH = roi[ROI_H_INDEX]

        // Hard stop: do not start a new analysis when the session quota is full.
        // Re-runs that update an existing Home row are still allowed.
        if (viewModel.wouldCreateNewSession(this)) {
            TokenStore.refreshSessionLimit(this, SessionStore.list(this).size)
            if (TokenStore.isSessionLimitReached(this)) {
                startActivity(Intent(this, SessionLimitActivity::class.java))
                return
            }
        }

        isProcessing = true
        checkReady()
        processingStartTime = System.currentTimeMillis()
        showComputeOverlay()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.btnRunCancel).apply {
            isEnabled = true
            setOnClickListener {
                MaterialAlertDialogBuilder(this@StaticAnalysisActivity)
                    .setTitle(R.string.cancel_run_title)
                    .setMessage(R.string.cancel_run_body)
                    .setPositiveButton(R.string.action_cancel) { _, _ ->
                        viewModel.cancelRequested = true
                        isEnabled = false
                    }
                    .setNegativeButton(R.string.keep_running, null)
                    .show()
            }
        }
        // Keep the legacy inline indicators in sync (hidden behind the overlay)
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        tvTimer.visibility = View.VISIBLE
        tvTimer.text = "Initializing Engine..."

        // Pinned: strain is always VSG, blur always off (UI removed; the
        // native signature keeps both flags so C++ stays untouched).
        val applyBlur = false
        val useNlvc = false
        val use6x6 = currentUseKeysInterpolator()
        val maskData = viewModel.roiMaskBytes ?: ByteArray(0)

        val debugDir = File(cacheDir, "dic_debug")
        if (!debugDir.exists()) debugDir.mkdirs()
        IndicVisionNativeLib.setDebugOutputDir(debugDir.absolutePath)

        lifecycleScope.launch(IndicVisionNativeLib.nativeDispatcher) {
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
                    processingStartTime = processingStartTime,
                )

                val outcome = viewModel.runBatchAnalysis(applicationContext, params) { progress ->
                    setComputeProgress(progress.percent)
                    setComputeStatus(progress.status)
                    runOnUiThread { overlayTitle.text = progress.status }
                    runOnUiThread {
                        progressBar.progress = progress.percent
                        tvTimer.text = progress.timerText
                        if (progress.pointsSolved >= 0) {
                            findViewById<TextView>(R.id.tvRunPoints).text =
                                String.format(java.util.Locale.US, "%,d", progress.pointsSolved)
                        }
                        if (progress.convergencePercent >= 0f) {
                            findViewById<TextView>(R.id.tvRunConvergence).text =
                                String.format(java.util.Locale.US, "%.1f%%", progress.convergencePercent)
                        }
                    }
                }

                val totalTime = outcome.executionTimeMs / 1000.0

                withContext(Dispatchers.Main) {
                    isProcessing = false
                    hideComputeOverlay()
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    progressBar.visibility = View.GONE

                    if (outcome.engineErrorCode == AnalysisViewModel.ERROR_CANCELLED) {
                        // User cancelled: stay on settings, nothing to report.
                        tvTimer.visibility = View.GONE
                        checkReady()
                    } else if (outcome.engineErrorCode == AnalysisViewModel.ERROR_SESSION_LIMIT) {
                        tvTimer.visibility = View.GONE
                        checkReady()
                        startActivity(Intent(this@StaticAnalysisActivity, SessionLimitActivity::class.java))
                    } else if (outcome.engineErrorCode < 0) {
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
                Timber.e(e, "Batch processing failed")
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    hideComputeOverlay()
                    progressBar.visibility = View.GONE
                    tvTimer.text = "Engine Error"
                    tvResult.text = "❌ Error: ${e.message}"
                    checkReady()
                }
            }
        }
    }

    /**
     * @param sweep true when the frames are parameter combinations rather than
     *   deformed images. The viewer needs each frame's own settings then — the
     *   step size alone changes how a frame renders — and names the frames
     *   after the combination instead of after an image file.
     */
    private fun openResultViewer(sweep: Boolean = false) {
        val plan = viewModel.sweepPlan
        // A sweep opens the interactive lattice first; it forwards these same
        // extras on to the result viewer when a node (or "View results") is
        // tapped, and stays on the back stack so Back returns to it.
        val target = if (sweep) VsgLatticeActivity::class.java else ResultViewerActivity::class.java
        val intent = Intent(this, target).apply {
            putExtra(DicKeys.IMG_W, viewModel.realRefWidth)
            putExtra(DicKeys.IMG_H, viewModel.realRefHeight)
            putExtra(DicKeys.STEP, viewModel.lastStep)
            putExtra(DicKeys.REF_NAME, viewModel.refName.removePrefix("Ref: "))

            // THE FIX: Pass the dynamic timestamped path stored in the ViewModel
            putExtra(DicKeys.REF_PATH, viewModel.lastRefPath ?: "")

            putExtra(DicKeys.DEF_PATH, viewModel.lastDefPath)
            putExtra(DicKeys.BATCH_DIR_PATH, viewModel.lastBatchDirPath)
            val frameNames = if (sweep) {
                plan.map { combinationLabel(it) }
            } else {
                viewModel.defFilePaths.map { it.substringAfterLast('/') }
            }
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, ArrayList(frameNames))
            putStringArrayListExtra(DicKeys.DEF_FILE_PATHS, ArrayList(viewModel.defFilePaths))

            if (sweep) {
                putExtra(DicKeys.SWEEP_SUBSETS, plan.map { it.subset }.toIntArray())
                putExtra(DicKeys.SWEEP_STEPS, plan.map { it.step }.toIntArray())
                putExtra(DicKeys.SWEEP_STRAIN_WINS, plan.map { it.strainWindow }.toIntArray())
                putExtra(DicKeys.LINE_CUT_HORIZONTAL, viewModel.lineCutHorizontal)
                // Skipped combinations show as hollow nodes on the lattice.
                val skipped = viewModel.sweepSkipped
                putExtra(DicKeys.SWEEP_SKIP_SUBSETS, skipped.map { it.subset }.toIntArray())
                putExtra(DicKeys.SWEEP_SKIP_STEPS, skipped.map { it.step }.toIntArray())
                putExtra(DicKeys.SWEEP_SKIP_STRAIN_WINS, skipped.map { it.strainWindow }.toIntArray())
            }

            // PDF GENERATOR DATA
            putExtra(DicKeys.SESSION_ID, viewModel.currentSessionId)
            putExtra(DicKeys.SESSION_LOCAL_ID, viewModel.workingLocalId)
            putExtra(DicKeys.SUBSET_SIZE, currentSubsetSize())
            putExtra(DicKeys.STRAIN_WINDOW, currentStrainWindow())
            putExtra(DicKeys.STRAIN_METHOD, "VSG")
            putExtra(DicKeys.ENGINE_STATS, viewModel.engineStatsArray)

            // PASSING ROI DATA FOR THE PDF REPORT
            putExtra(DicKeys.ROI_X, viewModel.roiX)
            putExtra(DicKeys.ROI_Y, viewModel.roiY)
            putExtra(DicKeys.ROI_W, viewModel.roiW)
            putExtra(DicKeys.ROI_H, viewModel.roiH)
        }
        startActivity(intent)
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

    /**
     * Snaps [raw] into [slider]'s range and onto its step grid. Subset size and
     * strain window use stepSize 2 from an odd valueFrom, so a typed even value
     * lands on the nearest odd one.
     */
    private fun snapToSlider(slider: Slider, raw: Int): Int {
        val from = slider.valueFrom.toInt()
        val to = slider.valueTo.toInt()
        val step = slider.stepSize.toInt().coerceAtLeast(1)
        val offset = raw.coerceIn(from, to) - from
        return (from + (offset + step / 2) / step * step).coerceIn(from, to)
    }

    /** Shows [value] in [field], unless the user is mid-edit in it. */
    private fun renderParamField(field: EditText, value: Int) {
        if (!field.hasFocus()) field.setText(value.toString())
    }

    /** Flushes any in-progress typing into the sliders (focus loss commits). */
    private fun commitParamFields() {
        tvSubsetValue.clearFocus()
        tvStepValue.clearFocus()
        tvStrainValue.clearFocus()
        if (::etSubsetMinValue.isInitialized) {
            etSubsetMinValue.clearFocus()
            etSubsetMaxValue.clearFocus()
            etVsgMaxValue.clearFocus()
        }
    }

    /**
     * Two-way binds a numeric field to its slider; commits on Done or focus
     * loss. [onUserChange] fires only when the commit actually moves the
     * slider, so tabbing through a field is not mistaken for an edit.
     */
    private fun bindParamField(field: EditText, slider: Slider, onUserChange: (() -> Unit)? = null) {
        val commit = {
            val previous = slider.value.toInt()
            val typed = field.text.toString().trim().toIntOrNull()
            val value = if (typed == null) previous else snapToSlider(slider, typed)
            slider.value = value.toFloat()
            field.setText(value.toString())
            field.setSelection(field.text.length)
            if (value != previous) onUserChange?.invoke()
            updateAdvancedSummary?.invoke()
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commit()
                field.clearFocus()
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
                true
            } else {
                false
            }
        }
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
    }

    // ------------------------------------------------------------------
    // Parameter sliders: the value fields are editable, so dragging writes
    // into them and typing writes back into the slider. Values are still
    // read via slider.value everywhere.
    // ------------------------------------------------------------------
    private fun setupParameterControls() {
        val updateLabels = {
            renderParamField(tvSubsetValue, etSubsetSize.value.toInt())
            renderParamField(tvStepValue, etStepSize.value.toInt())
            renderParamField(tvStrainValue, etStrainWindow.value.toInt())
            updateAdvancedSummary?.invoke()
        }
        updateLabels()

        bindParamField(tvSubsetValue, etSubsetSize) { viewModel.subsetUserModified = true }
        bindParamField(tvStepValue, etStepSize)
        bindParamField(tvStrainValue, etStrainWindow)

        // Advanced expander: collapsed by default; header toggles, summary
        // chip shows current values (+ "defaults" marker when untouched).
        val advancedBody = findViewById<View>(R.id.advancedParamsBody)
        val advancedChevron = findViewById<ImageView>(R.id.ivAdvancedChevron)
        val tvAdvancedSummary = findViewById<TextView>(R.id.tvAdvancedSummary)

        updateAdvancedSummary = {
            val s = currentSubsetSize()
            val st = currentStepSize()
            val w = currentStrainWindow()
            val isDefaults = s == defaultSubsetSize() &&
                st == 5 &&
                w == 15 &&
                !currentUseKeysInterpolator()
            tvAdvancedSummary.text = getString(R.string.advanced_summary_fmt, s, st, w) +
                if (isDefaults) getString(R.string.advanced_defaults_suffix) else ""
        }
        updateAdvancedSummary?.invoke()

        @Suppress("MagicNumber") // the documented defaults: 41 / 5 / 15
        findViewById<View>(R.id.btnAdvancedReset).setOnClickListener {
            // Drop focus first so the fields accept the reset values.
            commitParamFields()
            // Reset hands the subset back to the SSSIG recommendation when one
            // was measured for this reference image.
            viewModel.subsetUserModified = false
            etSubsetSize.value = defaultSubsetSize().toFloat()
            etStepSize.value = 5f
            etStrainWindow.value = 15f
            rgInterpolator.check(R.id.rbBicubic)
            updateAdvancedSummary?.invoke()
            // Reset also hands the sweep back to its suggested inputs.
            sweepUserModified = false
            seedSweepSuggestions()
        }

        fun infoDialog(titleRes: Int, bodyRes: Int): (View) -> Unit = {
            MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(bodyRes)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        findViewById<View>(R.id.btnSubsetInfo)
            .setOnClickListener(infoDialog(R.string.subset_size, R.string.info_subset))
        findViewById<View>(R.id.btnStepInfo)
            .setOnClickListener(infoDialog(R.string.step_size_density, R.string.info_step))
        findViewById<View>(R.id.btnStrainInfo)
            .setOnClickListener(infoDialog(R.string.strain_window, R.string.info_strain_window))

        findViewById<View>(R.id.advancedParamsHeader).setOnClickListener {
            val expanded = advancedBody.visibility == View.VISIBLE
            advancedBody.visibility = if (expanded) View.GONE else View.VISIBLE
            advancedChevron.rotation = if (expanded) 0f else 180f
        }

        etSubsetSize.addOnChangeListener { _, _, fromUser ->
            if (fromUser) viewModel.subsetUserModified = true
            updateLabels()
            // Moving the single-setting subset re-seeds the sweep's suggestion,
            // until the user sets their own sweep inputs.
            if (fromUser) onRecommendationChanged()
        }
        etStepSize.addOnChangeListener { _, _, _ -> updateLabels() }
        etStrainWindow.addOnChangeListener { _, _, _ -> updateLabels() }
    }

    // ------------------------------------------------------------------
    // Parameter sweep — the virtual strain gauge study of §5.4.5 of the DIC
    // Good Practices Guide. The user gives a subset *range* and a ceiling on
    // the VSG; [VsgStudy] derives the step sizes (1/6 to 1/3 of each subset)
    // and the strain windows that follow from VSG = (window - 1) * step + 1.
    // Every surviving combination is solved against one frame and lands in the
    // result viewer as its own specimen.
    // ------------------------------------------------------------------

    private fun setupSweepControls() {
        rgAnalysisMode = findViewById(R.id.rgAnalysisMode)
        advancedParamsCard = findViewById(R.id.advancedParamsCard)
        sweepBody = findViewById(R.id.sweepBody)
        rangeSubset = findViewById(R.id.rangeSubset)
        sliderVsgMax = findViewById(R.id.sliderVsgMax)
        sliderSubsetSamples = findViewById(R.id.sliderSubsetSamples)
        sliderVsgSamples = findViewById(R.id.sliderVsgSamples)
        sliderStepDepth = findViewById(R.id.sliderStepDepth)
        etSubsetMinValue = findViewById(R.id.etSubsetMinValue)
        etSubsetMaxValue = findViewById(R.id.etSubsetMaxValue)
        etVsgMaxValue = findViewById(R.id.etVsgMaxValue)
        tvSubsetSamplesValue = findViewById(R.id.tvSubsetSamplesValue)
        tvVsgSamplesValue = findViewById(R.id.tvVsgSamplesValue)
        tvStepDepthValue = findViewById(R.id.tvStepDepthValue)
        rgLineCutAxis = findViewById(R.id.rgLineCutAxis)
        btnPickSweepFrame = findViewById(R.id.btnPickSweepFrame)
        tvSweepPlan = findViewById(R.id.tvSweepPlan)
        lineCutPreview = findViewById(R.id.lineCutPreview)
        sweepLatticePreview = findViewById(R.id.sweepLatticePreview)
        btnRunSweep = findViewById(R.id.btnRunSweep)

        rgAnalysisMode.check(if (viewModel.sweepMode) R.id.rbModeSweep else R.id.rbModeSingle)
        rgAnalysisMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.sweepMode = checkedId == R.id.rbModeSweep
            // Leaving sweep mode while on the sweep page returns to settings.
            if (!viewModel.sweepMode && viewModel.wizardStep == 3) {
                goToStep(2, animate = true)
            } else {
                applyAnalysisModeUi()
                refreshSweepPlan()
            }
        }

        rgLineCutAxis.check(if (viewModel.lineCutHorizontal) R.id.rbAxisX else R.id.rbAxisY)
        rgLineCutAxis.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            viewModel.lineCutHorizontal = checkedId == R.id.rbAxisX
            refreshLineCutPreview()
        }

        btnPickSweepFrame.setOnClickListener {
            pickFrame(R.string.sweep_frame, resolvedSweepFrame()) { index ->
                viewModel.vsgFrameIndex = index
                refreshSweepPlan()
            }
        }

        btnRunSweep.setOnClickListener {
            commitParamFields()
            startVsgSweep()
        }

        val sweepSettingsBody = sweepBody
        val sweepChevron = findViewById<ImageView>(R.id.ivSweepSettingsChevron)
        findViewById<View>(R.id.sweepSettingsHeader).setOnClickListener {
            val expanded = sweepSettingsBody.visibility == View.VISIBLE
            sweepSettingsBody.visibility = if (expanded) View.GONE else View.VISIBLE
            sweepChevron.rotation = if (expanded) 0f else 180f
        }

        wireSweepControls()
        wireSweepInfoButtons()

        applyAnalysisModeUi()
        sliderSubsetSamples.value = viewModel.subsetSamples.toFloat()
        sliderVsgSamples.value = viewModel.vsgSamples.toFloat()
        sliderStepDepth.value = viewModel.stepDenominator.toFloat()
        seedSweepSuggestions()
    }

    /**
     * Single setting keeps Advanced + Compute on page 2. Parameter sweep hides
     * both and routes through Next → the sweep setup page.
     */
    private fun applyAnalysisModeUi() {
        val sweep = viewModel.sweepMode
        advancedParamsCard.visibility = if (sweep) View.GONE else View.VISIBLE
        btnCalculateFullField.visibility = if (sweep) View.GONE else View.VISIBLE
        updateWizardChrome()
        checkReady()
    }

    // ------------------------------------------------------------------
    // The three sweep inputs — min subset, max subset, Max VSG — are the
    // user's to set. The app suggests sensible starting values (see
    // [seedSweepSuggestions]); after that every edit funnels through a commit
    // that clamps it to safe bounds. Sliders and text fields share those
    // commits, so the two never disagree, and the slider ranges are fixed —
    // no cross-slider re-ranging, which is what used to re-enter the planner
    // mid-update.
    // ------------------------------------------------------------------

    private fun wireSweepControls() {
        // The range slider carries both subset ends; a thumb drag commits both.
        rangeSubset.addOnChangeListener { slider, _, fromUser ->
            onSliderInput(fromUser) { commitSubsetRange(slider.values[0].toInt(), slider.values[1].toInt()) }
        }
        sliderVsgMax.addOnChangeListener { _, value, fromUser ->
            onSliderInput(fromUser) { commitVsgMax(value.toInt()) }
        }
        sliderSubsetSamples.addOnChangeListener { _, value, fromUser ->
            onSliderInput(fromUser) {
                viewModel.subsetSamples = value.toInt()
                refreshSweepPlan()
            }
        }
        sliderVsgSamples.addOnChangeListener { _, value, fromUser ->
            onSliderInput(fromUser) {
                viewModel.vsgSamples = value.toInt()
                refreshSweepPlan()
            }
        }
        sliderStepDepth.addOnChangeListener { _, value, fromUser ->
            onSliderInput(fromUser) {
                viewModel.stepDenominator = value.toInt()
                refreshSweepPlan()
            }
        }

        wireSweepField(etSubsetMinValue, { viewModel.subsetMin }) { commitSubsetMin(it) }
        wireSweepField(etSubsetMaxValue, { viewModel.subsetMax }) { commitSubsetMax(it) }
        wireSweepField(etVsgMaxValue, { viewModel.vsgMax }) { commitVsgMax(it) }
    }

    /** Runs [body] for a genuine input, ignoring the echo of our own writes. */
    private inline fun onSliderInput(fromUser: Boolean, body: () -> Unit) {
        if (bindingSweep) return
        if (fromUser) sweepUserModified = true
        body()
    }

    /**
     * A numeric field the user can type into. Non-numeric input reverts to the
     * current value ([current]); a number is committed (and clamped) via
     * [commit]. Commit on Done or focus loss, mirroring the analysis fields.
     */
    private fun wireSweepField(field: EditText, current: () -> Int, commit: (Int) -> Unit) {
        // Commit on focus loss only. Done just drops focus, which fires this —
        // so the field is unfocused by the time we write the clamped value back,
        // and the displayed text always reflects what was actually applied.
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) return@setOnFocusChangeListener
            val typed = field.text.toString().trim().toIntOrNull()
            if (typed == null) {
                renderParamField(field, current())
            } else {
                sweepUserModified = true
                commit(typed)
            }
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                field.clearFocus()
                getSystemService(InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(field.windowToken, 0)
                true
            } else {
                false
            }
        }
    }

    /** Largest subset the sweep may use: the slider ceiling, capped to the ROI. */
    private fun effectiveSubsetCeiling(): Int =
        minOf(SubsetRecommender.MAX_SUBSET, maxSubsetForRoi())

    /** Rounds [raw] to an odd subset inside the slider's range. */
    private fun oddSubset(raw: Int): Int =
        raw.coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET) or 1

    /** Commits both ends of the subset range together (a range-slider drag). */
    private fun commitSubsetRange(rawLo: Int, rawHi: Int) {
        val ceiling = effectiveSubsetCeiling()
        val lo = oddSubset(rawLo).coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val hi = oddSubset(rawHi).coerceIn(lo, ceiling)
        viewModel.subsetMin = lo
        viewModel.subsetMax = hi
        writeSubsetRange(lo, hi)
        refreshSweepPlan()
    }

    /**
     * Min subset from its text field. Clamped to at least
     * [SubsetRecommender.MIN_SUBSET] and no more than the current max — typing
     * past the max simply pins it there.
     */
    private fun commitSubsetMin(raw: Int) {
        val currentMax = viewModel.subsetMax.takeIf { it > 0 } ?: effectiveSubsetCeiling()
        viewModel.subsetMin = oddSubset(raw).coerceAtMost(currentMax)
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        refreshSweepPlan()
    }

    /**
     * Max subset from its text field. Clamped to at least the min and no more
     * than the largest the image and ROI can hold — a huge typed value snaps
     * down to that ceiling.
     */
    private fun commitSubsetMax(raw: Int) {
        val floor = viewModel.subsetMin.coerceAtLeast(SubsetRecommender.MIN_SUBSET)
        viewModel.subsetMax = oddSubset(raw).coerceIn(floor, effectiveSubsetCeiling())
        writeSubsetRange(viewModel.subsetMin, viewModel.subsetMax)
        refreshSweepPlan()
    }

    /** Max VSG. Clamped to the reasonable band; a mistyped huge number snaps in. */
    private fun commitVsgMax(raw: Int) {
        viewModel.vsgMax = raw.coerceIn(VSG_MIN_INPUT, VSG_MAX_INPUT)
        writeField(sliderVsgMax, etVsgMaxValue, viewModel.vsgMax)
        refreshSweepPlan()
    }

    /** Reflects the subset range onto the range slider and both text fields. */
    private fun writeSubsetRange(lo: Int, hi: Int) {
        bindingSweep = true
        rangeSubset.values = listOf(
            lo.toFloat().coerceIn(rangeSubset.valueFrom, rangeSubset.valueTo),
            hi.toFloat().coerceIn(rangeSubset.valueFrom, rangeSubset.valueTo),
        )
        bindingSweep = false
        renderParamField(etSubsetMinValue, lo)
        renderParamField(etSubsetMaxValue, hi)
    }

    /** Reflects a committed value back onto its slider and field without echo. */
    private fun writeField(slider: Slider, field: EditText, value: Int) {
        bindingSweep = true
        slider.value = value.toFloat().coerceIn(slider.valueFrom, slider.valueTo)
        bindingSweep = false
        renderParamField(field, value)
    }

    /**
     * Seeds the sweep inputs with the app's suggestions — a subset window
     * centred on the SSSIG recommendation and a Max VSG a few times the subset.
     * Runs until the user edits a sweep control; after that their values stand.
     */
    private fun seedSweepSuggestions() {
        if (!::rangeSubset.isInitialized) return
        if (sweepUserModified) {
            refreshSweepPlan()
            return
        }
        val ceiling = effectiveSubsetCeiling()
        val rec = currentSubsetSize().coerceIn(SubsetRecommender.MIN_SUBSET, ceiling)
        val (lo, hi) = suggestedSubsetWindow(rec, ceiling)
        viewModel.subsetMin = lo
        viewModel.subsetMax = hi
        viewModel.vsgMax = (VSG_SUGGESTION_FACTOR * hi).coerceIn(VSG_MIN_INPUT, VSG_MAX_INPUT)
        writeSubsetRange(lo, hi)
        writeField(sliderVsgMax, etVsgMaxValue, viewModel.vsgMax)
        refreshSweepPlan()
    }

    /**
     * A subset window of [SUGGESTED_SUBSET_SPAN] centred on [rec], shifted whole
     * to fit inside `[MIN_SUBSET, ceiling]` so it never collapses to a single
     * value unless the valid range itself is that narrow.
     */
    private fun suggestedSubsetWindow(rec: Int, ceiling: Int): Pair<Int, Int> {
        val half = SUGGESTED_SUBSET_SPAN / 2
        var lo = rec - half
        var hi = rec + half
        if (lo < SubsetRecommender.MIN_SUBSET) {
            hi += SubsetRecommender.MIN_SUBSET - lo
            lo = SubsetRecommender.MIN_SUBSET
        }
        if (hi > ceiling) {
            lo -= hi - ceiling
            hi = ceiling
        }
        return oddSubset(lo.coerceAtLeast(SubsetRecommender.MIN_SUBSET)) to oddSubset(hi)
    }

    /** Re-seeds the sweep from the current recommendation when the user hasn't taken over. */
    private fun onRecommendationChanged() = seedSweepSuggestions()

    private fun wireSweepInfoButtons() {
        fun show(titleRes: Int, body: CharSequence): (View) -> Unit = {
            MaterialAlertDialogBuilder(this)
                .setTitle(titleRes)
                .setMessage(body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        findViewById<View>(R.id.btnSweepInfo)
            .setOnClickListener(show(R.string.analysis_mode, getString(R.string.info_analysis_mode)))
        findViewById<View>(R.id.btnSubsetRangeInfo)
            .setOnClickListener(show(R.string.subset_range, getString(R.string.info_subset_range)))
        findViewById<View>(R.id.btnVsgMaxInfo)
            .setOnClickListener(show(R.string.vsg_max, getString(R.string.info_vsg_max)))
        findViewById<View>(R.id.btnSamplesInfo)
            .setOnClickListener(show(R.string.subset_samples, getString(R.string.info_subset_samples)))
        findViewById<View>(R.id.btnStepDepthInfo)
            .setOnClickListener(show(R.string.step_depth, getString(R.string.info_step_depth)))
        findViewById<View>(R.id.btnLineCutInfo)
            .setOnClickListener(show(R.string.line_cut_axis, getString(R.string.info_line_cut_axis)))
    }

    /** Defaults to the last frame — the most deformed one in a monotonic test. */
    private fun resolvedSweepFrame(): Int {
        val last = maxOf(0, viewModel.defCount - 1)
        val stored = viewModel.vsgFrameIndex
        return if (stored < 0 || stored > last) last else stored
    }

    private fun frameLabel(index: Int): String =
        viewModel.defOriginalNames.getOrNull(index)?.substringAfterLast('/')
            ?: getString(R.string.sweep_frame_btn_fmt, index + 1)

    private fun pickFrame(titleRes: Int, current: Int, onPicked: (Int) -> Unit) {
        val labels = List(viewModel.defCount) { frameLabel(it) }.toTypedArray()
        if (labels.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setSingleChoiceItems(labels, current.coerceIn(labels.indices)) { dialog, which ->
                onPicked(which)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Largest subset the loaded image and ROI can actually hold.
     *
     * The engine drops any grid point whose subset box comes within
     * [ENGINE_EDGE_BUFFER_PX] of the image edge, and refuses a solve that
     * leaves no points at all. Capping the sweep here keeps it from asking for
     * a subset the ROI cannot fit and bailing out before the first solve.
     */
    private fun maxSubsetForRoi(): Int {
        val w = viewModel.realRefWidth
        val h = viewModel.realRefHeight
        if (w <= 0 || h <= 0) return SubsetRecommender.MAX_SUBSET
        val fits = if (viewModel.hasCustomRoi) {
            minOf(viewModel.roiW, viewModel.roiH) - 2 * ENGINE_EDGE_BUFFER_PX
        } else {
            // Full frame is inset by (subset/2 + slack) a side and must still be
            // one subset wide: imgW - 2*(s/2 + slack) >= s  =>  s <= imgW/2 - slack.
            minOf(w, h) / 2 - ROI_MARGIN_SLACK_PX
        }
        // Keep it odd and inside the slider's range.
        return (fits - 1 or 1).coerceIn(SubsetRecommender.MIN_SUBSET, SubsetRecommender.MAX_SUBSET)
    }

    /**
     * The sweep grid the current inputs describe, capped to the subsets the ROI
     * can hold: x subset sizes × y VSG sizes × z step sizes.
     */
    private fun currentPlan(): List<VsgStudy.Point> {
        val ceiling = maxSubsetForRoi()
        if (viewModel.subsetMin > ceiling) return emptyList()
        return VsgStudy.plan(
            subsetMin = viewModel.subsetMin,
            subsetMax = viewModel.subsetMax.coerceAtMost(ceiling),
            subsetSamples = viewModel.subsetSamples,
            vsgMax = viewModel.vsgMax,
            vsgSamples = viewModel.vsgSamples,
            stepDenominator = viewModel.stepDenominator,
        )
    }

    private fun refreshSweepPlan() {
        if (!::tvSweepPlan.isInitialized) return
        tvSubsetSamplesValue.text = sliderSubsetSamples.value.toInt().toString()
        tvVsgSamplesValue.text = sliderVsgSamples.value.toInt().toString()
        tvStepDepthValue.text = getString(R.string.step_depth_value_fmt, sliderStepDepth.value.toInt())
        btnPickSweepFrame.text = getString(R.string.sweep_frame_btn_fmt, resolvedSweepFrame() + 1)

        val plan = currentPlan()
        tvSweepPlan.text = when {
            plan.isNotEmpty() -> planSummary(plan)
            viewModel.subsetMin > maxSubsetForRoi() ->
                getString(R.string.sweep_plan_subset_too_big_fmt, maxSubsetForRoi())
            else -> getString(R.string.sweep_plan_empty)
        }
        refreshLatticePreview(plan)
        refreshLineCutPreview()
        checkReady()
    }

    /** Planned sweep lattice: every node drawn as filled (not yet run). */
    private fun refreshLatticePreview(plan: List<VsgStudy.Point>) {
        if (!::sweepLatticePreview.isInitialized) return
        sweepLatticePreview.onNodeClick = null
        sweepLatticePreview.setNodes(
            plan.map { point ->
                VsgLatticeView.Node(
                    subset = point.subset,
                    step = point.step,
                    window = point.strainWindow,
                    vsg = point.vsg,
                    solved = true,
                )
            },
        )
    }

    /** Centre-line cut over the reference image and current ROI. */
    private fun refreshLineCutPreview() {
        if (!::lineCutPreview.isInitialized) return
        val w = viewModel.realRefWidth
        val h = viewModel.realRefHeight
        if (w <= 0 || h <= 0) {
            lineCutPreview.setPreview(
                bitmap = null,
                imageW = 1,
                imageH = 1,
                roiX = 0,
                roiY = 0,
                roiW = 1,
                roiH = 1,
                horizontal = viewModel.lineCutHorizontal,
                maskBytes = null,
            )
            return
        }
        val roiX = if (viewModel.hasCustomRoi) viewModel.roiX else 0
        val roiY = if (viewModel.hasCustomRoi) viewModel.roiY else 0
        val roiW = if (viewModel.hasCustomRoi && viewModel.roiW > 0) viewModel.roiW else w
        val roiH = if (viewModel.hasCustomRoi && viewModel.roiH > 0) viewModel.roiH else h
        lineCutPreview.setPreview(
            bitmap = refPreviewBmp,
            imageW = w,
            imageH = h,
            roiX = roiX,
            roiY = roiY,
            roiW = roiW,
            roiH = roiH,
            horizontal = viewModel.lineCutHorizontal,
            maskBytes = viewModel.roiMaskBytes,
        )
    }

    /** "N analyses = X subset × Y VSG · step 1/D · subset 41–61 px" for a plan. */
    private fun planSummary(plan: List<VsgStudy.Point>): String = getString(
        R.string.sweep_plan_grid_fmt,
        plan.size,
        plan.map { it.subset }.distinct().size,
        viewModel.vsgSamples,
        viewModel.stepDenominator,
        plan.minOf { it.subset },
        plan.maxOf { it.subset },
    )

    /** Short per-combination label; becomes the frame name in viewer and report. */
    private fun combinationLabel(point: VsgStudy.Point): String = getString(
        R.string.sweep_frame_label_fmt,
        point.subset,
        point.step,
        point.strainWindow,
        point.vsg,
    )

    @Suppress("ReturnCount") // each precondition bails out on the spot
    private fun startVsgSweep() {
        if (!viewModel.isReadyToCompute()) return
        val plan = currentPlan()
        if (plan.isEmpty()) return
        // Every combination shares the ROI, so the largest subset has to fit it.
        val roi = resolveRoi(plan.maxOf { it.subset }) ?: return

        if (viewModel.wouldCreateNewSession(this)) {
            TokenStore.refreshSessionLimit(this, SessionStore.list(this).size)
            if (TokenStore.isSessionLimitReached(this)) {
                startActivity(Intent(this, SessionLimitActivity::class.java))
                return
            }
        }

        isProcessing = true
        checkReady()
        processingStartTime = System.currentTimeMillis()
        showComputeOverlay(getString(R.string.mode_sweep), planSummary(plan))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wireSweepCancelButton()

        val debugDir = File(cacheDir, "dic_debug").apply { mkdirs() }
        val use6x6 = currentUseKeysInterpolator()

        lifecycleScope.launch {
            val outcome = runCatching {
                val request = AnalysisViewModel.SweepRequest(
                    plan = plan,
                    labels = plan.map { combinationLabel(it) },
                    roi = roi,
                    use6x6 = use6x6,
                    debugDir = debugDir,
                )
                viewModel.runVsgSweep(applicationContext, request) { progress ->
                    showSweepProgress(progress)
                }
            }.onFailure { Timber.e(it, "Parameter sweep failed") }.getOrNull()

            isProcessing = false
            hideComputeOverlay()
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            progressBar.visibility = View.GONE
            checkReady()
            onSweepFinished(outcome)
        }
    }

    private fun wireSweepCancelButton() {
        findViewById<View>(R.id.btnRunCancel).apply {
            isEnabled = true
            setOnClickListener {
                MaterialAlertDialogBuilder(this@StaticAnalysisActivity)
                    .setTitle(R.string.cancel_run_title)
                    .setMessage(R.string.cancel_run_body)
                    .setPositiveButton(R.string.action_cancel) { _, _ ->
                        VsgStudyRunner.cancelRequested = true
                        isEnabled = false
                    }
                    .setNegativeButton(R.string.keep_running, null)
                    .show()
            }
        }
    }

    private fun showSweepProgress(progress: VsgStudyRunner.Progress) {
        setComputeProgress(progress.percent)
        setComputeStatus(
            getString(
                R.string.sweep_running_fmt,
                progress.runIndex + 1,
                progress.totalRuns,
                progress.point.subset,
                progress.point.step,
                progress.point.vsg,
            ),
        )
        runOnUiThread {
            overlayTitle.text = getString(R.string.mode_sweep)
            if (progress.pointsSolved > 0) {
                findViewById<TextView>(R.id.tvRunPoints).text =
                    String.format(java.util.Locale.US, "%,d", progress.pointsSolved)
            }
            if (progress.convergencePercent >= 0f) {
                findViewById<TextView>(R.id.tvRunConvergence).text =
                    String.format(java.util.Locale.US, "%.1f%%", progress.convergencePercent)
            }
        }
    }

    /**
     * A finished sweep is an ordinary session whose frames happen to be
     * settings rather than images, so it opens in the normal result viewer.
     */
    @Suppress("ReturnCount") // one branch per way a sweep can end
    private fun onSweepFinished(outcome: AnalysisViewModel.BatchAnalysisOutcome?) {
        if (outcome == null) {
            Toast.makeText(this, R.string.sweep_failed, Toast.LENGTH_LONG).show()
            return
        }
        if (outcome.engineErrorCode == AnalysisViewModel.ERROR_SESSION_LIMIT) {
            startActivity(Intent(this, SessionLimitActivity::class.java))
            return
        }
        if (outcome.totalFrames == 0) {
            // Nothing completed: a cancel is the user's own doing, anything
            // else is an engine failure the user needs the reason for.
            if (outcome.engineErrorCode != VsgStudyRunner.ERROR_CANCELLED) {
                showSweepFailureDialog(outcome.engineErrorCode)
            }
            return
        }
        val skipped = viewModel.sweepSkipped.size
        if (skipped > 0) {
            // Partial sweeps are still worth browsing; say what was dropped.
            Toast.makeText(
                this,
                getString(R.string.sweep_partial_fmt, skipped, skipped + outcome.totalFrames),
                Toast.LENGTH_LONG,
            ).show()
        }

        viewModel.lastDefPath = viewModel.defFilePaths.getOrNull(resolvedSweepFrame()) ?: ""
        viewModel.lastBatchDirPath = outcome.batchDirPath
        viewModel.hasCompletedAnalysis = true
        checkReady()
        // Stage the swept parameter space on the interactive lattice; it opens
        // the result viewer from there.
        openResultViewer(sweep = true)
    }

    /**
     * Why a sweep produced nothing. The engine's codes are the same ones a
     * single analysis reports, and every one of them points at the images or
     * the ROI rather than at the settings — so the message names the cause
     * instead of saying the sweep failed.
     */
    private fun showSweepFailureDialog(engineErrorCode: Int) {
        val reason = when (engineErrorCode) {
            ENGINE_ERROR_FEATURES -> R.string.sweep_fail_features
            ENGINE_ERROR_ROI -> R.string.sweep_fail_roi
            ENGINE_ERROR_INIT -> R.string.sweep_fail_init
            else -> R.string.sweep_fail_unknown
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.analysis_failed_title)
            .setMessage(getString(reason, engineErrorCode))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Wizard navigation: page 1 (images) → page 2 (settings) → page 3 (sweep)
    // ------------------------------------------------------------------
    private fun goToStep(step: Int, animate: Boolean) {
        val previous = viewModel.wizardStep
        // Sweep page only exists in parameter-sweep mode.
        val target = when {
            step >= 3 && !viewModel.sweepMode -> 2
            step < 1 -> 1
            else -> step.coerceAtMost(if (viewModel.sweepMode) 3 else 2)
        }
        viewModel.wizardStep = target

        // Reaching the settings page counts as reviewing the parameters —
        // they are all visible here — which satisfies the Compute gate.
        if (target >= 2) viewModel.settingsReviewed = true

        val pages = listOf(scrollStepImages, scrollStepSettings, scrollStepSweep)
        val showing = pages[target - 1]
        pages.forEach { page ->
            page.visibility = if (page === showing) View.VISIBLE else View.GONE
        }
        if (animate && previous != target) {
            val forward = target > previous
            showing.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(
                    this,
                    if (forward) R.anim.slide_in_right else R.anim.slide_in_left,
                ),
            )
        }

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).subtitle =
            getString(R.string.step_of_fmt, target, if (viewModel.sweepMode) 3 else 2)
        if (target == 2) {
            refreshInputsCard()
            updateRoiSummary()
            // Cheap no-op when the reference/ROI have not changed since the
            // last measurement; covers inputs that arrived before this page.
            requestSubsetRecommendation()
        }
        if (target == 3) {
            refreshSweepPlan()
        }

        updateWizardChrome()
        checkReady()
    }

    /** Bottom nav labels and visibility for the current wizard step + mode. */
    private fun updateWizardChrome() {
        if (!::btnNext.isInitialized) return
        val step = viewModel.wizardStep
        val sweep = viewModel.sweepMode
        when (step) {
            1 -> {
                btnNext.visibility = View.VISIBLE
                btnNext.setText(R.string.next_settings)
                btnBack.visibility = View.GONE
            }
            2 -> {
                btnBack.visibility = View.VISIBLE
                if (sweep) {
                    btnNext.visibility = View.VISIBLE
                    btnNext.setText(R.string.next_sweep)
                } else {
                    btnNext.visibility = View.GONE
                }
            }
            else -> {
                btnBack.visibility = View.VISIBLE
                btnNext.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------
    // Compute progress overlay control
    // ------------------------------------------------------------------
    private fun showComputeOverlay(
        title: String = "Computing Strain Field",
        status: String = "Initializing engine…",
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

        // Page-1 / page-2 (sweep) gate: Next stays disabled + faded until images are set
        val nextEnabled = ready && !isProcessing
        btnNext.isEnabled = nextEnabled
        btnNext.alpha = if (nextEnabled) 1.0f else 0.4f
        tvNextReason.text = when {
            viewModel.refBytes == null -> getString(R.string.next_reason_ref)
            viewModel.defFilePaths.isEmpty() -> getString(R.string.next_reason_def)
            else -> ""
        }

        // Frames that don't match the reference would reach the engine as a
        // silently degraded solve — say so instead, and hold Compute.
        val sizeError = viewModel.frameSizeError
        if (sizeError != null) tvResult.text = sizeError

        // Single-setting Compute: images + settings visited.
        val computeEnabled = ready &&
            viewModel.settingsReviewed &&
            !isProcessing &&
            sizeError == null &&
            !viewModel.sweepMode
        btnCalculateFullField.isEnabled = computeEnabled
        btnCalculateFullField.alpha = if (computeEnabled) 1.0f else 0.4f

        // Sweep Run: same gates plus a non-empty planned lattice.
        val sweepEnabled = ready &&
            viewModel.settingsReviewed &&
            !isProcessing &&
            sizeError == null &&
            viewModel.sweepMode &&
            currentPlan().isNotEmpty()
        if (::btnRunSweep.isInitialized) {
            btnRunSweep.isEnabled = sweepEnabled
            btnRunSweep.alpha = if (sweepEnabled) 1.0f else 0.4f
        }

        btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        btnBack.isEnabled = !isProcessing
        // Enabled/disabled visuals are handled by the Material theme —
        // no more hand-painted setBackgroundColor state juggling.
    }

    private fun restoreUiFromViewModel() {
        viewModel.refBytes?.let {
            refPreviewBmp = IndicVisionNativeLib.getPreviewFromBytes(it, 1000)
        }
        refreshRefSlot()
        refreshDefSlot()
        checkReady()
        // Survives rotation: the measurement is already in the ViewModel.
        applySubsetRecommendation()
    }

    /** Reference slot: dropzone when empty, summary card when filled. */
    private fun refreshRefSlot() {
        val hasRef = viewModel.refBytes != null
        refDropzone.visibility = if (hasRef) View.GONE else View.VISIBLE
        refCard.visibility = if (hasRef) View.VISIBLE else View.GONE
        if (hasRef) {
            tvRefName.text = viewModel.refName.removePrefix("Ref: ")
            tvRefMeta.text = getString(
                R.string.reference_meta_fmt,
                viewModel.realRefWidth,
                viewModel.realRefHeight,
            )
            refPreviewBmp?.let { ivRefThumb.setImageBitmap(it) }
        }
        updateJpegChip()
    }

    /** Deformed slot: dropzone when empty, count card when filled. */
    private fun refreshDefSlot() {
        val n = viewModel.defFilePaths.size
        defDropzone.visibility = if (n > 0) View.GONE else View.VISIBLE
        defCard.visibility = if (n > 0) View.VISIBLE else View.GONE
        if (n > 0) {
            tvDefName.text = getString(R.string.def_count_fmt, n)
            val first = viewModel.defFilePaths.first().substringAfterLast('/')
            val last = viewModel.defFilePaths.last().substringAfterLast('/')
            tvDefMeta.text = if (n == 1) first else "$first … $last"
            // Match the icon to what the user actually picked — the frames are
            // image files either way, so only the source tells them apart.
            ivDefIcon.setImageResource(
                if (viewModel.defFromVideo) R.drawable.ic_video else R.drawable.ic_photos_share,
            )
        }
        updateJpegChip()
    }

    /** Confirm-settings inputs summary card. */
    private fun refreshInputsCard() {
        tvInputsTitle.text = viewModel.refName.removePrefix("Ref: ")
        tvInputsMeta.text = getString(R.string.inputs_meta_fmt, viewModel.defFilePaths.size)
        refPreviewBmp?.let { ivInputsThumb.setImageBitmap(it) }
    }

    /** ROI card subtitle reflecting the current selection. */
    private fun updateRoiSummary() {
        tvInstruction.text = if (!viewModel.hasCustomRoi) {
            getString(R.string.roi_full_fmt, viewModel.realRefWidth, viewModel.realRefHeight)
        } else {
            getString(
                R.string.roi_custom_fmt,
                viewModel.roiW,
                viewModel.roiH,
                viewModel.roiX,
                viewModel.roiY,
            )
        }
        refreshLineCutPreview()
    }

    /** Inline, non-blocking JPEG accuracy warning. */
    private fun updateJpegChip() {
        val jpeg = viewModel.refName.endsWith(".jpg", true) ||
            viewModel.refName.endsWith(".jpeg", true) ||
            viewModel.defFilePaths.any { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
        jpegWarnRow.visibility = if (jpeg) View.VISIBLE else View.GONE
    }
}
