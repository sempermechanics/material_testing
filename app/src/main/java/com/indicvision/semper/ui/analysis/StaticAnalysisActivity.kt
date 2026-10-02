// Analysis wizard Activity: it orchestrates the whole two/three-page setup flow
// (image/video import, ROI, parameters, sweep, launch). Size and branching are
// inherent; suppress rather than baseline so new findings elsewhere still fail CI.

@file:Suppress(
    "LargeClass",
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "ReturnCount",
    "TooGenericExceptionCaught",
)
@file:SuppressLint("PrivateResource", "SetTextI18n")

package com.indicvision.semper.ui.analysis
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.R
import com.indicvision.semper.SemperNativeLib
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.prefs.ParamClipboard
import com.indicvision.semper.data.prefs.WizardDraft
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.data.session.SkippedNode
import com.indicvision.semper.databinding.ActivityStaticAnalysisBinding
import com.indicvision.semper.databinding.DialogVideoSamplingBinding
import com.indicvision.semper.databinding.WizardStepSettingsBinding
import com.indicvision.semper.databinding.WizardStepSettingsContentBinding
import com.indicvision.semper.databinding.WizardStepSweepBinding
import com.indicvision.semper.diagnostics.EngineDebug
import com.indicvision.semper.field.DicParams
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.ImageSizeExtras
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.RunStop
import com.indicvision.semper.field.getRoiExtras
import com.indicvision.semper.field.toRect
import com.indicvision.semper.imaging.video.ExtractionRequest
import com.indicvision.semper.imaging.video.VideoFrameExtractor
import com.indicvision.semper.imaging.video.VideoKeyframeHelper
import com.indicvision.semper.imaging.video.VideoMeta
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.frames.AnalysisDeformedBatchHelper
import com.indicvision.semper.ui.analysis.frames.AnalysisFrameOrderMenuHelper
import com.indicvision.semper.ui.analysis.frames.AnalysisVideoExtractHelper
import com.indicvision.semper.ui.analysis.frames.DeformedFrame
import com.indicvision.semper.ui.analysis.frames.FrameOrderAdapter
import com.indicvision.semper.ui.analysis.frames.FrameOrderDirection
import com.indicvision.semper.ui.analysis.frames.FrameOrderHelper
import com.indicvision.semper.ui.analysis.frames.FrameOrderMode
import com.indicvision.semper.ui.analysis.recommend.DicGoodPractice
import com.indicvision.semper.ui.analysis.recommend.SubsetRecommender
import com.indicvision.semper.ui.analysis.roi.RoiResolveHelper
import com.indicvision.semper.ui.analysis.run.BatchRunController
import com.indicvision.semper.ui.analysis.run.ComputeOverlayHelper
import com.indicvision.semper.ui.analysis.run.EngineFailure
import com.indicvision.semper.ui.analysis.run.RunSpec
import com.indicvision.semper.ui.analysis.sweep.SweepSetupHelper
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.analysis.sweep.VsgStudyRunner
import com.indicvision.semper.ui.analysis.sweep.toSkippedNode
import com.indicvision.semper.ui.analysis.wizard.AnalysisNavHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisReadyGate
import com.indicvision.semper.ui.analysis.wizard.AnalysisSettingsSheetHelper
import com.indicvision.semper.ui.analysis.wizard.AnalysisViewModel
import com.indicvision.semper.ui.analysis.wizard.AnalysisWizardChrome
import com.indicvision.semper.ui.analysis.wizard.AnalysisWizardCoach
import com.indicvision.semper.ui.analysis.wizard.AnalysisWizardSlots
import com.indicvision.semper.ui.analysis.wizard.ReferencePreviewLoader
import com.indicvision.semper.ui.common.CoachMarkController
import com.indicvision.semper.ui.common.Dialogs
import com.indicvision.semper.ui.common.FaqRedirect
import com.indicvision.semper.ui.common.Feedback
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.MediaPickerSheet
import com.indicvision.semper.ui.common.MediaSourceChooser
import com.indicvision.semper.ui.common.Motion
import com.indicvision.semper.ui.common.SerialJob
import com.indicvision.semper.ui.common.WarnChip
import com.indicvision.semper.ui.common.commitOnDone
import com.indicvision.semper.ui.common.onButtonChecked
import com.indicvision.semper.ui.common.showUnlessEditing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The analysis setup wizard: page 1 loads reference/deformed images (or
 * extracts frames from a video), page 2 sets parameters + ROI and launches
 * the batch solve via [AnalysisViewModel]. Results open in ResultViewerActivity.
 */
@MainThread
class StaticAnalysisActivity : AppCompatActivity() {

    private companion object {
        /** Subset shown before a reference image is available to measure. */
        const val FALLBACK_SUBSET_SIZE = 41
    }

    private val viewModel: AnalysisViewModel by viewModels()
    private lateinit var coach: CoachMarkController

    // The wizard's three pages: the host layout (page 1, the bottom nav and the
    // run overlay), and the settings and sweep pages inflated from their stubs.
    private lateinit var binding: ActivityStaticAnalysisBinding
    private lateinit var settingsPage: WizardStepSettingsBinding
    private lateinit var settings: WizardStepSettingsContentBinding
    private lateinit var sweepPage: WizardStepSweepBinding

    private lateinit var frameOrderAdapter: FrameOrderAdapter

    // Inline warnings: lossy formats, low texture and speckle size on page 1;
    // speckle span and frame sizes on page 2.
    private lateinit var formatChip: WarnChip
    private lateinit var lowTextureChip: WarnChip
    private lateinit var speckleChip: WarnChip
    private lateinit var speckleSpanChip: WarnChip
    private lateinit var frameSizeChip: WarnChip

    private var refPreviewBmp: android.graphics.Bitmap? = null

    // Prominent progress overlay (compute + video extraction)
    private lateinit var overlayHelper: ComputeOverlayHelper

    private lateinit var readyGate: AnalysisReadyGate
    private lateinit var wizardChrome: AnalysisWizardChrome
    private lateinit var wizardSlots: AnalysisWizardSlots
    private lateinit var wizardCoach: AnalysisWizardCoach
    private lateinit var sweepHelper: SweepSetupHelper
    private lateinit var settingsSheetHelper: AnalysisSettingsSheetHelper

    // State
    private var isProcessing = false
    private var importJob: Job? = null

    /** The reference pick still loading; a newer pick cancels it. */
    private val refJob = SerialJob()

    private data class CancelRunConfig(
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int,
        val onConfirm: () -> Unit,
    )

