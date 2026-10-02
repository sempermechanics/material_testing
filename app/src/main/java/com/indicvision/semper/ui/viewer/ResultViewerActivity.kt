// Result viewer Activity: frame scrubbing, overlays, tap-to-probe and export
// live on one screen. Size and branching are inherent; suppress rather than
// baseline so new findings elsewhere still fail CI.

@file:Suppress(
    "TooManyFunctions",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
    "LargeClass",
)
@file:SuppressLint("SetTextI18n")

package com.indicvision.semper.ui.viewer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Trace
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.PopupWindow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.session.SessionPaths
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.databinding.ActivityResultViewerBinding
import com.indicvision.semper.databinding.DialogCustomScaleBinding
import com.indicvision.semper.databinding.PopupFieldOptionsBinding
import com.indicvision.semper.field.DicResult
import com.indicvision.semper.field.FieldStats
import com.indicvision.semper.field.FrameParams
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.ValueRange
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.report.BakedHeatmap
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportImageNames
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.common.CrispToast
import com.indicvision.semper.ui.common.FaqRedirect
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.SerialJob
import com.indicvision.semper.ui.common.TransferBannerController
import com.indicvision.semper.ui.common.commitOnDone
import com.indicvision.semper.ui.common.hideKeyboard
import com.indicvision.semper.ui.home.HomeActivity
import com.indicvision.semper.ui.viewer.inspect.ViewerInspectHelper
import com.indicvision.semper.ui.viewer.share.ShareCenter
import com.indicvision.semper.ui.viewer.share.ShareExportUi
import com.indicvision.semper.ui.viewer.share.ShareKind
import com.indicvision.semper.ui.viewer.share.ViewerReportFactory
import com.indicvision.semper.ui.viewer.summary.SummaryAnimation
import com.indicvision.semper.ui.viewer.summary.SummaryCaption
import com.indicvision.semper.ui.viewer.summary.ViewerSummaryHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Results browser: renders each frame's displacement/strain heatmap over that
 * frame's own photo, drawn where the points moved to (the reference when the
 * photo is not on disk), with frame scrubbing, tap-to-probe readings, custom color scales,
 * and all exports (PDF/CSV/PNG/ZIP via [ShareCenter]).
 */
@MainThread
class ResultViewerActivity : AppCompatActivity() {

    private val viewerVm: ResultViewerViewModel by viewModels()

    internal lateinit var binding: ActivityResultViewerBinding

    private var chromeVisible = true

    /** Max / min (with coordinates) / mean for the info peek sheet. */
    private var detailStats: String = ""

    // Not while a frame number is being typed: hiding the scrubber takes the
    // field's focus, which commits it and closes the keyboard mid-number. The
    // commit on focus loss bumps the chrome, so the hide is rescheduled then.
    private val hideChromeRunnable = Runnable {
        if (!binding.etFrameNumber.hasFocus()) fadeChrome(visible = false)
    }

    internal lateinit var shareBanner: TransferBannerController
    private val chromeHideDelayMs = 2_500L

    /** Shows the running exports, which live in [viewerVm] and outlive this screen's rotations. */
    internal lateinit var shareExports: ShareExportUi

    /** Stashed while the SAF save-as picker is open for a slow share export. */
    private var pendingShareKind: ShareKind? = null

    /** Run once the frame set is read; see [whenFrameSetLoaded]. */
    private val afterFrameSet = mutableListOf<() -> Unit>()

