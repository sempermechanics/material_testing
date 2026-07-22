package com.rafad.indicvisiondic.ui.analysis
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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

    // Two-step wizard: page 1 = load images, page 2 = settings + run
    private lateinit var scrollStepImages: View
    private lateinit var scrollStepSettings: View
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

        // --- Two-step wizard wiring ---
        scrollStepImages = findViewById(R.id.scrollStepImages)
        scrollStepSettings = findViewById(R.id.scrollStepSettings)
        btnNext = findViewById(R.id.btnNext)
        btnBack = findViewById(R.id.btnBack)

        btnNext.setOnClickListener { goToStep(2, animate = true) }
        btnBack.setOnClickListener { goToStep(1, animate = true) }
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
                checkReady()
                requestSubsetRecommendation()
            }
        }

        btnCalculateFullField.setOnClickListener {
            // A field still holding focus has not committed its typed value yet.
            commitParamFields()
            startBatchAnalysis()
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
    }

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

    private fun openResultViewer() {
        val intent = Intent(this, ResultViewerActivity::class.java).apply {
            putExtra(DicKeys.IMG_W, viewModel.realRefWidth)
            putExtra(DicKeys.IMG_H, viewModel.realRefHeight)
            putExtra(DicKeys.STEP, viewModel.lastStep)
            putExtra(DicKeys.REF_NAME, viewModel.refName.removePrefix("Ref: "))

            // THE FIX: Pass the dynamic timestamped path stored in the ViewModel
            putExtra(DicKeys.REF_PATH, viewModel.lastRefPath ?: "")

            putExtra(DicKeys.DEF_PATH, viewModel.lastDefPath)
            putExtra(DicKeys.BATCH_DIR_PATH, viewModel.lastBatchDirPath)
            putStringArrayListExtra(DicKeys.DEF_FILE_NAMES, ArrayList(viewModel.defFilePaths.map { it.substringAfterLast('/') }))
            putStringArrayListExtra(DicKeys.DEF_FILE_PATHS, ArrayList(viewModel.defFilePaths))

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

    @Suppress("UnusedPrivateMember", "unused") // P5: rehome inside the ROI editor
    private fun handleMaskSelection(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                viewModel.roiMaskBytes = stream.readBytes()
                Toast.makeText(this, R.string.mask_uploaded, Toast.LENGTH_SHORT).show()
                viewModel.hasCustomRoi = true
                checkReady()
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load ROI mask")
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

    /** Flushes any in-progress typing into the sliders (focus loss commits). */
    private fun commitParamFields() {
        tvSubsetValue.clearFocus()
        tvStepValue.clearFocus()
        tvStrainValue.clearFocus()
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
        // Never overwrite a field mid-edit; its own commit handles that.
        val render = { field: EditText, value: Int ->
            if (!field.hasFocus()) field.setText(value.toString())
        }
        val updateLabels = {
            render(tvSubsetValue, etSubsetSize.value.toInt())
            render(tvStepValue, etStepSize.value.toInt())
            render(tvStrainValue, etStrainWindow.value.toInt())
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
            val isDefaults = s == defaultSubsetSize() && st == 5 && w == 15 &&
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
        }
        etStepSize.addOnChangeListener { _, _, _ -> updateLabels() }
        etStrainWindow.addOnChangeListener { _, _, _ -> updateLabels() }
    }

    // ------------------------------------------------------------------
    // Wizard navigation: page 1 (images)  page 2 (settings + run)
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
                    this,
                    if (forward) R.anim.slide_in_right else R.anim.slide_in_left,
                ),
            )
        }

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).subtitle =
            getString(R.string.step_of_fmt, step)
        if (forward) {
            refreshInputsCard()
            updateRoiSummary()
            // Cheap no-op when the reference/ROI have not changed since the
            // last measurement; covers inputs that arrived before this page.
            requestSubsetRecommendation()
        }

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

        // Page-1 gate: Next stays disabled + visibly faded until both images are set
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

        // Page-2 gate: Compute needs images AND the settings page visited
        val computeEnabled = ready && viewModel.settingsReviewed && !isProcessing && sizeError == null
        btnCalculateFullField.isEnabled = computeEnabled
        btnCalculateFullField.alpha = if (computeEnabled) 1.0f else 0.4f

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
    }

    /** Inline, non-blocking JPEG accuracy warning. */
    private fun updateJpegChip() {
        val jpeg = viewModel.refName.endsWith(".jpg", true) ||
            viewModel.refName.endsWith(".jpeg", true) ||
            viewModel.defFilePaths.any { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
        jpegWarnRow.visibility = if (jpeg) View.VISIBLE else View.GONE
    }

    // P5: rehome inside the ROI editor
    @Suppress("UnusedPrivateMember", "unused", "LongMethod", "CyclomaticComplexMethod")
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
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, shapes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
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
            var finalX = 0
            var finalY = 0
            var finalW = 0
            var finalH = 0
            var requiresMask = false

            val maskBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(maskBitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }

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
                    val cx = dialogView.findViewById<TextInputEditText>(R.id.etCircCx).text.toString().toFloatOrNull() ?: (imgW / 2f)
                    val cy = dialogView.findViewById<TextInputEditText>(R.id.etCircCy).text.toString().toFloatOrNull() ?: (imgH / 2f)
                    val r = dialogView.findViewById<TextInputEditText>(R.id.etCircR).text.toString().toFloatOrNull() ?: 100f

                    canvas.drawCircle(cx, cy, r, paint)

                    finalX = (cx - r).toInt()
                    finalY = (cy - r).toInt()
                    finalW = (r * 2).toInt()
                    finalH = (r * 2).toInt()
                }
                2 -> {
                    requiresMask = true
                    val cx = dialogView.findViewById<TextInputEditText>(R.id.etEllCx).text.toString().toFloatOrNull() ?: (imgW / 2f)
                    val cy = dialogView.findViewById<TextInputEditText>(R.id.etEllCy).text.toString().toFloatOrNull() ?: (imgH / 2f)
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

                    val path = Path().apply {
                        moveTo(x1, y1)
                        lineTo(x2, y2)
                        lineTo(x3, y3)
                        close()
                    }
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
                        imgW,
                        imgH,
                        finalX + finalW,
                        finalY + finalH,
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

                viewModel.roiX = finalX
                viewModel.roiY = finalY
                viewModel.roiW = finalW
                viewModel.roiH = finalH
                viewModel.hasCustomRoi = true

                checkReady()
                dialog.dismiss()
            }
        }
    }
}
