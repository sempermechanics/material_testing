// Analysis wizard Activity: it orchestrates the whole two/three-page setup flow
// (image/video import, ROI, parameters, sweep, launch), so its size, per-control
// methods, literal UI constants and broad import guards are inherent here.
@file:Suppress(
    "MagicNumber",
    "MaxLineLength",
    "LongMethod",
    "CyclomaticComplexMethod",
    "TooManyFunctions",
    "LargeClass",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

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
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

        // Native engine failure codes, shared with the single-analysis path.
        const val ENGINE_ERROR_FEATURES = -1
        const val ENGINE_ERROR_ROI = -2
        const val ENGINE_ERROR_INIT = -3
    }

    private val viewModel: AnalysisViewModel by viewModels()

    // UI Components
    private lateinit var btnDefineRoi: Button
    private lateinit var tvResult: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var tvRefName: TextView
    private lateinit var tvDefName: TextView
    private lateinit var etSubsetSize: Slider
    private lateinit var etStepSize: Slider
    private lateinit var etStrainWindow: Slider
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
    private lateinit var overlayHelper: ComputeOverlayHelper
    private lateinit var tvRunPoints: TextView
    private lateinit var tvRunConvergence: TextView
    private lateinit var rgInterpolator: MaterialButtonToggleGroup

    // Editable value fields for the parameter sliders (typing and dragging
    // both drive the same slider value)
    private lateinit var tvSubsetValue: EditText
    private lateinit var tvStepValue: EditText
    private lateinit var tvStrainValue: EditText

    /** Inline note carrying the SSSIG-based subset suggestion. */
    private lateinit var tvSubsetHint: TextView

    // Three-step wizard: images → settings → (sweep setup when Parameter sweep)
    private lateinit var scrollStepImages: View
    private lateinit var scrollStepSettings: View
    private lateinit var scrollStepSweep: View
    private lateinit var btnNext: Button
    private lateinit var btnBack: Button
    private lateinit var wizardChrome: AnalysisWizardChrome
    private lateinit var sweepHelper: SweepSetupHelper

    // State
    private var isProcessing = false

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
                        MaterialAlertDialogBuilder(this@StaticAnalysisActivity)
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

        tvRunPoints = findViewById(R.id.tvRunPoints)
        tvRunConvergence = findViewById(R.id.tvRunConvergence)
        overlayHelper = ComputeOverlayHelper(
            overlay = findViewById(R.id.computeOverlay),
            title = findViewById(R.id.overlayTitle),
            progress = findViewById(R.id.overlayProgress),
            percent = findViewById(R.id.overlayPercent),
            status = findViewById(R.id.overlayStatus),
            elapsed = findViewById(R.id.overlayElapsed),
            runPoints = tvRunPoints,
            runConvergence = tvRunConvergence,
        )
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
        rgInterpolator = findViewById(R.id.rgInterpolator)

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

        wizardChrome = AnalysisWizardChrome(
            activity = this,
            scrollStepImages = scrollStepImages,
            scrollStepSettings = scrollStepSettings,
            scrollStepSweep = scrollStepSweep,
            btnNext = btnNext,
            btnBack = btnBack,
            btnCalculateFullField = btnCalculateFullField,
            btnRunSweep = findViewById(R.id.btnRunSweep),
            toolbar = findViewById(R.id.toolbar),
        )

        sweepHelper = SweepSetupHelper(
            activity = this,
            viewModel = viewModel,
            callbacks = object : SweepSetupHelper.Callbacks {
                override fun goToStep(step: Int, animate: Boolean) =
                    this@StaticAnalysisActivity.goToStep(step, animate)
                override fun updateWizardChrome() =
                    wizardChrome.updateBottomNav(viewModel.wizardStep, viewModel.sweepMode)
                override fun checkReady() = this@StaticAnalysisActivity.checkReady()
                override fun showInfo(titleRes: Int, bodyRes: Int) =
                    this@StaticAnalysisActivity.showInfo(titleRes, bodyRes)
                override fun commitParamFields() =
                    this@StaticAnalysisActivity.commitParamFields()
                override fun startVsgSweep() = this@StaticAnalysisActivity.startVsgSweep()
                override fun currentSubsetSize() = this@StaticAnalysisActivity.currentSubsetSize()
                override fun maxSubsetForRoi() = this@StaticAnalysisActivity.maxSubsetForRoi()
                override fun refPreviewBitmap() = refPreviewBmp
                override fun renderParamField(field: EditText, value: Int) =
                    this@StaticAnalysisActivity.renderParamField(field, value)
            },
        )
        // After the wizard views exist: the sweep controls call checkReady().
        sweepHelper.setup()

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

        // Keyboard: the settings/sweep pages hold number fields; pad their scroll
        // viewports by the IME inset so a focused field scrolls clear of the
        // keyboard instead of hiding behind it.
        Insets.padImeBottom(findViewById(R.id.scrollStepSettings))
        Insets.padImeBottom(findViewById(R.id.scrollStepSweep))

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

                    // Load the freeform ROI mask RoiDrawActivity wrote to disk.
                    val maskPath = data.getStringExtra(DicKeys.MASK_FILE_PATH)
                    if (maskPath != null) {
                        val file = File(maskPath)
                        if (file.exists()) {
                            viewModel.roiMaskBytes = file.readBytes()
                        }
                    }

                    // A selection covering the whole image counts as no custom ROI.
                    viewModel.hasCustomRoi =
                        !(viewModel.roiW == viewModel.realRefWidth && viewModel.roiH == viewModel.realRefHeight)
                    updateRoiSummary()

                    sweepHelper.refreshLineCutPreview()
                    checkReady()
                    requestSubsetRecommendation()
                }
            } else {
                // Cancelled editor → fall back to full-image ROI.
                applyFullImageRoi()
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
                    Timber.e(e, "Failed to write temp ROI reference file")
                    Toast.makeText(this, R.string.failed_save_temp_file, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, R.string.load_image_first, Toast.LENGTH_SHORT).show()
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

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val loaded = contentResolver.openInputStream(uri)?.use { stream ->
                    loadReferenceFromStream(stream, isRaw)
                }
                if (loaded == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@StaticAnalysisActivity,
                            if (isRaw) R.string.failed_decode_raw else R.string.failed_load_reference,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    viewModel.realRefWidth = loaded.width
                    viewModel.realRefHeight = loaded.height
                    viewModel.refName = name
                    viewModel.refBytes = loaded.bytes
                    refPreviewBmp = loaded.preview
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
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@StaticAnalysisActivity, R.string.failed_load_reference, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private data class LoadedReference(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val preview: Bitmap?,
    )

    /** Decode / dimension / preview work for a reference pick. Runs off Main. */
    private suspend fun loadReferenceFromStream(
        stream: java.io.InputStream,
        isRaw: Boolean,
    ): LoadedReference? {
        if (isRaw) {
            val decoded = com.rafad.indicvisiondic.ui.common.BitmapDecode.rgbaAndPreviewFromStream(stream)
                ?: return null
            return LoadedReference(decoded.rgba, decoded.width, decoded.height, decoded.preview)
        }

        val bytes = stream.readBytes()
        return withContext(IndicVisionNativeLib.nativeDispatcher) {
            val dims = IndicVisionNativeLib.getImageDimensions(bytes)
            val preview = IndicVisionNativeLib.getPreviewFromBytes(
                bytes,
                com.rafad.indicvisiondic.ui.common.BitmapDecode.PREVIEW_MAX_EDGE,
            )
            LoadedReference(bytes, dims[0], dims[1], preview)
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

    private fun handleDeformedBatch(rawUris: List<Uri>) {
        // Frame cap (Home settings drawer): keep the first N and say so.
        val cap = DicSettings.maxFrames(this)
        val uris = if (rawUris.size > cap) {
            Toast.makeText(this, getString(R.string.frames_capped_fmt, cap), Toast.LENGTH_LONG).show()
            FrameImportHelper.cappedUris(rawUris, cap)
        } else {
            rawUris
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvResult.text = "Caching images..."
                }

                viewModel.clearPreviousResults()

                val batch = FrameImportHelper.importDeformedUris(
                    context = this@StaticAnalysisActivity,
                    uris = uris,
                    cacheDir = cacheDir,
                    displayName = ::getFileName,
                )
                if (batch != null) {
                    viewModel.defFilePaths = batch.filePaths
                    viewModel.defOriginalNames = batch.originalNames
                    viewModel.defFrameSizes = batch.frameSizes
                    viewModel.defFromVideo = batch.fromVideo
                } else {
                    viewModel.defFilePaths = emptyList()
                    viewModel.defOriginalNames = emptyList()
                    viewModel.defFrameSizes = emptyMap()
                    viewModel.defFromVideo = false
                }

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
    private fun handleVideo(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val meta = VideoFrameExtractor.readMeta(this@StaticAnalysisActivity, uri)

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
        info.add(VideoFrameExtractor.formatClock(meta.durationMs))
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
        tvSegment.text = "${VideoFrameExtractor.formatClock(0)} – ${VideoFrameExtractor.formatClock(meta.durationMs)}"

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
            tvSegment.text = "${VideoFrameExtractor.formatClock(startMs)} – ${VideoFrameExtractor.formatClock(endMs)}"
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
        overlayHelper.processingStartTime = System.currentTimeMillis()
        overlayHelper.show(title = "Extracting Frames", status = "Reading video…")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                viewModel.clearPreviousResults()
                val result = VideoFrameExtractor.extract(
                    context = this@StaticAnalysisActivity,
                    uri = uri,
                    fpsExtract = fpsExtract,
                    startMs = startMs,
                    endMs = endMs,
                    maxFrames = DicSettings.maxFrames(this@StaticAnalysisActivity),
                    cacheDir = cacheDir,
                    onProgress = { percent, status ->
                        overlayHelper.update(percent = percent, status = status)
                    },
                )

                withContext(Dispatchers.Main) {
                    overlayHelper.hide()
                    if (result == null) {
                        Toast.makeText(this@StaticAnalysisActivity, R.string.video_extract_insufficient, Toast.LENGTH_LONG).show()
                        return@withContext
                    }

                    viewModel.realRefWidth = result.refWidth
                    viewModel.realRefHeight = result.refHeight
                    viewModel.refBytes = result.refPng
                    viewModel.refName = result.refName
                    if (!viewModel.hasCustomRoi) {
                        viewModel.roiX = 0
                        viewModel.roiY = 0
                        viewModel.roiW = result.refWidth
                        viewModel.roiH = result.refHeight
                    }
                    viewModel.defFilePaths = result.batch.filePaths
                    viewModel.defOriginalNames = result.batch.originalNames
                    viewModel.defFrameSizes = result.batch.frameSizes
                    viewModel.defFromVideo = result.batch.fromVideo

                    result.refPreview?.let { refPreviewBmp = it }
                    refreshRefSlot()
                    refreshDefSlot()
                    validateFrameSizes()
                    checkReady()
                    requestSubsetRecommendation()
                    Toast.makeText(
                        this@StaticAnalysisActivity,
                        getString(R.string.video_loaded_frames, result.batch.filePaths.size),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error extracting video frames")
                withContext(Dispatchers.Main) {
                    overlayHelper.hide()
                    Toast.makeText(this@StaticAnalysisActivity, getString(R.string.video_read_error, e.message), Toast.LENGTH_LONG).show()
                }
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
        sweepHelper.onRecommendationChanged()
    }

    /**
     * The rectangle the engine solves over, as `[x, y, w, h]`: the drawn ROI,
     * or the whole frame inset by half a subset (plus slack) so no subset hangs
     * off the edge. Null — with the user told why — when it cannot hold one
     * subset.
     */
    private fun resolveRoi(subset: Int): IntArray? {
        val roi = RoiResolveHelper.resolve(
            subset = subset,
            hasCustomRoi = viewModel.hasCustomRoi,
            roiX = viewModel.roiX,
            roiY = viewModel.roiY,
            roiW = viewModel.roiW,
            roiH = viewModel.roiH,
            realRefWidth = viewModel.realRefWidth,
            realRefHeight = viewModel.realRefHeight,
        )
        if (roi == null) {
            Toast.makeText(this, R.string.roi_too_small, Toast.LENGTH_LONG).show()
        }
        return roi
    }

    /** Largest odd subset the loaded image and ROI can hold. */
    private fun maxSubsetForRoi(): Int = RoiResolveHelper.maxSubsetForRoi(
        hasCustomRoi = viewModel.hasCustomRoi,
        roiW = viewModel.roiW,
        roiH = viewModel.roiH,
        realRefWidth = viewModel.realRefWidth,
        realRefHeight = viewModel.realRefHeight,
    )

    private fun startBatchAnalysis() {
        if (!viewModel.isReadyToCompute()) return

        val subset = currentSubsetSize()
        val step = currentStepSize()
        val strainWin = currentStrainWindow()

        val roi = resolveRoi(subset) ?: return
        val finalRectX = roi[0]
        val finalRectY = roi[1]
        val finalRectW = roi[2]
        val finalRectH = roi[3]

        // Hard stop: do not start a new analysis when the session quota is full.
        // Re-runs that update an existing Home row are still allowed.
        if (!ensureSessionQuota()) return

        isProcessing = true
        checkReady()
        overlayHelper.processingStartTime = System.currentTimeMillis()
        overlayHelper.show()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wireCancelButton { viewModel.cancelRequested = true }

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
                    processingStartTime = overlayHelper.processingStartTime,
                )

                val outcome = viewModel.runBatchAnalysis(applicationContext, params) { progress ->
                    overlayHelper.update(
                        percent = progress.percent,
                        status = progress.status,
                        title = progress.status,
                        pointsSolved = progress.pointsSolved,
                        convergencePercent = progress.convergencePercent,
                    )
                }

                withContext(Dispatchers.Main) {
                    isProcessing = false
                    overlayHelper.hide()
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                    if (outcome.engineErrorCode == AnalysisRunCodes.ERROR_CANCELLED) {
                        // User cancelled: stay on settings, nothing to report.
                        checkReady()
                    } else if (outcome.engineErrorCode == AnalysisRunCodes.ERROR_SESSION_LIMIT) {
                        checkReady()
                        startActivity(Intent(this@StaticAnalysisActivity, SessionLimitActivity::class.java))
                    } else if (outcome.engineErrorCode < 0) {
                        val errorMsg = engineFailureMessage(outcome.engineErrorCode)
                        tvResult.text = "❌ Error: $errorMsg"
                        showEngineFailureDialog(outcome.engineErrorCode, R.string.analysis_failed_title)
                    } else if (outcome.firstFrameValidPoints <= 0) {
                        tvResult.text = "❌ Engine returned no data"
                    } else {
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
                    overlayHelper.hide()
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
            putExtra(DicKeys.REF_NAME, viewModel.refName)

            // The persisted per-session reference copy, not the picked source.
            putExtra(DicKeys.REF_PATH, viewModel.lastRefPath ?: "")

            putExtra(DicKeys.DEF_PATH, viewModel.lastDefPath)
            putExtra(DicKeys.BATCH_DIR_PATH, viewModel.lastBatchDirPath)
            val frameNames = if (sweep) {
                plan.map { sweepHelper.combinationLabel(it) }
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
        if (::sweepHelper.isInitialized) sweepHelper.clearSweepFieldFocus()
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
            if (::sweepHelper.isInitialized) {
                sweepHelper.resetUserModified()
                sweepHelper.seedSweepSuggestions()
            }
        }

        findViewById<View>(R.id.btnSubsetInfo)
            .setOnClickListener { showInfo(R.string.subset_size, R.string.info_subset) }
        findViewById<View>(R.id.btnStepInfo)
            .setOnClickListener { showInfo(R.string.step_size_density, R.string.info_step) }
        findViewById<View>(R.id.btnStrainInfo)
            .setOnClickListener { showInfo(R.string.strain_window, R.string.info_strain_window) }

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
            if (fromUser && ::sweepHelper.isInitialized) sweepHelper.onRecommendationChanged()
        }
        etStepSize.addOnChangeListener { _, _, _ -> updateLabels() }
        etStrainWindow.addOnChangeListener { _, _, _ -> updateLabels() }
    }

    // ------------------------------------------------------------------
    @Suppress("ReturnCount") // each precondition bails out on the spot
    private fun startVsgSweep() {
        if (!viewModel.isReadyToCompute()) return
        val plan = sweepHelper.currentPlan()
        if (plan.isEmpty()) return
        // Every combination shares the ROI, so the largest subset has to fit it.
        val roi = resolveRoi(plan.maxOf { it.subset }) ?: return

        if (!ensureSessionQuota()) return

        isProcessing = true
        checkReady()
        overlayHelper.processingStartTime = System.currentTimeMillis()
        overlayHelper.show(getString(R.string.mode_sweep), sweepHelper.planSummary(plan))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wireCancelButton { VsgStudyRunner.cancelRequested = true }

        val debugDir = File(cacheDir, "dic_debug").apply { mkdirs() }
        val use6x6 = currentUseKeysInterpolator()

        lifecycleScope.launch {
            val outcome = runCatching {
                val request = AnalysisViewModel.SweepRequest(
                    plan = plan,
                    labels = plan.map { sweepHelper.combinationLabel(it) },
                    roi = roi,
                    use6x6 = use6x6,
                    debugDir = debugDir,
                )
                viewModel.runVsgSweep(applicationContext, request) { progress ->
                    showSweepProgress(progress)
                }
            }.onFailure { Timber.e(it, "Parameter sweep failed") }.getOrNull()

            isProcessing = false
            overlayHelper.hide()
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            checkReady()
            onSweepFinished(outcome)
        }
    }

    private fun showSweepProgress(progress: VsgStudyRunner.Progress) {
        overlayHelper.update(
            percent = progress.percent,
            status = getString(
                R.string.sweep_running_fmt,
                progress.runIndex + 1,
                progress.totalRuns,
                progress.point.subset,
                progress.point.step,
                progress.point.vsg,
            ),
            title = getString(R.string.mode_sweep),
            pointsSolved = if (progress.pointsSolved > 0) progress.pointsSolved else -1,
            convergencePercent = progress.convergencePercent,
        )
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
        if (outcome.engineErrorCode == AnalysisRunCodes.ERROR_SESSION_LIMIT) {
            startActivity(Intent(this, SessionLimitActivity::class.java))
            return
        }
        if (outcome.totalFrames == 0) {
            // Nothing completed: a cancel is the user's own doing, anything
            // else is an engine failure the user needs the reason for.
            if (outcome.engineErrorCode != AnalysisRunCodes.ERROR_CANCELLED) {
                showEngineFailureDialog(outcome.engineErrorCode, R.string.sweep_fail_title)
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

        viewModel.lastDefPath = viewModel.defFilePaths.getOrNull(sweepHelper.resolvedSweepFrame()) ?: ""
        viewModel.lastBatchDirPath = outcome.batchDirPath
        viewModel.hasCompletedAnalysis = true
        checkReady()
        // Stage the swept parameter space on the interactive lattice; it opens
        // the result viewer from there.
        openResultViewer(sweep = true)
    }

    /**
     * Why a run produced nothing. The engine's codes are the same for single
     * analysis and sweep; messages reuse the sweep-path string resources.
     */
    private fun engineFailureMessage(engineErrorCode: Int): String {
        val reason = when (engineErrorCode) {
            ENGINE_ERROR_FEATURES -> R.string.sweep_fail_features
            ENGINE_ERROR_ROI -> R.string.sweep_fail_roi
            ENGINE_ERROR_INIT -> R.string.sweep_fail_init
            else -> R.string.sweep_fail_unknown
        }
        return getString(reason, engineErrorCode)
    }

    private fun showEngineFailureDialog(engineErrorCode: Int, titleRes: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(engineFailureMessage(engineErrorCode))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Hard stop for a new session when quota is full. Returns false after
     * navigating to the limit screen; re-runs of an existing session still pass.
     */
    private fun ensureSessionQuota(): Boolean {
        if (!viewModel.wouldCreateNewSession(this)) return true
        TokenStore.refreshSessionLimit(this, SessionStore.list(this).size)
        if (!TokenStore.isSessionLimitReached(this)) return true
        startActivity(Intent(this, SessionLimitActivity::class.java))
        return false
    }

    private fun wireCancelButton(onConfirm: () -> Unit) {
        findViewById<View>(R.id.btnRunCancel).apply {
            isEnabled = true
            setOnClickListener {
                MaterialAlertDialogBuilder(this@StaticAnalysisActivity)
                    .setTitle(R.string.cancel_run_title)
                    .setMessage(R.string.cancel_run_body)
                    .setPositiveButton(R.string.action_cancel) { _, _ ->
                        onConfirm()
                        isEnabled = false
                    }
                    .setNegativeButton(R.string.keep_running, null)
                    .show()
            }
        }
    }

    private fun showInfo(titleRes: Int, bodyRes: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------
    // Wizard navigation: page 1 (images) → page 2 (settings) → page 3 (sweep)
    // ------------------------------------------------------------------
    private fun goToStep(step: Int, animate: Boolean) {
        val previous = viewModel.wizardStep
        val target = wizardChrome.applyStep(
            previous = previous,
            requestedStep = step,
            sweepMode = viewModel.sweepMode,
            animate = animate,
        )
        viewModel.wizardStep = target

        // Reaching the settings page counts as reviewing the parameters —
        // they are all visible here — which satisfies the Compute gate.
        if (target >= 2) viewModel.settingsReviewed = true

        if (target == 2) {
            refreshInputsCard()
            updateRoiSummary()
            // Cheap no-op when the reference/ROI have not changed since the
            // last measurement; covers inputs that arrived before this page.
            requestSubsetRecommendation()
        }
        if (target == 3) {
            sweepHelper.refreshSweepPlan()
        }

        checkReady()
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
            ::sweepHelper.isInitialized &&
            sweepHelper.currentPlan().isNotEmpty()
        if (::sweepHelper.isInitialized) {
            sweepHelper.setRunSweepEnabled(sweepEnabled)
        }

        btnDefineRoi.isEnabled = (viewModel.refBytes != null) && !isProcessing
        btnBack.isEnabled = !isProcessing
        // Enabled/disabled visuals are handled by the Material theme —
        // no more hand-painted setBackgroundColor state juggling.
    }

    private fun restoreUiFromViewModel() {
        val bytes = viewModel.refBytes
        if (bytes != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val preview = withContext(IndicVisionNativeLib.nativeDispatcher) {
                    IndicVisionNativeLib.getPreviewFromBytes(
                        bytes,
                        com.rafad.indicvisiondic.ui.common.BitmapDecode.PREVIEW_MAX_EDGE,
                    )
                }
                withContext(Dispatchers.Main) {
                    refPreviewBmp = preview
                    refreshRefSlot()
                    refreshDefSlot()
                    checkReady()
                    applySubsetRecommendation()
                }
            }
        } else {
            refreshRefSlot()
            refreshDefSlot()
            checkReady()
            applySubsetRecommendation()
        }
    }

    /** Reference slot: dropzone when empty, summary card when filled. */
    private fun refreshRefSlot() {
        val hasRef = viewModel.refBytes != null
        refDropzone.visibility = if (hasRef) View.GONE else View.VISIBLE
        refCard.visibility = if (hasRef) View.VISIBLE else View.GONE
        if (hasRef) {
            tvRefName.text = viewModel.refName
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
        tvInputsTitle.text = viewModel.refName
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
        sweepHelper.refreshLineCutPreview()
    }

    /** Clears a custom crop and treats the whole reference frame as the ROI. */
    private fun applyFullImageRoi() {
        if (viewModel.realRefWidth > 0) {
            viewModel.hasCustomRoi = false
            viewModel.roiMaskBytes = null
            viewModel.roiX = 0
            viewModel.roiY = 0
            viewModel.roiW = viewModel.realRefWidth
            viewModel.roiH = viewModel.realRefHeight
        }
        updateRoiSummary()
        checkReady()
        requestSubsetRecommendation()
    }

    /** Inline, non-blocking JPEG accuracy warning. */
    private fun updateJpegChip() {
        val jpeg = viewModel.refName.endsWith(".jpg", true) ||
            viewModel.refName.endsWith(".jpeg", true) ||
            viewModel.defFilePaths.any { it.endsWith(".jpg", true) || it.endsWith(".jpeg", true) }
        jpegWarnRow.visibility = if (jpeg) View.VISIBLE else View.GONE
    }
}