    private val createShareDocument = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val kind = pendingShareKind
        pendingShareKind = null
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null || kind == null) return@registerForActivityResult
        viewerVm.setPendingSave(kind.wire, uri)
        startPendingSave()
    }

    /**
     * Starts the ViewModel's pending save-as export once the frames it covers
     * are known. Taking it clears it, so a viewer recreated after the export
     * started does not start it again.
     */
    private fun startPendingSave() = whenFrameSetLoaded {
        val (kind, uri) = viewerVm.takePendingSave() ?: return@whenFrameSetLoaded
        ShareCenter(this).writeKindToUri(kind, uri)
    }

    /** Runs [action] now if the frame set is read, else right after [onFrameSetRead]. */
    private fun whenFrameSetLoaded(action: () -> Unit) {
        if (frameSetLoaded) action() else afterFrameSet += action
    }

    private lateinit var inspect: ViewerInspectHelper

    internal var rawData: FloatArray? = null

    /** The reference's true size: from the Intent, else read from its header off the main thread. */
    internal var imageSize: ImageSize = ImageSize.UNKNOWN
        private set

    /** Every frame's solver parameters; a sweep varies them frame by frame. */
    internal val frameParams: FrameParams by lazy { args.frameParams }

    /**
     * Step size of the frame on screen. A parameter sweep varies it from frame
     * to frame — rendering, point picking and the report all key off it — so it
     * is re-read whenever a frame loads rather than fixed at launch.
     */
    internal var step = 5

    /** Axis of the study's line cut through the ROI centre. */
    internal val lineCutHorizontal: Boolean get() = args.sweep?.lineCutHorizontal ?: true

    /** True when the frames are parameter combinations rather than images. */
    internal val isSweep: Boolean get() = args.sweep != null

    /** The ROI the run solved over. */
    internal val roi: Roi by lazy { args.roi }

    /** The reference at display size. Exports and the report read it; it is never a frame's photo. */
    internal var cachedBaseImage: Bitmap? = null
    private var cachedHeatmap: Bitmap? = null

    /**
     * Each frame's own photo by position, looked up off the main thread in
     * [readFrameDat]; "" when it is not on disk. See [onFramePhoto].
     */
    private val framePhotos = ConcurrentHashMap<Int, String>()

    /** The frame photo under the map now, and its path; null while the reference is shown. */
    private var framePhotoBitmap: Bitmap? = null
    private var framePhotoPath: String? = null
    private val framePhotoJob = SerialJob()

    /** True once [cachedBaseImage] is the image under the map. */
    private var referenceShown = false
    internal var currentTypeString: String
        get() = viewerVm.currentTypeString
        set(value) {
            viewerVm.currentTypeString = value
        }
    private var isGeneratingHeatmap = false

    private var batchFiles: List<File> = emptyList()

    /** The planned frame behind each of [batchFiles], by position; set with it. */
    private var plannedFrames: List<Int> = emptyList()

    /**
     * Largest `.dat` size, computed once when [batchFiles] is set. The prefetch
     * heap guard used to `stat()` every file on every frame load (3F syscalls per
     * scrub step); the file set never changes after onCreate, so one scan suffices.
     */
    private var maxDatBytes: Long = 0L

    private var refImagePath: String? = null
    private var defImagePaths: List<String> = emptyList()
    internal var currentFrameIndex: Int
        get() = viewerVm.currentFrameIndex
        set(value) {
            viewerVm.currentFrameIndex = value
        }
    private val loadFrameJob = SerialJob()

    /** The single in-flight look-ahead worker; see [prefetchAround]. */
    private val prefetchJob = SerialJob()

    /** Previous look-ahead centre, used to infer scrub direction. */
    private var lastPrefetchCenter = 0
    private val visualizationJob = SerialJob()
    private val scrubDebounceJob = SerialJob()
    private val refDecodeJob = SerialJob()

    private val scrubCache = ScrubFrameCache()

    internal var currentDataIndex: Int
        get() = viewerVm.currentDataIndex
        set(value) {
            viewerVm.currentDataIndex = value
        }

    /** The range of the heatmap on screen; null until one is shown. */
    private var shownRange: ValueRange? = null

    /** Fixed colour scales per field; in the ViewModel so a rotation keeps them. */
    private val customBoundsMap: MutableMap<Int, ValueRange> get() = viewerVm.customBounds

    private lateinit var summary: ViewerSummaryHelper

    /**
     * True while the summary animation is up instead of a frame. It sits before
     * frame 1: Prev from frame 1 reaches it, Next leaves it.
     */
    private var showingSummary = false

    /** True while the looping summary GIF is the thing on screen. */
    internal val isShowingSummary: Boolean get() = showingSummary

    internal fun summaryBatchFiles(): List<File> = batchFiles

    /** How many frames this analysis actually holds. */
    internal fun frameCount(): Int = batchFiles.size

    /**
     * The fixed colour scale of field [dataIndex], or null for the engine's
     * auto scale (the frame's own clamped range). Sequence-global scale is
     * reserved for the summary GIF / share animations, not the on-screen frame.
     */
    internal fun customBoundsFor(dataIndex: Int): ValueRange? = customBoundsMap[dataIndex]

    /** Called when [ViewerSummaryHelper] finishes the whole-sequence range pass. */
    internal fun onSequenceRangesReady() {
        // The summary colour bar is sequence-global; refresh ⓘ so it quotes
        // the same ends instead of the hidden first frame's extrema.
        if (!showingSummary) return
        val data = rawData ?: return
        applyFieldMetrics(fieldMetricsFor(currentFrameIndex, currentDataIndex, data), currentDataIndex)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.viewColorScale.background = ColorScaleBar.drawable(resources)

        binding.imgHeatmapOverlay.setOnTouchListener { _, event -> binding.imgBaseResult.dispatchTouchEvent(event) }

        shareBanner = TransferBannerController(binding.transferBannerRoot.root)
        // Re-attaches any export a rotation left running.
        shareExports = ShareExportUi(this, viewerVm.exports).also { it.attach() }

        Insets.padTop(binding.viewerTopStack)
        // Lifted, not padded, above the keyboard: the image is fitted to the
        // scrubber's height (wireContentInsets), so growing it would refit the frame.
        Insets.padBottomLiftAboveIme(binding.layoutScrubber)
        wireContentInsets()

        inspect = ViewerInspectHelper(this)
        // Warm [sessionRecord] here rather than at the share tap that needs it:
        // the lazy reads the session index off disk, and by lazy is synchronized,
        // so a tap arriving mid-read waits on the read it would have done itself
        // and never on a second one.
        lifecycleScope.launch(Dispatchers.IO) { sessionRecord }

        if (savedInstanceState != null) {
            val savedProbe = savedInstanceState.getInt(STATE_PROBE_INDEX, -1)
            inspect.restoreProbe(savedProbe)
            currentFrameIndex = savedInstanceState.getInt(STATE_FRAME, 0)
            showingSummary = savedInstanceState.getBoolean(STATE_SHOWING_SUMMARY, false)
            pendingShareKind = ShareKind.fromWire(savedInstanceState.getString(STATE_SHARE_KIND))
        } else {
            // A lattice node tap asks to open on a specific frame; clamped once
            // the batch is loaded below.
            currentFrameIndex = args.startFrame ?: 0
            // Otherwise the summary is what the viewer opens on — it answers
            // "what happened across the test" before any single frame does.
            // Sweeps never use the summary slot (combinations are not a time series).
            showingSummary = args.startFrame == null
        }
        // A save-as picked before the last viewer had listed its frames (a
        // rotation, or process death) waits in the ViewModel for this one.
        if (viewerVm.hasPendingSave) startPendingSave()

        imageSize = args.imageSize
        step = frameParams.base.step
        // Sweep extras are available now; drop any restored summary flag.
        if (isSweep) showingSummary = false

        val refPath = args.refPath.ifBlank { null }
        refImagePath = refPath
        // Every writer puts the image size on the Intent; only an old one makes
        // the reference's header be read for it, off the main thread below.
        val dimsKnown = imageSize.isKnown
        if (dimsKnown) showReference(refPath)

        summary = ViewerSummaryHelper(this)

        // The directory listings, and a stat per frame, used to run here on the
        // main thread on every open. The first frame (and, with it, everything
        // the batch drives) starts once they are read, as it did before.
        val knownSize = imageSize
        lifecycleScope.launch {
            val set = withContext(frameSetDispatcher) { readFrameSet(refPath, knownSize) }
            onFrameSetRead(set, refPath, dimsKnown)
        }

        binding.btnPrevFrame.setOnClickListener {
            bumpChrome()
            stepFrame(-1)
        }
        binding.btnNextFrame.setOnClickListener {
            bumpChrome()
            stepFrame(1)
        }

        wireFrameJump()

        binding.imgBaseResult.onMatrixChangedListener = {
            applyHeatmapMatrix()
            inspect.refreshCrosshairs()
            bumpChrome()
        }

        // Field FAB: shows the current field, tap opens a glass-pill popup of all
        // five with the live field checked. The live field comes back from the
        // ViewModel after a rotation, so re-derive the label or it disagrees
        // with the heatmap.
        binding.btnFieldFab.text = ViewerFieldPills.BY_ID[ViewerFieldPills.idFor(currentDataIndex)]?.first ?: "U"
        binding.btnFieldFab.setOnClickListener {
            bumpChrome()
            showFieldPopup(it)
        }

        binding.btnViewerBack.setOnClickListener { finish() }
        val canShare = LicenseEntitlements.shareEnabled(this)
        binding.btnViewerShare.alpha = if (canShare) 1f else LicenseEntitlements.BLOCKED_ALPHA
        binding.btnViewerShare.setOnClickListener {
            if (!LicenseEntitlements.shareEnabled(this)) {
                CrispToast.show(this, getString(R.string.share_licensed_only), long = true)
                return@setOnClickListener
            }
            showShareSheet()
        }
        binding.btnViewerHome.setOnClickListener { goHome() }
        binding.btnViewerSettingsInfo.setOnClickListener {
            bumpChrome()
            ViewerSettingsSheet.show(this)
        }

        binding.layoutColorScale.setOnClickListener {
            bumpChrome()
            showCustomScaleDialog()
        }
        inspect.wireTapHandling()
        wireSummaryGestures()

        binding.imgBaseResult.post {
            inspect.refreshCrosshairs()
            bumpChrome()
        }
    }

    /** What [onCreate] needs from disk before the first frame can load; see [readFrameSet]. */
    private class FrameSet(
        val imageSize: ImageSize,
        val defImagePaths: List<String>,
        val batchFiles: List<File>,
        val plannedFrames: List<Int>,
        val maxDatBytes: Long,
    )

    /** True once [onFrameSetRead] has run: [batchFiles] and the rest are final. */
    internal var frameSetLoaded = false
        private set

    /** Where [readFrameSet] runs. A test holds it to see what onCreate does without it. */
    @VisibleForTesting
    internal var frameSetDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Reads the batch listing, the deformed originals, each frame's size and (for an
     * Intent without it) the reference's dimensions. Disk only — call it off the
     * main thread.
     */
    private fun readFrameSet(refPath: String?, knownSize: ImageSize): FrameSet {
        var size = knownSize
        if (!size.isKnown && refPath != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(refPath, bounds)
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                size = ImageSize(bounds.outWidth, bounds.outHeight)
            }
        }
        val batchDirPath = args.batchDirPath
        // Prefer the raw deformed originals persisted in the session dir (survive
        // reopen/eviction); fall back to the just-analysed session's temp paths.
        val rawDeformedDir = batchDirPath?.let { File(it, SessionPaths.RAW_DEFORMED_SUBDIR) }
        val defPaths = rawDeformedDir?.takeIf { it.isDirectory }
            ?.listFiles()?.sortedBy { it.name }?.map { it.absolutePath }
            ?: args.defFilePaths
        val dir = batchDirPath?.let { File(it) }?.takeIf { it.isDirectory }
        val files = dir?.listFiles { file -> file.extension == "dat" }?.sortedBy { it.name }.orEmpty()
        return FrameSet(
            imageSize = size,
            defImagePaths = defPaths,
            batchFiles = files,
            plannedFrames = SessionPaths.plannedFrameIndices(files),
            // Once, here: the prefetch heap guard used to stat() every file per load.
            maxDatBytes = files.maxOfOrNull { it.length() } ?: 0L,
        )
    }

    /** Puts the reference on screen at its true dimensions (decoded off-main, display size). */
    private fun showReference(refPath: String?) {
        // True sensor dims stay on the intent for math / probe / export; the
        // on-screen bitmap is decoded off-main at ImageView scale.
        binding.imgBaseResult.setTrueImageDimensions(imageSize.width, imageSize.height)
        updateHeatmapFitBounds(data = null)
        if (refPath != null) {
            decodeReferenceForDisplay(refPath)
        }
    }

    /** The rest of [onCreate], once [readFrameSet] is back: open the batch on its first frame or summary. */
    private fun onFrameSetRead(set: FrameSet, refPath: String?, referenceShownAlready: Boolean) {
        imageSize = set.imageSize
        if (!referenceShownAlready) showReference(refPath)
        defImagePaths = set.defImagePaths
        batchFiles = set.batchFiles
        plannedFrames = set.plannedFrames
        maxDatBytes = set.maxDatBytes
        frameSetLoaded = true

        if (batchFiles.isNotEmpty()) {
            // A START_FRAME (or restored index) past the batch would load nothing.
            currentFrameIndex = currentFrameIndex.coerceIn(0, batchFiles.lastIndex)
            binding.tvFrameTotal.text = getString(R.string.frame_total_fmt, batchFiles.size)
            loadFrameData(currentFrameIndex)
            // Summary GIF / share animations are single-setting only.
            if (!isSweep && batchFiles.size > 1) summary.start()
            if (showingSummary && !isSweep) {
                enterSummary()
            } else {
                showingSummary = false
                updateNavButtons()
                bumpChrome()
            }
        } else {
            showingSummary = false
            FaqRedirect.snackbar(this, R.string.no_batch_data, R.string.url_faq_no_batch_data)
        }
        val waiting = afterFrameSet.toList()
        afterFrameSet.clear()
        waiting.forEach { it() }
    }

    /**
     * Measures the top bar and scrub bar once they've laid out and feeds those
     * sizes to the image as content insets, so the heatmap's fit-to-screen view
     * fills the space between them (full width). The colour scale is a sibling
     * overlay on the right — it may cover the image; it is not a reserved inset.
     * Inset values only change on a real layout event (initial layout, rotation)
     * — [fadeChrome] toggles VISIBLE/INVISIBLE, never GONE, so a bar keeps its
     * laid-out size while faded and the safe area stays stable through the
     * auto-hide animation.
     */
    private fun wireContentInsets() {
        binding.imgBaseResult.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val top = binding.chromeTop.height
                    val bottom = binding.layoutScrubber.height
                    if (top > 0 && bottom > 0) {
                        binding.imgBaseResult.setContentInsets(top = top, bottom = bottom)
                    }
                }
            },
        )
    }

    /** Glass-pill popup listing every field; the live field is checked. */
    private fun showFieldPopup(anchor: View) {
        val popup = PopupFieldOptionsBinding.inflate(layoutInflater)
        val window = PopupWindow(
            popup.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        window.isOutsideTouchable = true
        ViewerFieldPills.BY_ID.forEach { (id, pair) ->
            val (label, index) = pair
            // Looked up by id: the pills' id ↔ field map is ViewerFieldPills'.
            val button = popup.root.findViewById<MaterialButton>(id)
            button.text = label
            button.isCheckable = true
            button.isChecked = index == currentDataIndex
            button.setOnClickListener {
                if (index == currentDataIndex) {
                    window.dismiss()
                    return@setOnClickListener
                }
                currentTypeString = label
                currentDataIndex = index
                binding.btnFieldFab.text = label
                // Caption + probe value are refreshed by updateVisualization once the
                // new field's metrics are computed off the main thread.
                updateVisualization(currentDataIndex)
                summary.onFieldChanged()
                if (showingSummary) binding.tvFrameCounter.text = summary.counterText()
                inspect.refreshCrosshairs()
                bumpChrome()
                window.dismiss()
            }
        }
        window.showAsDropDown(anchor, 0, 4)
    }

    /** Clears the back stack to Home. Used by the top-bar Home action. */
    internal fun goHome() {
        val home = Intent(this, HomeActivity::class.java)
        home.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(home)
        finish()
    }

    /** Max / min (with coordinates) / mean for the info peek sheet. */
    internal fun detailStatsText(): String = detailStats.ifBlank { getString(R.string.stat_empty) }

    /** Bring edge chrome back, then schedule auto-hide. */
    internal fun bumpChrome() {
        if (chromeVisible) {
            binding.chromeTop.removeCallbacks(hideChromeRunnable)
            binding.chromeTop.postDelayed(hideChromeRunnable, chromeHideDelayMs)
            return
        }
        fadeChrome(visible = true)
        binding.chromeTop.removeCallbacks(hideChromeRunnable)
        binding.chromeTop.postDelayed(hideChromeRunnable, chromeHideDelayMs)
    }

    /**
     * Centre double-tap while chrome is hidden: show the bars. Returns true when
     * consumed so zoom does not also run.
     */
    internal fun showChromeIfHidden(): Boolean {
        if (chromeVisible) return false
        bumpChrome()
        return true
    }

    private fun fadeChrome(visible: Boolean) {
        chromeVisible = visible
        val bars = with(binding) { listOf(chromeTop, layoutScrubber, btnFieldFab, layoutColorScale) }
        bars.forEach { bar ->
            bar.animate().cancel()
            if (visible) {
                bar.visibility = View.VISIBLE
                if (bar.alpha < 0.99f) {
                    bar.animate().alpha(1f).setDuration(180L).start()
                } else {
                    bar.alpha = 1f
                }
            } else {
                bar.animate()
                    .alpha(0f)
                    .setDuration(320L)
                    .withEndAction { bar.visibility = View.INVISIBLE }
                    .start()
            }
        }
    }

    /** Advance or retreat one frame (or leave/enter the summary). Used by buttons and fling. */
    internal fun stepFrame(delta: Int) {
        bumpChrome()
        if (delta == 0) return
        if (delta > 0) {
            if (showingSummary) {
                leaveSummary()
            } else if (currentFrameIndex < batchFiles.size - 1) {
                currentFrameIndex++
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
            return
        }
        when {
            showingSummary -> Unit
            currentFrameIndex == 0 -> if (!isSweep) enterSummary()
            else -> {
                currentFrameIndex--
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::binding.isInitialized) {
            binding.chromeTop.removeCallbacks(hideChromeRunnable)
            binding.chromeTop.animate().cancel()
            binding.layoutScrubber.animate().cancel()
            binding.btnFieldFab.animate().cancel()
            binding.layoutColorScale.animate().cancel()
        }
        // The exports themselves run on in the ViewModel; only their dialogs go.
        if (::shareExports.isInitialized) shareExports.detach()
        loadFrameJob.cancel()
        visualizationJob.cancel()
        scrubDebounceJob.cancel()
        refDecodeJob.cancel()
        framePhotoJob.cancel()
        summary.cancel()
        scrubCache.clear(except = cachedHeatmap)
        inspect.clearSpatialIndex()
    }

    /**
     * Decode the reference off the main thread with [BitmapFactory.Options.inSampleSize]
     * sized to the ImageView (capped by [VisualizationEngine.DISPLAY_MAX_EDGE]).
     */
    private fun decodeReferenceForDisplay(refPath: String) {
        val image = binding.imgBaseResult
        image.post {
            val viewW = image.width.coerceAtLeast(1)
            val viewH = image.height.coerceAtLeast(1)
            val reqW = viewW.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            val reqH = viewH.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            refDecodeJob.launch(lifecycleScope, Dispatchers.IO) {
                val bmp = BitmapDecode.decodeFileForView(
                    refPath,
                    reqW,
                    reqH,
                    rawWidth = imageSize.width,
                    rawHeight = imageSize.height,
                )
                withContext(Dispatchers.Main) {
                    if (isDestroyed || isFinishing) {
                        bmp?.recycle()
                        return@withContext
                    }
                    cachedBaseImage = bmp
                    // A frame already on its own photo keeps it.
                    if (!onFramePhoto) showReferenceBase()
                }
            }
        }
    }

    /** Debounce rapid Next/Prev so only the settled frame is decoded. */
    private fun requestFrameLoad(debounced: Boolean) {
        scrubDebounceJob.cancel()
        if (!debounced) {
            loadFrameData(currentFrameIndex)
            return
        }
        scrubDebounceJob.launch(lifecycleScope) {
            delay(SCRUB_DEBOUNCE_MS)
            loadFrameData(currentFrameIndex)
        }
    }

    private fun applyHeatmapMatrix() {
        val hm = cachedHeatmap
        val zoom = binding.imgBaseResult.getZoomMatrix()
        val overlay = binding.imgHeatmapOverlay
        if (hm == null || hm.isRecycled || hm.width <= 0 || imageSize.width <= 0) {
            overlay.imageMatrix = zoom
        } else {
            val m = Matrix(zoom)
            m.preScale(imageSize.width.toFloat() / hm.width, imageSize.height.toFloat() / hm.height)
            overlay.imageMatrix = m
        }
        overlay.invalidate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PROBE_INDEX, inspect.lastClosestIdx)
        outState.putInt(STATE_FRAME, currentFrameIndex)
        outState.putBoolean(STATE_SHOWING_SUMMARY, showingSummary)
        pendingShareKind?.let { outState.putString(STATE_SHARE_KIND, it.wire) }
    }

    private fun loadFrameData(index: Int) {
        if (index < 0 || index >= batchFiles.size) return

        scrubCache.getData(index)?.let { cached ->
            applyLoadedFrame(index, cached)
            prefetchAround(index)
            return
        }

        loadFrameJob.launch(lifecycleScope, Dispatchers.IO) {
            try {
                val data = readFrameDat(index) ?: return@launch
                scrubCache.putData(index, data)

                withContext(Dispatchers.Main) {
                    if (currentFrameIndex != index) return@withContext
                    applyLoadedFrame(index, data)
                }
                prefetchAround(index)
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: OutOfMemoryError) {
                // Error, not Exception — must be caught explicitly or the process dies.
                Timber.e(e, "OOM loading frame $index")
                scrubCache.clear()
                withContext(Dispatchers.Main) {
                    FaqRedirect.snackbar(
                        this@ResultViewerActivity,
                        R.string.viewer_frame_oom,
                        R.string.url_faq_viewer_oom,
                    )
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.e(e, "Failed to load frame $index")
            }
        }
    }

    /**
     * Fills [scrubCache] with a bounded look-ahead window around [center], so a scrub
     * step is usually a cache hit without letting memory grow with scrub speed.
     *
     * One serialized worker, not a job per neighbour: the previous version launched up
     * to two uncancelled coroutines on *every* frame load, so a fast scrub could have a
     * dozen concurrent decodes in flight — each holding a full frame — while the cache
     * only ever kept the last two, so most of that work became garbage on arrival. Peak
     * memory then scaled with how fast the user scrubbed rather than with any bound.
     *
     * Here exactly one decode runs at a time, the window is cancelled and restarted when
     * the user moves on, and each frame is admitted only if the cache still has room
     * (count *and* bytes) and the heap guard passes — so the queue stays warm while peak
     * stays flat.
     */
    private fun prefetchAround(center: Int) {
        prefetchJob.cancel()
        val direction = if (center >= lastPrefetchCenter) 1 else -1
        lastPrefetchCenter = center

        prefetchJob.launch(lifecycleScope, Dispatchers.IO) {
            try {
                for (offset in lookAheadOffsets(direction)) {
                    val index = center + offset
                    if (index < 0 || index >= batchFiles.size) continue
                    if (scrubCache.getData(index) != null) continue
                    // Re-checked per frame: both the cache budget and the heap can be
                    // consumed by the foreground frame while this window is filling.
                    if (scrubCache.freeSlots(maxDatBytes) <= 0) return@launch
                    if (!heapHasRoomForPrefetch()) return@launch
                    val data = readFrameDat(index) ?: continue
                    scrubCache.putData(index, data)
                    yield() // stay promptly cancellable between frames
                }
            } catch (e: CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: OutOfMemoryError) {
                Timber.w(e, "Prefetch around frame %d OOM — clearing scrub cache", center)
                scrubCache.clear()
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "Prefetch around frame %d failed", center)
            }
        }
    }

    /**
     * Frames to warm, nearest first and biased to the scrub [direction], with one frame
     * behind so reversing is still a hit. Length is capped by the cache, so this never
     * queues more than can be held.
     */
    private fun lookAheadOffsets(direction: Int): IntArray {
        val ahead = ScrubFrameCache.DEFAULT_MAX_FRAMES - 1
        val offsets = IntArray(ahead + 1)
        for (i in 0 until ahead) offsets[i] = direction * (i + 1)
        offsets[ahead] = -direction
        return offsets
    }

    private fun readFrameDat(index: Int): FloatArray? {
        Trace.beginSection("Semper.viewer.decodeDat")
        try {
            // Off the main thread, before the frame can be shown: which photo it
            // goes on decides how its map is drawn.
            framePhotos.getOrPut(index) { deformedImagePathAt(index).orEmpty() }
            val file = batchFiles[index]
            val data = DicResult.decodeDatFile(file)
            if (data == null) {
                Timber.e("Invalid file size for frame $index")
            }
            return data
        } finally {
            Trace.endSection()
        }
    }

    /** Rough guard: need headroom for another full-frame FloatArray (~file size). */
    private fun heapHasRoomForPrefetch(): Boolean {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (maxDatBytes <= 0L) return false
        return free > maxDatBytes * 3
    }

    private fun applyLoadedFrame(index: Int, data: FloatArray) {
        rawData = data
        // A sweep's frames each have their own grid pitch.
        step = frameParams.at(index).step
        // Invalidate rather than rebuild: the O(n) bucket map is only needed for
        // probe nearest-point taps, and findNearestDataPoint builds it lazily
        // for the new frame. Scrubbing large frames no longer pays for an unused index.
        inspect.clearSpatialIndex()
        updateHeatmapFitBounds(data)
        showFrameBase(index)
        val displayName = frameDisplayName(index)
        if (!showingSummary) {
            binding.tvFrameCounter.text = "$displayName (${index + 1} / ${batchFiles.size})"
        }
        syncFrameNumber()
        // updateVisualization warms this frame's stats off the main thread
        // and pushes them to the caption when the render completes.
        updateVisualization(currentDataIndex)
        if (inspect.lastClosestIdx != -1) {
            inspect.refreshCrosshairs()
        }
    }

    /**
     * Rest-fit the coloured region: custom ROI if set, else accepted-point
     * bounds for this frame, else the full specimen. On the frame's own photo
     * the box also takes in where the points moved to, so the displaced map
     * stays in view.
     */
    private fun updateHeatmapFitBounds(data: FloatArray?) {
        if (!imageSize.isKnown) return
        val box = HeatmapFit.resolve(imageSize, roi, accepted = data?.let { DicResult.acceptedPointsBounds(it) })
        val moved = data?.takeIf { onFramePhoto }?.let { DicResult.acceptedPointsBounds(it, displaced = true) }
        if (moved != null) {
            box[HeatmapFit.LEFT] = minOf(box[HeatmapFit.LEFT], moved[HeatmapFit.LEFT])
            box[HeatmapFit.TOP] = minOf(box[HeatmapFit.TOP], moved[HeatmapFit.TOP])
            box[HeatmapFit.RIGHT] = maxOf(box[HeatmapFit.RIGHT], moved[HeatmapFit.RIGHT])
            box[HeatmapFit.BOTTOM] = maxOf(box[HeatmapFit.BOTTOM], moved[HeatmapFit.BOTTOM])
        }
        binding.imgBaseResult.setFitBounds(box[0], box[1], box[2], box[3])
    }

    /** The photo of the frame at [position] when it is on disk, else null (the reference is shown). */
    private fun framePhotoPathFor(position: Int): String? = framePhotos[position]?.ifEmpty { null }

    /**
     * True when the frame on screen is drawn over its own photo, with its map
     * at the displaced positions. False shows the reference under the
     * reference-position map: a frame whose photo is gone (a restore without
     * it, storage reclaim) still lines up.
     */
    internal val onFramePhoto: Boolean get() = framePhotoPathFor(currentFrameIndex) != null

    /**
     * Puts the photo of the frame at [index] under its map — or the reference,
     * when that frame has none. Decoded off the main thread at display size,
     * like the reference; a photo that fails to decode drops the frame back to
     * the reference and its map with it.
     */
    private fun showFrameBase(index: Int) {
        framePhotoJob.cancel()
        val path = framePhotoPathFor(index)
        if (path == null) {
            showReferenceBase()
            return
        }
        if (path == framePhotoPath) return
        val image = binding.imgBaseResult
        val reqW = image.width.takeIf { it > 0 }?.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            ?: VisualizationEngine.DISPLAY_MAX_EDGE
        val reqH = image.height.takeIf { it > 0 }?.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            ?: VisualizationEngine.DISPLAY_MAX_EDGE
        framePhotoJob.launch(lifecycleScope) {
            val bmp = withContext(Dispatchers.IO) {
                BitmapDecode.decodeFileForView(
                    path,
                    reqW,
                    reqH,
                    rawWidth = imageSize.width,
                    rawHeight = imageSize.height,
                )
            }
            if (isDestroyed || isFinishing) {
                bmp?.recycle()
                return@launch
            }
            if (bmp == null) {
                Timber.w("Frame %d photo did not decode; showing it on the reference", index)
                framePhotos[index] = ""
                val data = rawData
                if (currentFrameIndex == index && data != null) applyLoadedFrame(index, data)
                return@launch
            }
            if (framePhotoPathFor(currentFrameIndex) != path) {
                bmp.recycle()
                return@launch
            }
            val previous = framePhotoBitmap
            framePhotoBitmap = bmp
            framePhotoPath = path
            image.setImageBitmap(bmp)
            referenceShown = false
            previous?.recycle()
        }
    }

    /** Puts the reference back under the map, once it is decoded, and frees any frame photo. */
    private fun showReferenceBase() {
        val reference = cachedBaseImage ?: return
        val previous = framePhotoBitmap
        if (previous == null && framePhotoPath == null && referenceShown) return
        framePhotoBitmap = null
        framePhotoPath = null
        binding.imgBaseResult.setImageBitmap(reference)
        referenceShown = true
        previous?.recycle()
    }

    /**
     * Same rest-fit box the summary GIF should fill. Custom ROI when set;
     * otherwise null so [SummaryAnimation] discovers accepted points from the
     * first readable frame.
     */
    internal fun summaryFitBounds(): FloatArray? = roi.takeIf { it.isCustomFor(imageSize) }?.toLtrb()

    private fun showCustomScaleDialog() {
        val dialog = DialogCustomScaleBinding.inflate(layoutInflater)

        val isStrain = DicResult.isStrainFieldIndex(currentDataIndex)
        val multiplier = DicResult.strainMultiplier(currentDataIndex)
        val unit = getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)

        dialog.tilScaleMax.hint = getString(R.string.scale_max_value, unit)
        dialog.tilScaleMin.hint = getString(R.string.scale_min_value, unit)

        val shown = shownRange.takeIf { cachedHeatmap != null && !showingSummary && !isGeneratingHeatmap }
        CustomScalePrefill.text(
            custom = customBoundsFor(currentDataIndex),
            shown = shown,
            multiplier = multiplier,
        )?.let { (min, max) ->
            dialog.etScaleMin.setText(min)
            dialog.etScaleMax.setText(max)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.scale_dialog_title, currentTypeString))
            .setView(dialog.root)
            .setPositiveButton(R.string.apply) { _, _ ->
                val maxVal = dialog.etScaleMax.text?.toString()?.toFloatOrNull()
                val minVal = dialog.etScaleMin.text?.toString()?.toFloatOrNull()

                if (maxVal != null && minVal != null && maxVal > minVal) {
                    customBoundsMap[currentDataIndex] = ValueRange(minVal / multiplier, maxVal / multiplier)
                    updateVisualization(currentDataIndex)
                    summary.onScaleChanged(currentDataIndex)
                } else {
                    FaqRedirect.snackbar(
                        this,
                        R.string.invalid_scale_inputs,
                        R.string.url_faq_custom_scale,
                    )
                }
            }
            .setNeutralButton(R.string.auto_scale) { _, _ ->
                customBoundsMap.remove(currentDataIndex)
                updateVisualization(currentDataIndex)
                summary.onScaleChanged(currentDataIndex)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateVisualization(index: Int) {
        val data = rawData ?: return
        isGeneratingHeatmap = true

        val custom = customBoundsFor(index)
        val displaced = onFramePhoto
        val heatKey = ScrubFrameCache.HeatKey(
            frame = currentFrameIndex,
            field = index,
            step = step,
            custom = custom,
            displaced = displaced,
        )

        val frameAtStart = currentFrameIndex

        scrubCache.getHeat(heatKey)?.let { hit ->
            visualizationJob.cancel()
            showHeatmap(hit, index)
            // A heatmap hit means this (frame,field) was visited before, so its
            // metrics are already cached — this read is O(1) on the main thread.
            applyFieldMetrics(fieldMetricsFor(frameAtStart, index, data), index)
            return
        }

        val size = imageSize
        val frameStep = step
        visualizationJob.launch(lifecycleScope, Dispatchers.Default) {
            // Warm the stats/extrema off the main thread, next to the heatmap render,
            // so the scrub settle never pays the O(n)+sort on the UI thread.
            val metrics = fieldMetricsFor(frameAtStart, index, data)
            val heatmap = if (displaced) {
                VisualizationEngine.generateDeformedHeatmap(
                    data,
                    size.width,
                    size.height,
                    index,
                    frameStep,
                    custom?.min,
                    custom?.max,
                    maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
                )
            } else {
                VisualizationEngine.generateHeatmap(
                    data,
                    size.width,
                    size.height,
                    index,
                    frameStep,
                    custom?.min,
                    custom?.max,
                    maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
                )
            }
            scrubCache.putHeat(heatKey, heatmap)

            withContext(Dispatchers.Main) {
                if (currentDataIndex != index || currentFrameIndex != frameAtStart) return@withContext
                showHeatmap(heatmap, index)
                applyFieldMetrics(metrics, index)
            }
        }
    }

    private fun showHeatmap(heatmap: BakedHeatmap, index: Int) {
        cachedHeatmap = heatmap.bitmap
        binding.imgHeatmapOverlay.scaleType = ImageView.ScaleType.MATRIX
        binding.imgHeatmapOverlay.setImageBitmap(heatmap.bitmap)
        applyHeatmapMatrix()

        shownRange = heatmap.range

        // While the summary is up the labels belong to its whole-sequence scale,
        // not to whichever frame happens to be loaded behind it.
        if (!showingSummary) {
            val isStrain = DicResult.isStrainFieldIndex(index)
            val multiplier = DicResult.strainMultiplier(index)
            val unit = getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)
            // ≤/≥, not "Min:"/"Max:": these are the 2nd/98th-percentile clamp the
            // colour ramp is built on (VisualizationEngine.computeSigmaClampedRange),
            // not the field's true extrema -- the ⓘ details sheet shows those,
            // via DicResult.fieldStats. Same wording as the summary-mode scale
            // (ViewerSummaryHelper) so the two paths agree.
            val minText = ReportBuilder.formatMetric(heatmap.min * multiplier)
            val maxText = ReportBuilder.formatMetric(heatmap.max * multiplier)
            binding.tvScaleMin.text = getString(R.string.scale_min_fmt, minText, unit)
            binding.tvScaleMax.text = getString(R.string.scale_max_fmt, maxText, unit)
        }
        isGeneratingHeatmap = false
    }

    /** What a report page reads from this viewer, as plain data an export can keep. */
    private fun reportSource(): ViewerReportFactory.Source = ViewerReportFactory.Source(
        args = args,
        imageSize = imageSize,
        plannedFrames = plannedFrames,
        defImagePaths = defImagePaths,
        displayBase = cachedBaseImage,
    )

    /**
     * The planned frame behind the [position]-th `.dat` on disk. A frame the
     * batch skipped leaves a gap in the numbering, so the two part ways there.
     */
    internal fun plannedFrameIndex(position: Int): Int = plannedFrames.getOrElse(position) { position }

    /**
     * What the frame at [position] is called on screen: its image name (or a
     * sweep's combination label), looked up by the planned frame, not the
     * position, so a frame after a skipped one keeps its own name.
     */
    internal fun frameDisplayName(position: Int): String {
        val planned = plannedFrameIndex(position)
        return ReportImageNames.frameName(args.frameNames, planned) ?: "Frame ${planned + 1}"
    }

    /**
     * The deformed image solved at [position], or null when it is not on disk.
     * Every node of a sweep solves the one deformed image. A batch looks its
     * frame up by the name the run persisted it under, since `raw_deformed/`
     * keeps the user's own file names and sorts them alphabetically, not in
     * frame order.
     */
    internal fun deformedImagePathAt(position: Int): String? =
        ViewerReportFactory.deformedImagePath(
            args,
            isSweep,
            defImagePaths,
            args.frameNames,
            plannedFrameIndex(position),
        )

    /**
     * A filename-safe base for exports, drawn from the specimen/reference name so
     * shared files read like "IMG_0768_report.pdf" instead of a generic prefix.
     * Falls back to the session name, then the first deformed frame, then "analysis".
     */
    private fun shareBaseName(): String {
        val record = sessionRecord
        val raw = record?.refName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: record?.name?.takeIf { it.isNotBlank() }
            ?: args.frameNames.firstOrNull()?.substringBeforeLast('.')
            ?: "analysis"
        return raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60).ifBlank { "analysis" }
    }

    /**
     * The stored record behind this viewer, or null when it was opened without
     * one (a run still in flight, or a legacy Intent).
     *
     * Read once and kept: the share name, the CSV and every page of an
     * all-frames report want the same few fields off it, and re-reading the
     * session index per report page would be a file read per page.
     */
    internal val sessionRecord: SessionRecord? by lazy {
        intent.getStringExtra(DicKeys.SESSION_LOCAL_ID)
            ?.let { runCatching { SessionStore.get(this, it) }.getOrNull() }
    }

    /**
     * This viewer's arguments, parsed once (ADR-003). The record fallback reads
     * the session index only for an Intent missing a key, which no current
     * writer produces.
     */
    internal val args: ViewerArgs by lazy { ViewerArgs.from(intent) { sessionRecord } }

    /**
     * Everything an export needs, or null before the frame set is read or when
     * there are no frames. The frame on screen may still be loading ([rawData]
     * null): only the photo kinds need it, and they read it from disk then.
     */
    internal fun buildShareSnapshot(): ShareCenter.Snapshot? {
        if (!frameSetLoaded || batchFiles.isEmpty()) return null
        return ShareCenter.Snapshot(
            data = rawData,
            batchFiles = batchFiles,
            baseName = shareBaseName(),
            frameIndex = currentFrameIndex,
            dataIndex = currentDataIndex,
            typeString = currentTypeString,
            summary = if (isSweep) null else summary.animation,
            summaryBounds = if (isSweep) {
                emptyMap()
            } else {
                SummaryAnimation.FIELDS.mapNotNull { (_, index) -> summary.boundsFor(index)?.let { index to it } }
                    .toMap()
            },
            reportSource = reportSource(),
        )
    }

    /** Share sheet (wireframe 08) - targets wired via ShareCenter. */
    private fun showShareSheet() {
        ShareCenter(this).show()
    }

    /** SAF CreateDocument for a slow export; generation starts only after a URI returns. */
    internal fun pickShareDocument(kind: ShareKind, filename: String) {
        pendingShareKind = kind
        createShareDocument.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = kind.saveMime
                putExtra(Intent.EXTRA_TITLE, filename)
            },
        )
    }

    /** Edge title + peek-sheet stats for [index], from pre-computed [metrics]. */
    private fun updateCaptionsFrom(metrics: FieldMetrics, index: Int) {
        val unit = if (DicResult.isStrainFieldIndex(index)) "mε" else "px"
        val frameBit = if (showingSummary) {
            getString(R.string.summary_title)
        } else {
            "${currentFrameIndex + 1} / ${batchFiles.size.coerceAtLeast(1)}"
        }
        binding.tvFinding.text = getString(R.string.viewer_edge_title_fmt, currentTypeString, frameBit)

        if (showingSummary) {
            detailStats = SummaryCaption.text(resources, summary.boundsFor(index), index, unit)
            binding.tvStatsCaption.text = detailStats
            return
        }

        val stats = metrics.stats
        if (stats == null) {
            detailStats = getString(R.string.stat_empty)
            binding.tvStatsCaption.text = detailStats
            return
        }
        val maxText = ReportBuilder.formatMetric(stats.max)
        val minText = ReportBuilder.formatMetric(stats.min)
        val meanText = ReportBuilder.formatMetric(stats.mean)
        val data = rawData
        detailStats = if (
            data != null &&
            metrics.maxIdx in data.indices &&
            metrics.minIdx in data.indices
        ) {
            getString(
                R.string.viewer_stats_fmt,
                maxText,
                data[metrics.maxIdx].toInt(),
                data[metrics.maxIdx + 1].toInt(),
                minText,
                data[metrics.minIdx].toInt(),
                data[metrics.minIdx + 1].toInt(),
                meanText,
                unit,
            )
        } else {
            getString(R.string.viewer_stats_plain_fmt, maxText, minText, meanText, unit)
        }
        binding.tvStatsCaption.text = detailStats
    }

    private fun updateNavButtons() {
        val prev = binding.btnPrevFrame
        val next = binding.btnNextFrame
        // Sweep: no summary slot, so Prev is inert on the first combination.
        prev.isEnabled =
            !showingSummary &&
            batchFiles.isNotEmpty() &&
            (currentFrameIndex > 0 || !isSweep)
        next.isEnabled = showingSummary || currentFrameIndex < batchFiles.size - 1

        prev.alpha = if (prev.isEnabled) 1.0f else 0.5f
        next.alpha = if (next.isEnabled) 1.0f else 0.5f
        // The number tracks the buttons, not the decode: a debounced scrub would
        // otherwise leave it a frame behind for as long as the load takes.
        syncFrameNumber()
    }

    // ── Summary slot ─────────────────────────────────────────────────────

    private fun wireSummaryGestures() {
        val gif = binding.imgSummary
        gif.onScrubListener = { stepFrame(it) }
        gif.onCenterDoubleTapShowChrome = { showChromeIfHidden() }
        gif.onChromeSwipeListener = { show -> if (show) bumpChrome() }
        gif.onTapListener = { _, _ -> bumpChrome() }
    }

    private fun enterSummary() {
        if (isSweep) return
        showingSummary = true
        inspect.dismissProbe()
        summary.show()
        binding.tvFrameCounter.text = summary.counterText()
        binding.layoutFrameJump.visibility = View.GONE
        binding.tvFinding.text = getString(
            R.string.viewer_edge_title_fmt,
            currentTypeString,
            getString(R.string.summary_title),
        )
        updateNavButtons()
        bumpChrome()
    }

    private fun leaveSummary() {
        showingSummary = false
        summary.hide()
        binding.layoutFrameJump.visibility = View.VISIBLE
        updateNavButtons()
        // Re-apply the frame's own labels and heatmap after the summary's.
        requestFrameLoad(debounced = false)
        bumpChrome()
    }

    // ── Typed frame jump ─────────────────────────────────────────────────

    private fun wireFrameJump() {
        val field = binding.etFrameNumber
        field.commitOnDone(EditorInfo.IME_ACTION_GO) { commitFrameJump() }
        field.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitFrameJump()
        }
    }

    /**
     * Applies what is typed in the frame field. Anything unparseable or outside
     * the batch restores the current number rather than jumping somewhere the
     * user did not ask for.
     */
    private fun commitFrameJump() {
        bumpChrome()
        val field = binding.etFrameNumber
        val typed = field.text?.toString()?.trim()?.toIntOrNull()
        val target = typed?.minus(1)?.takeIf { it in batchFiles.indices }
        if (target == null) {
            syncFrameNumber()
        } else if (target != currentFrameIndex || showingSummary) {
            if (showingSummary) leaveSummary()
            currentFrameIndex = target
            updateNavButtons()
            // A typed number is a settled destination, unlike a Next/Prev burst.
            requestFrameLoad(debounced = false)
        }
        field.clearFocus()
        field.hideKeyboard()
    }

    private fun syncFrameNumber() {
        val shown = if (showingSummary) "" else (currentFrameIndex + 1).toString()
        val field = binding.etFrameNumber
        if (field.text?.toString() != shown) field.setText(shown)
    }

    // ── Field metrics cache (caption + peek-sheet extrema) ───────────────────

    /** Immutable per-(frame,field) result: its stats and the indices of its extrema. */
    internal class FieldMetrics(val stats: FieldStats?, val maxIdx: Int, val minIdx: Int)

    /**
     * Memoised [FieldMetrics] keyed by (frameIndex, dataIndex). A decoded frame is
     * immutable, so these never need invalidation — only an LRU size bound. Computing
     * them is one walk of accepted points; caching means a field toggle or a
     * revisited frame costs nothing, and [updateVisualization] warms the entry on its
     * background thread so a scrub settle never does the work on the main thread.
     * Guarded by its own monitor (read on Main, written on Dispatchers.Default).
     */
    private val fieldMetricsCache = LinkedHashMap<Long, FieldMetrics>()

    internal fun fieldMetricsFor(frameIndex: Int, dataIndex: Int, data: FloatArray): FieldMetrics {
        val key = (frameIndex.toLong() shl Int.SIZE_BITS) or (dataIndex.toLong() and 0xFFFF_FFFFL)
        synchronized(fieldMetricsCache) { fieldMetricsCache[key]?.let { return it } }
        val stats = FieldStats.fromArray(DicResult.fieldStats(data, dataIndex))
        val (maxIdx, minIdx) = trueExtremaIndices(data, dataIndex)
        val metrics = FieldMetrics(stats, maxIdx, minIdx)
        synchronized(fieldMetricsCache) {
            fieldMetricsCache[key] = metrics
            if (fieldMetricsCache.size > FIELD_METRICS_CACHE_MAX) {
                val eldest = fieldMetricsCache.keys.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        return metrics
    }

    /**
     * Indices of the accepted points that carry this field's true min and max —
     * the same values [DicResult.fieldStats] reports — so the ⓘ coordinates
     * match the printed numbers (not the colour-bar percentile clamp).
     */
    private fun trueExtremaIndices(data: FloatArray, dataIndex: Int): Pair<Int, Int> {
        var maxIdx = -1
        var minIdx = -1
        var maxV = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                val v = data[i + dataIndex]
                if (v > maxV) {
                    maxV = v
                    maxIdx = i
                }
                if (v < minV) {
                    minV = v
                    minIdx = i
                }
            }
            i += DicResult.STRIDE
        }
        return maxIdx to minIdx
    }

    /** Push cached stats into the finding caption. Main thread only. */
    private fun applyFieldMetrics(metrics: FieldMetrics, index: Int) {
        updateCaptionsFrom(metrics, index)
    }

    private companion object {
        const val SCRUB_DEBOUNCE_MS = 70L

        /** Field metrics are tiny (5 floats + 2 ints); keep plenty across frames/fields. */
        const val FIELD_METRICS_CACHE_MAX = 64

        // Saved-state keys; their strings are what a restored viewer reads back.
        const val STATE_PROBE_INDEX = "LAST_CLOSEST_IDX"
        const val STATE_FRAME = "CURRENT_FRAME"
        const val STATE_SHOWING_SUMMARY = "SHOWING_SUMMARY"
        const val STATE_SHARE_KIND = "PENDING_SHARE_KIND"
    }
}