    private var cancelRun: CancelRunConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStaticAnalysisBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel.attachDraft(WizardDraft(applicationContext))
        // Later wizard pages live in ViewStubs so the host layout stays under
        // lint's TooManyViews cap.
        settingsPage = WizardStepSettingsBinding.bind(binding.stubStepSettings.inflate())
        settings = settingsPage.settingsColumn.binding
        sweepPage = WizardStepSweepBinding.bind(binding.stubStepSweep.inflate())
        coach = CoachMarkController(this)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (isProcessing) {
                        if (cancelRun != null) {
                            showCancelRunDialog()
                        } else {
                            Feedback.toast(this@StaticAnalysisActivity, R.string.analysis_running_back_blocked)
                        }
                    } else if (viewModel.wizardStep > 1) {
                        goToStep(viewModel.wizardStep - 1, animate = true)
                    } else if (viewModel.refBytes != null || viewModel.defFilePaths.isNotEmpty()) {
                        showLeaveAnalysisDialog()
                    } else {
                        finish()
                    }
                }
            },
        )

        overlayHelper = ComputeOverlayHelper(binding)
        clearRunStatus()
        frameSizeChip = WarnChip(settings.frameSizeWarnRow.root, ::confirmOpenFaq)
            .apply { setFaq(getString(R.string.url_faq_frame_size)) }
        readyGate = AnalysisReadyGate(viewModel, binding, settings, frameSizeChip)
        // Text is set per-refresh by AnalysisWizardSlots.updateFormatChip: it
        // names the formats actually loaded, so it cannot be fixed here.
        formatChip = WarnChip(binding.formatWarnRow.root, ::confirmOpenFaq)
            .apply { setFaq(getString(R.string.url_faq_jpeg)) }
        setupFrameOrderStrip()
        lowTextureChip = WarnChip(binding.lowTextureWarnRow.root, ::confirmOpenFaq)
            .apply { setFaq(getString(R.string.url_faq_speckle)) }
        speckleChip = WarnChip(binding.speckleWarnRow.root, ::confirmOpenFaq)
        speckleSpanChip = WarnChip(settings.speckleSpanWarnRow.root, ::confirmOpenFaq)
        settings.rgInterpolator.onButtonChecked { clearRunStatus() }

        // --- Parameter sliders: live value labels ---
        setupParameterControls()

        // --- Wizard wiring: images → settings → optional sweep page ---
        wizardChrome = AnalysisWizardChrome(this, binding, settingsPage, sweepPage)
        wizardCoach = AnalysisWizardCoach(this, coach, binding, settings, sweepPage)

        sweepHelper = SweepSetupHelper(
            activity = this,
            viewModel = viewModel,
            callbacks = object : SweepSetupHelper.Callbacks {
                override fun goToStep(step: Int, animate: Boolean) =
                    this@StaticAnalysisActivity.goToStep(step, animate)
                override fun updateWizardChrome() =
                    wizardChrome.updateBottomNav(viewModel.wizardStep, viewModel.sweepMode)
                override fun checkReady() = this@StaticAnalysisActivity.checkReady()
                override fun commitParamFields() =
                    this@StaticAnalysisActivity.commitParamFields()
                override fun startVsgSweep() = this@StaticAnalysisActivity.startVsgSweep()
                override fun currentSubsetSize() = this@StaticAnalysisActivity.currentSubsetSize()
                override fun maxSubsetForRoi() = this@StaticAnalysisActivity.maxSubsetForRoi()
                override fun refPreviewBitmap() = refPreviewBmp
                override fun renderParamField(field: EditText, value: Int) =
                    field.showUnlessEditing(value.toString())
                override fun confirmOpenFaq(url: String) =
                    this@StaticAnalysisActivity.confirmOpenFaq(url)
            },
        )
        // After the wizard views exist: the sweep controls call checkReady().
        sweepHelper.setup()
        wizardSlots = AnalysisWizardSlots(
            viewModel = viewModel,
            binding = binding,
            settings = settings,
            formatChip = formatChip,
            frameOrderAdapter = frameOrderAdapter,
            onLineCutPreview = { sweepHelper.refreshLineCutPreview() },
        )

        binding.btnNext.setOnClickListener {
            when (viewModel.wizardStep) {
                1 -> goToStep(2, animate = true)
                2 -> if (viewModel.sweepMode) goToStep(3, animate = true)
            }
        }
        binding.btnBack.setOnClickListener {
            if (viewModel.wizardStep > 1) goToStep(viewModel.wizardStep - 1, animate = true)
        }
        goToStep(viewModel.wizardStep, animate = false)

        // Hand-off from Home's media picker: the selection type already
        // decided the branch — image becomes the reference, video enters
        // the extract-frames flow. Consumed once: a process death restores
        // the original Intent, extras and all, but the draft holds the result.
        if (savedInstanceState == null) consumePickerHandOff()
        // Edge-to-edge (targetSdk 36): push the app bar below the status bar
        // and keep the wizard nav above the nav-bar gesture area so the top
        // controls aren't in the system swipe-down zone.
        Insets.padTop(binding.toolbar)
        binding.toolbar.apply {
            title = getString(R.string.new_analysis_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        val maxFrames = DicSettings.maxFrames(this, AppRemoteConfig.maxFrames(this))
        binding.tvDefDropHint.text =
            resources.getQuantityString(R.plurals.def_formats_hint_fmt, maxFrames, maxFrames)
        Insets.padBottom(binding.bottomNav)

        // Keyboard: the settings/sweep pages hold number fields; pad their scroll
        // viewports by the part of the IME above the nav bar, and scroll the
        // focused field clear of the keyboard.
        Insets.padImeBottom(settingsPage.root)
        Insets.padImeBottom(sweepPage.root)

        // Gentle entrance: cards cascade in on first show only (not on rotation)
        if (savedInstanceState == null) {
            Motion.enterStaggered(binding.contentColumn)
        }

        restoreUiFromViewModel()
        BatchRunController(
            activity = this,
            viewModel = viewModel,
            overlayHelper = overlayHelper,
            tvResult = settings.tvStaticResult,
            setProcessing = { isProcessing = it },
            checkReady = ::checkReady,
            onPartialRun = ::onPartialRun,
            openResultViewer = { openResultViewer() },
            engineFailureMessage = { code, frameIndex, frameName ->
                engineFailureMessage(code, frameIndex, frameName)
            },
            showEngineFailureDialog = ::showEngineFailureDialog,
            clearEngineFailFaq = { setEngineFailFaq(null) },
            onSweepProgress = ::showSweepProgress,
            onSweepFinished = ::onSweepFinished,
        ).observe()

        // Files (SAF) still reaches DNG/RAW and Drive, which MediaStore may not index.
        var mediaPicker: MediaPickerSheet? = null
        val requestMediaPermission =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                mediaPicker?.onPermissionResult()
            }
        val pickRefFiles =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri?.let { handleReferenceImage(it) }
            }
        val pickDefFiles =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                onDeformedPicked(uris)
            }

        val roiStudioLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { applyRoiResult(it) }
            } else {
                // Cancelled editor → fall back to full-image ROI.
                applyFullImageRoi()
            }
        }

        val launchRefPicker = {
            mediaPicker = MediaSourceChooser.show(
                activity = this,
                mode = MediaSourceChooser.Mode.REFERENCE,
                requestPermission = {
                    requestMediaPermission.launch(
                        MediaSourceChooser.requiredPermissions(includeVideo = false),
                    )
                },
                onBrowseSaf = { pickRefFiles.launch(arrayOf("image/*")) },
                onPicked = { uris -> uris.firstOrNull()?.let { handleReferenceImage(it) } },
            )
        }
        binding.refDropzone.setOnClickListener { launchRefPicker() }
        binding.btnRefChange.setOnClickListener { launchRefPicker() }

        val launchDefPicker = {
            mediaPicker = MediaSourceChooser.show(
                activity = this,
                mode = MediaSourceChooser.Mode.DEFORMED,
                requestPermission = {
                    requestMediaPermission.launch(
                        MediaSourceChooser.requiredPermissions(includeVideo = false),
                    )
                },
                onBrowseSaf = { pickDefFiles.launch(arrayOf("image/*")) },
                onPicked = { uris -> onDeformedPicked(uris) },
            )
        }
        binding.defDropzone.setOnClickListener { launchDefPicker() }
        binding.btnDefChange.setOnClickListener { launchDefPicker() }

        settings.btnDefineRoi.setOnClickListener {
            val bytes = viewModel.refBytes
            if (bytes == null) {
                Feedback.toast(this, R.string.load_image_first)
            } else {
                openRoiStudio(bytes, roiStudioLauncher)
            }
        }

        binding.btnCalculateFullField.setOnClickListener {
            // A field still holding focus has not committed its typed value yet.
            commitParamFields()
            if (!viewModel.sweepMode) startBatchAnalysis()
        }
    }

    /**
     * Hand the reference image to [RoiDrawActivity] through a cache file.
     *
     * The copy runs on [Dispatchers.IO]: [bytes] is the decoded reference, tens
     * of megabytes for a RAW frame, and writing that from the click handler
     * froze the wizard for the length of the write.
     */
    private fun openRoiStudio(
        bytes: ByteArray,
        launcher: ActivityResultLauncher<Intent>,
    ) {
        val tempFile = File(cacheDir, CacheJanitor.TEMP_ROI_REF)
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) {
                try {
                    tempFile.writeBytes(bytes)
                    true
                } catch (e: IOException) {
                    Timber.e(e, "Failed to write temp ROI reference file")
                    false
                }
            }
            if (!written) {
                Feedback.toast(this@StaticAnalysisActivity, R.string.failed_save_temp_file)
                return@launch
            }
            val intent = Intent(this@StaticAnalysisActivity, RoiDrawActivity::class.java)
            intent.putExtra(DicKeys.IMAGE_FILE_PATH, tempFile.absolutePath)
            ImageSizeExtras.ROI_EDITOR.put(intent, viewModel.refSize)
            launcher.launch(intent)
        }
    }

    /**
     * Adopt the ROI the studio returned.
     *
     * The freeform mask is read on [Dispatchers.IO] — one byte per reference
     * pixel, so tens of megabytes on a modern sensor, and reading it inline
     * stalled the very frame that had to draw the updated summary. Everything
     * that depends on the mask stays after the read, in order.
     */
    private fun applyRoiResult(data: Intent) {
        // After a process death whose draft was lost there is no reference
        // left to measure this ROI against.
        if (viewModel.realRefWidth == 0) {
            Timber.w("ROI result with no reference; ignored")
            return
        }
        val drawn = data.getRoiExtras(default = Roi.full(viewModel.refSize))
        viewModel.roi = drawn
        // A selection covering the whole image counts as no custom ROI.
        viewModel.hasCustomRoi = !drawn.coversFrameOf(viewModel.refSize)

        val maskPath = data.getStringExtra(DicKeys.MASK_FILE_PATH)
        lifecycleScope.launch {
            val mask = maskPath?.let { path ->
                withContext(Dispatchers.IO) {
                    File(path).takeIf(File::exists)?.let { file ->
                        try {
                            file.readBytes()
                        } catch (e: IOException) {
                            Timber.e(e, "Failed to read ROI mask")
                            null
                        }
                    }
                }
            }
            if (mask != null) viewModel.roiMaskBytes = mask
            wizardSlots.updateRoiSummary()
            sweepHelper.refreshLineCutPreview()
            checkReady()
            requestSubsetRecommendation()
        }
    }

    override fun onDestroy() {
        // The engine runs on a process-global daemon thread that outlives this
        // Activity. Without this teardown a solve in flight when the screen is
        // destroyed keeps burning CPU and holding frame bytes, the progress
        // ticker reposts against dead views, and KEEP_SCREEN_ON leaks.
        viewModel.cancelRequested = true // also flips the native cancel flag via AnalysisCancelGate
        importJob?.cancel()
        importJob = null
        refJob.cancel()
        // VsgStudyRunner observes the same gate — no separate flag.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (::overlayHelper.isInitialized) overlayHelper.release()
        if (::frameOrderAdapter.isInitialized) frameOrderAdapter.release()
        // Reclaim the retained reference thumbnail deterministically on close. The
        // thumbnail ImageViews won't be drawn again after onDestroy, so this is
        // safe. (Replace sites intentionally don't recycle: the prior bitmap may
        // still be shown in a slot until its refresh runs, and recycling a live
        // bitmap crashes on the next draw — the superseded ones are GC-eligible.)
        refPreviewBmp?.recycle()
        refPreviewBmp = null
        // Finishing is the one way out that no restore follows.
        if (isFinishing) viewModel.discardDraft()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::settingsSheetHelper.isInitialized) settingsSheetHelper.refreshPasteVisibility()
    }

    /**
     * Loads a picked reference. A pick cancels the one still loading, so the
     * last image picked is the one that lands, not the last one to finish
     * decoding.
     */
    private fun handleReferenceImage(uri: Uri) {
        refJob.launch(lifecycleScope) {
            try {
                val name = withContext(Dispatchers.IO) { getFileName(uri) }
                val isRaw = name.endsWith(".dng", true) || name.endsWith(".raw", true)
                val loaded = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream -> loadReferenceFromStream(stream, isRaw) }
                }
                if (loaded == null) {
                    FaqRedirect.snackbar(
                        this@StaticAnalysisActivity,
                        if (isRaw) R.string.failed_decode_raw else R.string.failed_load_reference,
                        R.string.url_faq_import_reference,
                    )
                    return@launch
                }

                viewModel.applyNewReference(loaded.bytes, name, loaded.size)
                refPreviewBmp = loaded.preview
                wizardSlots.refreshRefSlot(refPreviewBmp)
                wizardSlots.updateRoiSummary()
                sweepHelper.refreshLineCutPreview()
                // Frames may have been loaded before this reference.
                validateFrameSizes()
                clearRunStatus()
                checkReady()
                requestSubsetRecommendation()
            } catch (e: CancellationException) {
                // A newer pick, or the screen closing: nothing failed.
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Failed to load reference image")
                FaqRedirect.snackbar(
                    this@StaticAnalysisActivity,
                    R.string.failed_load_reference,
                    R.string.url_faq_import_reference,
                )
            }
        }
    }

    private class LoadedReference(val bytes: ByteArray, val size: ImageSize, val preview: Bitmap?)

    /** Decode / dimension / preview work for a reference pick. Null when it cannot be decoded. */
    @WorkerThread
    private suspend fun loadReferenceFromStream(
        stream: java.io.InputStream,
        isRaw: Boolean,
    ): LoadedReference? {
        if (isRaw) {
            val decoded = com.indicvision.semper.imaging.BitmapDecode.rgbaAndPreviewFromStream(stream)
                ?: return null
            return LoadedReference(decoded.rgba, ImageSize(decoded.width, decoded.height), decoded.preview)
        }

        val bytes = stream.readBytes()
        // Sizes from the native decoder, which applies EXIF as the engine does.
        val loaded = ReferencePreviewLoader.load(
            ReferencePreviewLoader.Request(bytes, 0, 0, com.indicvision.semper.imaging.BitmapDecode.PREVIEW_MAX_EDGE),
        )
        if (loaded.width <= 0 || loaded.height <= 0) return null
        return LoadedReference(bytes, ImageSize(loaded.width, loaded.height), loaded.bitmap)
    }

    /** Shared result path for the deformed-frame pickers (Photos and Files). */
    private fun onDeformedPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) {
            handleDeformedBatch(uris)
        } else {
            Feedback.toast(this, R.string.no_images_selected)
        }
    }

    private fun handleDeformedBatch(rawUris: List<Uri>) {
        if (isProcessing) return
        isProcessing = true
        checkReady()
        val job = AnalysisDeformedBatchHelper(this, viewModel, overlayHelper, settings.tvStaticResult).handle(
            rawUris = rawUris,
            displayName = ::getFileName,
            onApplied = {
                wizardSlots.refreshDefSlot()
                validateFrameSizes()
                clearRunStatus()
                checkReady()
            },
            onFinished = ::finishImportOperation,
        )
        importJob = job
        wireCancelButton(
            titleRes = R.string.cancel_import_title,
            bodyRes = R.string.cancel_import_body,
        ) { job.cancel() }
    }

    private fun setupFrameOrderStrip() {
        binding.rvFrameOrder.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        frameOrderAdapter = FrameOrderAdapter { orderedPaths ->
            applyManualFrameOrder(orderedPaths)
        }
        binding.rvFrameOrder.adapter = frameOrderAdapter
        FrameOrderAdapter.attachDrag(binding.rvFrameOrder, frameOrderAdapter)

        binding.btnFrameOrderSort.setOnClickListener { anchor ->
            showFrameOrderMenu(anchor)
        }
    }

    private fun showFrameOrderMenu(anchor: View) {
        AnalysisFrameOrderMenuHelper.show(
            activity = this,
            anchor = anchor,
            mode = viewModel.defOrderMode,
            direction = viewModel.defOrderDirection,
            onSelect = ::applyFrameOrderMode,
        )
    }

    private fun applyFrameOrderMode(
        mode: FrameOrderMode,
        direction: FrameOrderDirection = FrameOrderDirection.ASCENDING,
    ) {
        if (viewModel.defFromVideo || viewModel.defFilePaths.size <= 1) return
        viewModel.defOrderMode = mode
        viewModel.defOrderDirection = direction
        frameOrderAdapter.dragEnabled = mode == FrameOrderMode.MANUAL
        if (mode == FrameOrderMode.MANUAL) {
            Feedback.toast(this, R.string.frame_order_manual_hint)
            return
        }
        // Import no longer probes URI dates (kept the overlay at 0% on PLC).
        // Resolve from the cached files the first time the user sorts by date.
        val snapshot = viewModel.deformedFrames
        lifecycleScope.launch {
            val dated = withContext(Dispatchers.IO) {
                if (mode == FrameOrderMode.DATE && snapshot.all { it.date == DeformedFrame.UNKNOWN_DATE }) {
                    snapshot.map { it.copy(date = FrameOrderHelper.resolveDateMs(File(it.path))) }
                } else {
                    snapshot
                }
            }
            val ordered = withContext(Dispatchers.Default) { FrameOrderHelper.reorder(dated, mode, direction) }
            viewModel.deformedFrames = withContext(Dispatchers.IO) { FrameOrderHelper.reprefixTempFiles(ordered) }
            wizardSlots.refreshDefSlot()
            validateFrameSizes()
        }
    }

    private fun applyManualFrameOrder(orderedPaths: List<String>) {
        if (orderedPaths == viewModel.defFilePaths) return
        val indexOf = viewModel.defFilePaths.withIndex().associate { it.value to it.index }
        val order = orderedPaths.mapNotNull { indexOf[it] }
        if (order.size != orderedPaths.size) return
        // Keep file names as-is during drag; analysis uses list order, not path sort.
        viewModel.deformedFrames =
            FrameOrderHelper.reorder(viewModel.deformedFrames, FrameOrderMode.MANUAL, manualOrder = order)
        viewModel.defOrderMode = FrameOrderMode.MANUAL
        validateFrameSizes()
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

            // An AVI we could demux but not decode can say which codec it is,
            // which beats "could not read this video" by a mile.
            val unsupported = meta.unsupportedCodec
            if (unsupported != null) {
                withContext(Dispatchers.Main) {
                    FaqRedirect.snackbar(
                        this@StaticAnalysisActivity,
                        getString(R.string.video_codec_unsupported, unsupported.trim()),
                        R.string.url_faq_video_read,
                    )
                }
                return@launch
            }

            if (meta.durationMs <= 0L) {
                withContext(Dispatchers.Main) {
                    FaqRedirect.snackbar(
                        this@StaticAnalysisActivity,
                        R.string.video_read_failed,
                        R.string.url_faq_video_read,
                    )
                }
                return@launch
            }
            withContext(Dispatchers.Main) { showVideoSamplingDialog(uri, meta) }
        }
    }

    /** Sampling by extraction frame rate + time segment, with a metadata summary. */
    private fun showVideoSamplingDialog(uri: Uri, meta: VideoMeta) {
        val sheetBinding = DialogVideoSamplingBinding.inflate(layoutInflater)
        val tvInfo = sheetBinding.tvVideoInfo
        val toggleMode = sheetBinding.toggleExtractMode
        val layoutFps = sheetBinding.layoutFps
        val sliderFps = sheetBinding.sliderFps
        val tvFps = sheetBinding.tvFpsValue
        val range = sheetBinding.rangeSegment
        val tvSegment = sheetBinding.tvSegmentValue
        val tvEstimate = sheetBinding.tvEstimate

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

        val maxFrames = DicSettings.maxFrames(
            this@StaticAnalysisActivity,
            AppRemoteConfig.maxFrames(this@StaticAnalysisActivity),
        )
        // The slider reaches the clip's end, where no frame starts; sampling
        // stops at the last frame's start so the estimate is what extraction
        // delivers (see VideoKeyframeHelper.lastFrameStartMs).
        val lastFrameMs = VideoKeyframeHelper.lastFrameStartMs(meta.durationMs, meta.fps, meta.fpsKnown)
        fun segmentMs(): Pair<Long, Long> =
            (range.values.first() * 1000).toLong().coerceAtMost(lastFrameMs) to
                (range.values.last() * 1000).toLong().coerceAtMost(lastFrameMs)
        fun estimate(): Int {
            val (startMs, endMs) = segmentMs()
            val fps = sliderFps.value.toDouble()
            return VideoKeyframeHelper.uniformTimestampsUs(startMs, endMs, fps, maxFrames).size
        }
        val btnExtract = sheetBinding.btnExtractFrames
        fun refreshEstimate() {
            val isKeyframeMode = toggleMode.checkedButtonId == R.id.btnModeKeyframes
            if (isKeyframeMode) {
                tvEstimate.text = getString(R.string.video_keyframes_estimate_note)
                btnExtract.setText(R.string.extract_frames_title)
            } else {
                val n = estimate()
                val capped = if (n >= maxFrames) getString(R.string.video_capped_suffix) else ""
                tvEstimate.text = "≈ $n frame(s): 1 reference + ${(n - 1).coerceAtLeast(0)} deformed$capped"
                btnExtract.text = resources.getQuantityString(R.plurals.extract_n_frames_fmt, n, n)
            }
        }

        toggleMode.onButtonChecked { checkedId ->
            val isKeyframeMode = checkedId == R.id.btnModeKeyframes
            layoutFps.visibility = if (isKeyframeMode) View.GONE else View.VISIBLE
            refreshEstimate()
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
        sheet.setContentView(sheetBinding.root)
        btnExtract.setOnClickListener {
            sheet.dismiss()
            val (startMs, endMs) = segmentMs()
            extractVideoFrames(
                ExtractionRequest(
                    uri = uri,
                    fpsExtract = sliderFps.value.toDouble().coerceAtLeast(0.1),
                    startMs = startMs,
                    endMs = endMs,
                    maxFrames = maxFrames,
                    preferKeyframes = toggleMode.checkedButtonId == R.id.btnModeKeyframes,
                    rotationDegrees = meta.rotationDegrees,
                ),
            )
        }
        sheet.show()
    }

    /** Extracts the frames [request] samples, with the progress overlay. */
    private fun extractVideoFrames(request: ExtractionRequest) {
        if (isProcessing) return
        isProcessing = true
        // The video's first frame becomes the reference; an image pick still
        // decoding must not land on top of it.
        refJob.cancel()
        checkReady()
        val job = AnalysisVideoExtractHelper(this, viewModel, overlayHelper, settings.tvStaticResult).extract(
            request = request,
            onApplied = { applied ->
                applied.refPreview?.let { refPreviewBmp = it }
                wizardSlots.refreshRefSlot(refPreviewBmp)
                wizardSlots.refreshDefSlot()
                // A different-size reference resets the ROI (applyNewReference).
                wizardSlots.updateRoiSummary()
                sweepHelper.refreshLineCutPreview()
                validateFrameSizes()
                clearRunStatus()
                checkReady()
                requestSubsetRecommendation()
            },
            onFinished = ::finishImportOperation,
        )
        importJob = job
        wireCancelButton(
            titleRes = R.string.cancel_import_title,
            bodyRes = R.string.cancel_import_body,
        ) { job.cancel() }
    }

    private fun currentSubsetSize(): Int = settings.etSubsetSize.value.toInt()
    private fun currentStepSize(): Int = settings.etStepSize.value.toInt()

    /** The VSG in px handed to the engine: the slider's window is in data points. */
    private fun currentStrainWindow(): Int = VsgStudy.vsgFor(settings.etStrainWindow.value.toInt(), currentStepSize())
    private fun currentUseKeysInterpolator(): Boolean = settings.rgInterpolator.checkedButtonId == R.id.rbKeys

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
        val ref = viewModel.refSize
        val badNames = viewModel.deformedFrames
            .filter { it.size != null && it.size != ref }
            .map { frame -> frame.name.ifEmpty { File(frame.path).name } }
        viewModel.frameSizeError = if (!ref.isKnown || badNames.isEmpty()) {
            null
        } else {
            resources.getQuantityString(
                R.plurals.frames_size_mismatch_fmt,
                badNames.size,
                ref.width,
                ref.height,
                formatMismatchNames(badNames),
            )
        }
    }

    /** First few mismatched filenames, then "and N more" when the list is long. */
    private fun formatMismatchNames(names: List<String>): String {
        val limit = 3
        if (names.size <= limit) return names.joinToString(", ")
        val head = names.take(limit).joinToString(", ")
        return resources.getQuantityString(
            R.plurals.frames_size_mismatch_and_more_fmt,
            names.size - limit,
            head,
            names.size - limit,
        )
    }

    /** Drop a previous run's ❌ / success line when the user changes inputs. */
    private fun clearRunStatus() {
        if (isProcessing) return
        settings.tvStaticResult.text = ""
        setEngineFailFaq(null)
    }

    /**
     * ⓘ beside the run-status line after an engine-failure dialog — same FAQ
     * hop as that dialog's **Why?**, still reachable once the alert is gone.
     */
    private fun setEngineFailFaq(@StringRes faqUrlRes: Int?) {
        val faq = settings.btnEngineFailFaq
        if (faqUrlRes == null) {
            faq.isVisible = false
            faq.setOnClickListener(null)
            return
        }
        faq.isVisible = true
        faq.setOnClickListener { FaqRedirect.confirm(this, faqUrlRes) }
    }

    /** Region the recommendation samples: the ROI when set, else the frame. */
    private fun currentSamplingRoi(): Rect? =
        viewModel.roi.orFullFrame(viewModel.hasCustomRoi, viewModel.refSize)?.toRect()

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

        // Read off the slider here: the measurement runs on the native thread,
        // which must not touch views.
        val tuning = SubsetRecommender.Tuning(
            sizes = settings.etSubsetSize.valueFrom.toInt()..settings.etSubsetSize.valueTo.toInt(),
        )

        lifecycleScope.launch(SemperNativeLib.nativeDispatcher) {
            val result = runCatching {
                SubsetRecommender.recommend(
                    refBytes = bytes,
                    imgW = viewModel.realRefWidth,
                    imgH = viewModel.realRefHeight,
                    roi = roi,
                    tuning = tuning,
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
        return snapToSlider(settings.etSubsetSize, rec.subsetSize)
    }

    /** Seeds the slider with the recommendation, until the user overrides it. */
    private fun applySubsetRecommendation() {
        val rec = viewModel.subsetRecommendation ?: run {
            lowTextureChip.hide()
            speckleChip.hide()
            speckleSpanChip.hide()
            settings.tvSpeckleReadout.isVisible = false
            return
        }
        // The one thing the measurement knows that the slider cannot show: even
        // the largest allowed subset misses the accuracy target on this pattern.
        lowTextureChip.showOrHide(getString(R.string.subset_low_texture_fmt).takeIf { rec.lowTexture })
        if (!viewModel.subsetUserModified) {
            val snapped = snapToSlider(settings.etSubsetSize, rec.subsetSize)
            if (settings.etSubsetSize.value.toInt() != snapped) {
                commitParamFields()
                settings.etSubsetSize.value = snapped.toFloat()
            }
        }
        // A new recommendation re-seeds the sweep's suggested inputs (unless the
        // user has already set their own).
        sweepHelper.onRecommendationChanged()
        // After the slider has been seeded, so the span check judges the size
        // that will actually be run.
        showSpeckleFeedback()
    }

    /**
     * Reports the measured speckle size against the iDICs *Good Practices
     * Guide* band, so the user learns something about their specimen rather
     * than only about the slider.
     *
     * Three surfaces, each placed where its fix is. The readout under the
     * subset slider is shown whenever a measurement exists, good news included
     * — it is the number the recommendation rests on, and a user who can see
     * it can judge their own pattern before spending a run on it. The size
     * chip sits on step 1 with the images, because a pattern outside the band
     * is fixed by a different photograph and by nothing on step 2. The span
     * chip sits on step 2 under the slider, because that slider is its fix and
     * a warning the user cannot watch clear is a warning they will not trust.
     *
     * At most one chip shows: a pattern too fine or too coarse to resolve
     * makes the subset-span question moot, so the size verdict is reported
     * ahead of it and suppresses the span chip entirely.
     */
    private fun showSpeckleFeedback() {
        val diameter = viewModel.subsetRecommendation?.speckleDiameterPx
        if (diameter == null) {
            // No measurable pattern in any sample patch. The low-texture chip
            // already covers the case where that is the user's problem; saying
            // nothing here is better than reporting a number we do not have.
            speckleChip.hide()
            speckleSpanChip.hide()
            settings.tvSpeckleReadout.isVisible = false
            return
        }

        settings.tvSpeckleReadout.text = getString(
            R.string.speckle_readout_fmt,
            diameter,
            DicGoodPractice.MIN_SPECKLE_PX.toInt(),
            DicGoodPractice.MAX_SPECKLE_PX.toInt(),
        )
        settings.tvSpeckleReadout.isVisible = true

        val sizeMessage = when (DicGoodPractice.verdictFor(diameter)) {
            DicGoodPractice.Verdict.UNDER_RESOLVED -> getString(
                R.string.speckle_under_resolved_fmt,
                diameter,
                DicGoodPractice.MIN_SPECKLE_PX.toInt(),
            )
            DicGoodPractice.Verdict.OVER_RESOLVED -> getString(
                R.string.speckle_over_resolved_fmt,
                diameter,
                DicGoodPractice.MAX_SPECKLE_PX.toInt(),
            )
            DicGoodPractice.Verdict.USABLE -> null
        }
        // Read off the slider, not off the recommendation: the user may have
        // moved it since, and a chip naming a size they are no longer using is
        // worse than no chip. Suppressed outright while the size chip is up —
        // spanning three speckles is not the problem on a pattern that cannot
        // be resolved at all.
        val spanMessage = if (sizeMessage != null) {
            null
        } else {
            val inUse = settings.etSubsetSize.value.toInt()
            val wanted = viewModel.subsetRecommendation?.subsetSpanningSpeckles
            if (wanted != null && wanted > inUse) {
                getString(R.string.speckle_subset_span_fmt, inUse, wanted)
            } else {
                null
            }
        }

        val faqUrl = getString(R.string.url_faq_speckle)
        speckleChip.showOrHide(sizeMessage, faqUrl)
        speckleSpanChip.showOrHide(spanMessage, faqUrl)
    }

    /**
     * The rectangle the engine solves over, as `[x, y, w, h]`: the drawn ROI,
     * or the whole frame inset by half a subset (plus slack) so no subset hangs
     * off the edge. Null — with the user told why — when it cannot hold one
     * subset.
     */
    private fun resolveRoi(subset: Int): Roi? {
        val roi = Roi.forSolve(subset, viewModel.hasCustomRoi, viewModel.roi, viewModel.refSize)
        if (roi == null) {
            FaqRedirect.snackbar(
                this,
                R.string.roi_too_small,
                R.string.url_faq_roi_too_small,
            )
        }
        return roi
    }

    /** Largest odd subset the loaded image and ROI can hold. */
    private fun maxSubsetForRoi(): Int =
        RoiResolveHelper.maxSubsetForRoi(viewModel.hasCustomRoi, viewModel.roi, viewModel.refSize)

    private fun startBatchAnalysis() {
        if (!viewModel.isReadyToCompute()) return

        val params = DicParams(currentSubsetSize(), currentStepSize(), currentStrainWindow())
        val roi = resolveRoi(params.subset) ?: return
        // Frozen here: everything after Compute reads the run's spec, not the sliders.
        val spec = RunSpec.of(
            params = params,
            roi = roi,
            mask = viewModel.roiMaskBytes,
            use6x6 = currentUseKeysInterpolator(),
            debugDir = EngineDebug.dirFor(cacheDir),
        )

        // Hard stop: do not start a new analysis when the session quota is full.
        // Re-runs that update an existing Home row are still allowed.
        lifecycleScope.launch {
            if (!ensureCanStart()) return@launch

            isProcessing = true
            checkReady()
            overlayHelper.processingStartTime = System.currentTimeMillis()
            overlayHelper.show()
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wireCancelButton { viewModel.cancelRequested = true }

            // Survives Activity destroy; progress/outcome observed via StateFlow / SharedFlow.
            viewModel.launchBatchAnalysis(applicationContext, spec, cacheDir, overlayHelper.processingStartTime)
        }
    }

    /**
     * A run that stopped itself partway: the images decorrelated, but the frames
     * solved before that are valid and the session already holds them.
     *
     * Told plainly and then opened. The alternative — a failure dialog — left a
     * session appearing on Home that the user had just been told was a failure,
     * with no route to it from here.
     */
    private fun onPartialRun(outcome: AnalysisViewModel.BatchAnalysisOutcome) {
        val kept = outcome.totalFrames
        val planned = viewModel.defFilePaths.size
        settings.tvStaticResult.text = getString(R.string.run_stopped_early_fmt, outcome.stoppedAtFrame, planned)
        viewModel.lastDefPath = viewModel.defFilePaths.firstOrNull() ?: ""
        viewModel.lastBatchDirPath = outcome.batchDirPath
        checkReady()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.run_stopped_early_title)
            .setMessage(
                resources.getQuantityString(R.plurals.run_stopped_early_body, kept, kept, planned) +
                    System.lineSeparator() + System.lineSeparator() +
                    engineFailureMessage(
                        outcome.engineErrorCode,
                        frameIndex = outcome.failedFrameIndex,
                        frameName = outcome.failedFrameName,
                    ),
            )
            // The dialog explains, it does not ask: the frames are saved either
            // way, so dismissing it back onto the settings page would strand the
            // user one screen away from the data the run just produced.
            .setCancelable(false)
            .setPositiveButton(R.string.run_stopped_early_view) { _, _ -> openResultViewer() }
            .show()
    }

    /**
     * @param sweep true when the frames are parameter combinations rather than
     *   deformed images. The viewer needs each frame's own settings then — the
     *   step size alone changes how a frame renders — and names the frames
     *   after the combination instead of after an image file.
     */
    private fun openResultViewer(sweep: Boolean = false) {
        val plan = viewModel.sweepPlan
        val frameNames = if (sweep) {
            ArrayList(plan.map { sweepHelper.combinationLabel(it) })
        } else {
            ArrayList(viewModel.defFilePaths.map { it.substringAfterLast('/') })
        }
        AnalysisNavHelper.openResults(
            host = this,
            viewModel = viewModel,
            sweep = sweep,
            frameNames = frameNames,
        )
    }

    /** The picked file's display name; a content-provider query, so off the main thread. */
    @SuppressLint("Range")
    @WorkerThread
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
        settings.tvSubsetValue.clearFocus()
        settings.tvStepValue.clearFocus()
        settings.tvOverlapValue.clearFocus()
        settings.tvStrainValue.clearFocus()
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
        }
        field.commitOnDone(onDone = commit)
        field.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
    }

    // ------------------------------------------------------------------
    // Parameter sliders: the value fields are editable, so dragging writes
    // into them and typing writes back into the slider. Values are still
    // read via slider.value everywhere.
    // ------------------------------------------------------------------
    private fun setupParameterControls() {
        settingsSheetHelper = AnalysisSettingsSheetHelper(
            activity = this,
            settings = settings,
            bindParamField = { field, slider, onUser -> bindParamField(field, slider, onUser) },
            onSubsetUserModified = {
                viewModel.subsetUserModified = true
                showSpeckleFeedback()
            },
            onSubsetRecommendationRefresh = {
                if (::sweepHelper.isInitialized) sweepHelper.onRecommendationChanged()
            },
            onAdvancedReset = {
                commitParamFields()
                viewModel.subsetUserModified = false
                settings.etSubsetSize.value = defaultSubsetSize().toFloat()
                settings.etStepSize.value = 5f
                settings.etStrainWindow.value = VsgStudy.DEFAULT_WINDOW_POINTS.toFloat()
                settings.rgInterpolator.check(R.id.rbBicubic)
                settingsSheetHelper.syncFromStep()
                showSpeckleFeedback()
                if (::sweepHelper.isInitialized) {
                    viewModel.stepDenominator = VsgStudy.DEFAULT_STEP_DENOM
                    viewModel.subsetOverlap = VsgStudy.overlapForDenominator(VsgStudy.DEFAULT_STEP_DENOM)
                    sweepHelper.resetUserModified()
                    sweepHelper.seedSweepSuggestions()
                }
                clearRunStatus()
            },
            onPasteParams = { pasteCopiedParams() },
            onParamsChanged = { clearRunStatus() },
        ).also { it.bind() }
    }

    /** Applies ParamClipboard subset/step/window into the analysis sliders. */
    private fun pasteCopiedParams() {
        val params = ParamClipboard.peek(this) ?: return
        commitParamFields()
        viewModel.subsetUserModified = true
        settings.etSubsetSize.value = snapToSlider(settings.etSubsetSize, params.subset).toFloat()
        settings.etStepSize.value = snapToSlider(settings.etStepSize, params.step).toFloat()
        // The clipboard holds a VSG in px; the slider takes points at the pasted step.
        settings.etStrainWindow.value = VsgStudy.nearestWindowPoints(params.vsg, currentStepSize()).toFloat()
        settingsSheetHelper.syncFromStep()
        showSpeckleFeedback()
        if (::sweepHelper.isInitialized) sweepHelper.onRecommendationChanged()
        clearRunStatus()
        // Bring the advanced-params card into view so the pasted values are visible.
        val card = settings.advancedParamsCard
        card.post { card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), false) }
    }

    // ------------------------------------------------------------------
    @Suppress("ReturnCount") // each precondition bails out on the spot
    private fun startVsgSweep() {
        if (!viewModel.isReadyToCompute()) return
        val plan = sweepHelper.currentPlan()
        if (plan.isEmpty()) return
        // Every combination shares the ROI, so the largest subset has to fit it.
        val roi = resolveRoi(plan.maxOf { it.subset }) ?: return
        val spec = RunSpec.sweep(
            RunSpec.Sweep(
                plan = plan,
                labels = plan.map { sweepHelper.combinationLabel(it) },
                lineCutHorizontal = viewModel.lineCutHorizontal,
                frameIndex = sweepHelper.resolvedSweepFrame(),
            ),
            roi = roi,
            mask = viewModel.roiMaskBytes,
            use6x6 = currentUseKeysInterpolator(),
            debugDir = EngineDebug.dirFor(cacheDir),
        )

        lifecycleScope.launch {
            if (!ensureCanStart()) return@launch

            isProcessing = true
            checkReady()
            overlayHelper.processingStartTime = System.currentTimeMillis()
            overlayHelper.show(getString(R.string.mode_sweep), sweepHelper.planSummary(plan))
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wireCancelButton { viewModel.cancelRequested = true }

            // Handed to the view model rather than run here: a sweep is one
            // solve per combination, long enough that a rotation mid-run used to
            // cancel it and leave the half-written session behind.
            // BatchRunController tears the chrome down when it ends.
            viewModel.launchVsgSweep(applicationContext, spec)
        }
    }

    private fun showSweepProgress(progress: VsgStudyRunner.Progress) {
        overlayHelper.update(
            percent = progress.percent.toFloat(),
            status = getString(
                R.string.sweep_running_fmt,
                progress.runIndex + 1,
                progress.totalRuns,
                progress.point.subset,
                progress.point.step,
                progress.point.window,
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
            Feedback.toast(this, R.string.sweep_failed, long = true)
            return
        }
        if (outcome.stop == RunStop.SessionLimit) {
            AnalysisNavHelper.openSessionLimit(this)
            return
        }
        if (outcome.totalFrames == 0) {
            if (outcome.stop == RunStop.Cancelled) return
            // Route to lattice with all-failed nodes so the user can tap each for details.
            // Each node keeps its own reason; they used to all show the last one's.
            viewModel.sweepPlan = emptyList()
            viewModel.sweepSkippedNodes = SkippedNode.forFailedSweep(viewModel.sweepSkippedNodes) {
                val plan = viewModel.runResult.value.spec?.sweep?.plan ?: sweepHelper.currentPlan()
                plan.map { it.toSkippedNode(outcome.engineErrorCode) }
            }
            viewModel.lastBatchDirPath = outcome.batchDirPath
            openResultViewer(sweep = true)
            return
        }
        val skipped = viewModel.sweepSkippedNodes.size
        if (skipped > 0) {
            // Partial sweeps are still worth browsing; say what was dropped.
            Feedback.toast(
                this,
                resources.getQuantityString(
                    R.plurals.sweep_partial_fmt,
                    skipped,
                    skipped,
                    skipped + outcome.totalFrames,
                ),
                long = true,
            )
        }

        val sweepFrame = viewModel.runResult.value.spec?.sweep?.frameIndex ?: sweepHelper.resolvedSweepFrame()
        viewModel.lastDefPath = viewModel.defFilePaths.getOrNull(sweepFrame) ?: ""
        viewModel.lastBatchDirPath = outcome.batchDirPath
        checkReady()
        // Stage the swept parameter space on the interactive lattice; it opens
        // the result viewer from there.
        openResultViewer(sweep = true)
    }

    /**
     * Why a run produced nothing. The engine's codes are the same for single
     * analysis and sweep; messages reuse the sweep-path string resources.
     */
    private fun engineFailureMessage(
        engineErrorCode: Int,
        frameIndex: Int = -1,
        frameName: String? = null,
    ): String {
        val frameInfo = when {
            frameIndex < 0 -> ""
            frameName != null -> getString(R.string.failure_frame_fmt, frameIndex + 1, frameName)
            else -> getString(R.string.failure_frame_no_name_fmt, frameIndex + 1)
        }
        return frameInfo + getString(EngineFailure.reasonRes(engineErrorCode), engineErrorCode)
    }

    private fun showEngineFailureDialog(message: String, @StringRes titleRes: Int, @StringRes faqUrlRes: Int) {
        setEngineFailFaq(faqUrlRes)
        FaqRedirect.errorDialog(this, getString(titleRes), message, faqUrlRes)
    }

    private suspend fun ensureCanStart(): Boolean =
        AnalysisNavHelper.ensureCanStart(this, viewModel)

    private fun wireCancelButton(
        titleRes: Int = R.string.cancel_run_title,
        bodyRes: Int = R.string.cancel_run_body,
        onConfirm: () -> Unit,
    ) {
        cancelRun = CancelRunConfig(titleRes, bodyRes, onConfirm)
        binding.btnRunCancel.apply {
            isEnabled = true
            setOnClickListener { showCancelRunDialog() }
        }
    }

    private fun showCancelRunDialog() {
        val config = cancelRun ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(config.titleRes)
            .setMessage(config.bodyRes)
            .setPositiveButton(R.string.action_cancel) { _, _ ->
                config.onConfirm()
                binding.btnRunCancel.isEnabled = false
            }
            .setNegativeButton(R.string.keep_running, null)
            .show()
    }

    private fun showLeaveAnalysisDialog() {
        Dialogs.confirm(this, R.string.exit_analysis_title, R.string.exit_analysis_message, R.string.exit) { finish() }
    }

    private fun finishImportOperation() {
        importJob = null
        isProcessing = false
        clearCancelButton()
        checkReady()
    }

    private fun clearCancelButton() {
        cancelRun = null
        binding.btnRunCancel.apply {
            isEnabled = false
            setOnClickListener(null)
        }
    }

    private fun confirmOpenFaq(url: String) {
        FaqRedirect.confirm(this, url)
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
            wizardSlots.updateRoiSummary()
            // Cheap no-op when the reference/ROI have not changed since the
            // last measurement; covers inputs that arrived before this page.
            requestSubsetRecommendation()
        }
        if (target == 3) {
            sweepHelper.refreshSweepPlan()
        }

        checkReady()
        wizardCoach.maybeShow(target)
    }

    private fun checkReady() {
        readyGate.apply(isProcessing, sweepHelper = if (::sweepHelper.isInitialized) sweepHelper else null)
    }

    private fun consumePickerHandOff() {
        intent.getStringExtra(DicKeys.PICKED_REF_URI)?.let {
            intent.removeExtra(DicKeys.PICKED_REF_URI)
            handleReferenceImage(it.toUri())
        }
        intent.getStringExtra(DicKeys.PICKED_VIDEO_URI)?.let {
            intent.removeExtra(DicKeys.PICKED_VIDEO_URI)
            handleVideo(it.toUri())
        }
        intent.getStringArrayListExtra(DicKeys.PICKED_DEF_URIS)?.let { list ->
            intent.removeExtra(DicKeys.PICKED_DEF_URIS)
            if (list.isNotEmpty()) {
                onDeformedPicked(list.map { it.toUri() })
            }
        }
    }

    /**
     * Redraws the slots from the view model, after first reading back the
     * draft when this is a restore from a process death (ADR-005). Runs
     * straight through, without suspending, when there is nothing to read.
     */
    private fun restoreUiFromViewModel() {
        lifecycleScope.launch {
            val restore = viewModel.restoreDraft()
            val bytes = viewModel.refBytes
            if (bytes != null) {
                // Through the loader, not the native decoder directly: a RAW
                // reference is a headerless RGBA blob, which OpenCV cannot read.
                refPreviewBmp = ReferencePreviewLoader.load(
                    ReferencePreviewLoader.Request(
                        bytes = bytes,
                        intentWidth = viewModel.realRefWidth,
                        intentHeight = viewModel.realRefHeight,
                        previewMaxEdge = com.indicvision.semper.imaging.BitmapDecode.PREVIEW_MAX_EDGE,
                    ),
                ).bitmap
            }
            wizardSlots.refreshRefSlot(refPreviewBmp)
            wizardSlots.refreshDefSlot()
            when (restore) {
                // Re-enter the page so it re-measures what it shows.
                AnalysisViewModel.DraftRestore.RESTORED -> goToStep(viewModel.wizardStep, animate = false)
                AnalysisViewModel.DraftRestore.LOST -> {
                    goToStep(1, animate = false)
                    val lost = getString(R.string.wizard_draft_lost)
                    Snackbar.make(findViewById(android.R.id.content), lost, FaqRedirect.durationFor(lost))
                        .setAnchorView(binding.bottomNav)
                        .show()
                }
                AnalysisViewModel.DraftRestore.NONE -> Unit
            }
            checkReady()
            applySubsetRecommendation()
        }
    }

    /** Clears a custom crop and treats the whole reference frame as the ROI. */
    private fun applyFullImageRoi() {
        if (viewModel.realRefWidth > 0) {
            viewModel.hasCustomRoi = false
            viewModel.roiMaskBytes = null
            viewModel.roi = Roi.full(viewModel.refSize)
        }
        wizardSlots.updateRoiSummary()
        checkReady()
        requestSubsetRecommendation()
    }
}
