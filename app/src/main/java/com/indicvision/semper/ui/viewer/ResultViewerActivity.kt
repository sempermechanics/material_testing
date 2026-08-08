// Result viewer Activity: frame scrubbing, overlays, inspect mode and export
// live on one screen. Size and branching are inherent; suppress rather than
// baseline so new findings elsewhere still fail CI.

@file:Suppress(
    "TooManyFunctions",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "LongMethod",
    "LoopWithTooManyJumpStatements",
    "MagicNumber",
)
@file:SuppressLint("SetTextI18n")

package com.indicvision.semper.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.indicvision.semper.DicKeys
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.imaging.BitmapDecode
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.ReportData
import com.indicvision.semper.report.VisualizationEngine
import com.indicvision.semper.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Results browser: renders displacement/strain heatmaps over the reference
 * image, with frame scrubbing (batch runs), point inspection, min/max
 * markers, custom color scales, and all exports (PDF/CSV/PNG/ZIP via
 * [ShareCenter]).
 */
class ResultViewerActivity : AppCompatActivity() {

    private val viewerVm: ResultViewerViewModel by viewModels()

    internal lateinit var imgMain: TouchImageView
    private lateinit var imgHeatmap: ImageView

    private lateinit var btnPrevFrame: ImageButton
    private lateinit var btnNextFrame: ImageButton
    private lateinit var tvFrameCounter: TextView

    private lateinit var layoutColorScale: LinearLayout
    private lateinit var tvScaleMax: TextView
    private lateinit var tvScaleMin: TextView

    private lateinit var btnInputCoords: Button
    private lateinit var toggleMaxMin: ToggleButton

    internal lateinit var toggleInspect: ToggleButton
    internal lateinit var cardInspectorHud: CardView
    internal lateinit var tvInspectorData: TextView
    internal lateinit var cardMaxMinHud: CardView
    internal lateinit var tvMaxMinData: TextView
    internal lateinit var glassShield: InspectOverlayView

    private lateinit var inspect: ViewerInspectHelper

    internal var rawData: FloatArray? = null
    internal var imgW = 0
    internal var imgH = 0

    /**
     * Step size of the frame on screen. A parameter sweep varies it from frame
     * to frame — rendering, point picking and the report all key off it — so it
     * is re-read whenever a frame loads rather than fixed at launch.
     */
    internal var step = 5

    /** Step size for an ordinary analysis, where every frame shares one. */
    internal var baseStep = 5

    // A sweep hands over one setting triple per frame; null for a normal run.
    internal var sweepSubsets: IntArray? = null
    internal var sweepSteps: IntArray? = null
    internal var sweepStrainWins: IntArray? = null

    /** Axis of the study's line cut through the ROI centre. */
    internal var lineCutHorizontal = true

    /** True when the frames are parameter combinations rather than images. */
    internal val isSweep: Boolean get() = sweepSteps != null

    internal var roiX = 0
    internal var roiY = 0
    internal var roiW = 0
    internal var roiH = 0

    internal var cachedBaseImage: Bitmap? = null
    private var cachedHeatmap: Bitmap? = null
    internal var currentTypeString: String
        get() = viewerVm.currentTypeString
        set(value) {
            viewerVm.currentTypeString = value
        }
    private var isGeneratingHeatmap = false

    private var batchFiles: List<File> = emptyList()
    internal var originalDefNames: List<String> = emptyList()

    private var refImagePath: String? = null
    private var defImagePaths: List<String> = emptyList()
    internal var currentFrameIndex: Int
        get() = viewerVm.currentFrameIndex
        set(value) {
            viewerVm.currentFrameIndex = value
        }
    private var loadFrameJob: Job? = null
    private var visualizationJob: Job? = null
    private var scrubDebounceJob: Job? = null
    private var refDecodeJob: Job? = null

    private val scrubCache = ScrubFrameCache()

    internal var currentDataIndex: Int
        get() = viewerVm.currentDataIndex
        set(value) {
            viewerVm.currentDataIndex = value
        }

    internal var currentDefPath: String? = null
    private var currentHeatmapMin = 0f
    private var currentHeatmapMax = 0f

    private val customBoundsMap = mutableMapOf<Int, Pair<Float, Float>>()

    private lateinit var etFrameNumber: EditText
    private lateinit var tvFrameTotal: TextView
    private lateinit var summary: ViewerSummaryHelper

    /**
     * True while the summary animation is up instead of a frame. It sits before
     * frame 1: Prev from frame 1 reaches it, Next leaves it.
     */
    private var showingSummary = false

    internal fun summaryBatchFiles(): List<File> = batchFiles

    /** How many frames this analysis actually holds. */
    internal fun frameCount(): Int = batchFiles.size

    internal fun customBoundsFor(dataIndex: Int): Pair<Float, Float>? = customBoundsMap[dataIndex]

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_viewer)

        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)

        btnPrevFrame = findViewById(R.id.btnPrevFrame)
        btnNextFrame = findViewById(R.id.btnNextFrame)
        tvFrameCounter = findViewById(R.id.tvFrameCounter)
        etFrameNumber = findViewById(R.id.etFrameNumber)
        tvFrameTotal = findViewById(R.id.tvFrameTotal)

        layoutColorScale = findViewById(R.id.layoutColorScale)
        tvScaleMax = findViewById(R.id.tvScaleMax)
        tvScaleMin = findViewById(R.id.tvScaleMin)

        Insets.padTop(findViewById(R.id.topBarHost))
        Insets.padBottom(findViewById(R.id.layoutScrubber))

        toggleInspect = findViewById(R.id.toggleInspect)
        btnInputCoords = findViewById(R.id.btnInputCoords)
        toggleMaxMin = findViewById(R.id.toggleMaxMin)

        cardInspectorHud = findViewById(R.id.cardInspectorHud)
        tvInspectorData = findViewById(R.id.tvInspectorData)
        cardMaxMinHud = findViewById(R.id.cardMaxMinHud)
        tvMaxMinData = findViewById(R.id.tvMaxMinData)
        glassShield = findViewById(R.id.glassShield)

        inspect = ViewerInspectHelper(this)

        if (savedInstanceState != null) {
            inspect.lastClosestIdx = savedInstanceState.getInt("LAST_CLOSEST_IDX", -1)
            inspect.lastMaxIdx = savedInstanceState.getInt("LAST_MAX_IDX", -1)
            inspect.lastMinIdx = savedInstanceState.getInt("LAST_MIN_IDX", -1)
            inspect.isMaxMinActive = savedInstanceState.getBoolean("MAX_MIN_ACTIVE", false)
            currentFrameIndex = savedInstanceState.getInt("CURRENT_FRAME", 0)
            showingSummary = savedInstanceState.getBoolean("SHOWING_SUMMARY", false)
        } else {
            // A lattice node tap asks to open on a specific frame; clamped once
            // the batch is loaded below.
            currentFrameIndex = intent.getIntExtra(DicKeys.START_FRAME, 0)
            // Otherwise the summary is what the viewer opens on — it answers
            // "what happened across the test" before any single frame does.
            showingSummary = !intent.hasExtra(DicKeys.START_FRAME)
        }

        imgW = intent.getIntExtra(DicKeys.IMG_W, 0)
        imgH = intent.getIntExtra(DicKeys.IMG_H, 0)
        baseStep = intent.getIntExtra(DicKeys.STEP, 5)
        step = baseStep
        sweepSubsets = intent.getIntArrayExtra(DicKeys.SWEEP_SUBSETS)
        sweepSteps = intent.getIntArrayExtra(DicKeys.SWEEP_STEPS)
        sweepStrainWins = intent.getIntArrayExtra(DicKeys.SWEEP_STRAIN_WINS)
        lineCutHorizontal = intent.getBooleanExtra(DicKeys.LINE_CUT_HORIZONTAL, true)

        roiX = intent.getIntExtra(DicKeys.ROI_X, 0)
        roiY = intent.getIntExtra(DicKeys.ROI_Y, 0)
        roiW = intent.getIntExtra(DicKeys.ROI_W, imgW)
        roiH = intent.getIntExtra(DicKeys.ROI_H, imgH)

        val refPath = intent.getStringExtra(DicKeys.REF_PATH)
        // True sensor dims stay on the intent for math / inspect / export; the
        // on-screen bitmap is decoded off-main at ImageView scale.
        imgMain.setTrueImageDimensions(imgW, imgH)
        if (refPath != null) {
            decodeReferenceForDisplay(refPath)
        }

        currentDefPath = intent.getStringExtra(DicKeys.DEF_PATH)

        val batchDirPath = intent.getStringExtra(DicKeys.BATCH_DIR_PATH)
        originalDefNames = intent.getStringArrayListExtra(DicKeys.DEF_FILE_NAMES) ?: emptyList()
        refImagePath = intent.getStringExtra(DicKeys.REF_PATH)
        // Prefer the raw deformed originals persisted in the session dir (survive
        // reopen/eviction); fall back to the just-analysed session's temp paths.
        val rawDeformedDir = batchDirPath?.let { File(it, "raw_deformed") }
        defImagePaths = rawDeformedDir?.takeIf { it.isDirectory }
            ?.listFiles()?.sortedBy { it.name }?.map { it.absolutePath }
            ?: intent.getStringArrayListExtra(DicKeys.DEF_FILE_PATHS)
            ?: emptyList()

        if (batchDirPath != null) {
            val dir = File(batchDirPath)
            if (dir.exists() && dir.isDirectory) {
                batchFiles = dir.listFiles { file -> file.extension == "dat" }?.sortedBy { it.name } ?: emptyList()
            }
        }

        summary = ViewerSummaryHelper(this)

        if (batchFiles.isNotEmpty()) {
            // A START_FRAME (or restored index) past the batch would load nothing.
            currentFrameIndex = currentFrameIndex.coerceIn(0, batchFiles.lastIndex)
            tvFrameTotal.text = getString(R.string.frame_total_fmt, batchFiles.size)
            loadFrameData(currentFrameIndex)
            summary.start()
            if (showingSummary) summary.show()
            updateNavButtons()
        } else {
            showingSummary = false
            com.google.android.material.snackbar.Snackbar.make(
                findViewById(android.R.id.content),
                R.string.no_batch_data,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
            ).show()
        }

        btnPrevFrame.setOnClickListener {
            when {
                showingSummary -> Unit
                currentFrameIndex == 0 -> enterSummary()
                else -> {
                    currentFrameIndex--
                    updateNavButtons()
                    requestFrameLoad(debounced = true)
                }
            }
        }

        btnNextFrame.setOnClickListener {
            if (showingSummary) {
                leaveSummary()
            } else if (currentFrameIndex < batchFiles.size - 1) {
                currentFrameIndex++
                updateNavButtons()
                requestFrameLoad(debounced = true)
            }
        }

        wireFrameJump()

        imgMain.onMatrixChangedListener = {
            applyHeatmapMatrix()
            inspect.refreshCrosshairs()
            updateStickyScaleBar()
        }

        // Five equal-width field buttons spanning the screen (wireframe 08)
        val fieldByButton = mapOf(
            R.id.rbFieldU to ("U" to DicResult.IDX_U),
            R.id.rbFieldV to ("V" to DicResult.IDX_V),
            R.id.rbFieldExx to ("Exx" to DicResult.IDX_EXX),
            R.id.rbFieldEyy to ("Eyy" to DicResult.IDX_EYY),
            R.id.rbFieldExy to ("Exy" to DicResult.IDX_EXY),
        )
        findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.fieldToggle)
            .addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val (label, index) = fieldByButton[checkedId] ?: return@addOnButtonCheckedListener
                currentTypeString = label
                currentDataIndex = index
                updateVisualization(currentDataIndex)
                summary.onFieldChanged()
                if (showingSummary) tvFrameCounter.text = summary.counterText()
                updateStatsStrip()
                if (inspect.isMaxMinActive) inspect.calculateMaxMin()
                inspect.refreshCrosshairs()
            }

        findViewById<View>(R.id.btnViewerBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnViewerHome).setOnClickListener {
            val home = Intent(this, com.indicvision.semper.ui.home.HomeActivity::class.java)
            home.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(home)
            finish()
        }
        findViewById<View>(R.id.btnViewerShare).setOnClickListener { showShareSheet() }
        findViewById<View>(R.id.btnViewerSettingsInfo).setOnClickListener {
            ViewerSettingsSheet.show(this)
        }

        layoutColorScale.setOnClickListener { showCustomScaleDialog() }

        toggleInspect.setOnCheckedChangeListener { _, isChecked ->
            inspect.isInspectModeActive = isChecked
            inspect.manageGlassShieldState()
            inspect.refreshCrosshairs()
        }

        toggleMaxMin.isChecked = inspect.isMaxMinActive
        toggleMaxMin.setOnCheckedChangeListener { _, isChecked ->
            inspect.isMaxMinActive = isChecked
            if (isChecked) inspect.calculateMaxMin()
            inspect.manageGlassShieldState()
            inspect.refreshCrosshairs()
        }

        btnInputCoords.setOnClickListener { inspect.showCoordinateInputDialog() }
        inspect.wireGlassShieldTouch()

        imgMain.post {
            inspect.refreshCrosshairs()
            updateStickyScaleBar()
            if (inspect.isMaxMinActive && inspect.lastMaxIdx == -1) {
                inspect.calculateMaxMin()
                inspect.refreshCrosshairs()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep the Home row's headline in sync with what was on screen.
        // Sweeps already carry a stable caption (image + solved count) — don't
        // overwrite it with the last field's peak reading.
        if (isSweep) return
        intent.getStringExtra(DicKeys.SESSION_LOCAL_ID)?.let { localId ->
            val data = rawData ?: return@let
            val stats = DicResult.fieldStats(data, currentDataIndex) ?: return@let
            val unit = if (DicResult.isStrainFieldIndex(currentDataIndex)) "m\u03b5" else "px"
            val headline = "$currentTypeString max ${ReportBuilder.formatMetric(stats[0])} $unit"
            lifecycleScope.launch(Dispatchers.IO) {
                com.indicvision.semper.data.SessionStore.updateHeadline(
                    this@ResultViewerActivity,
                    localId,
                    headline,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loadFrameJob?.cancel()
        visualizationJob?.cancel()
        scrubDebounceJob?.cancel()
        refDecodeJob?.cancel()
        summary.cancel()
        scrubCache.clear(except = cachedHeatmap)
        inspect.clearSpatialIndex()
    }

    /**
     * Decode the reference off the main thread with [BitmapFactory.Options.inSampleSize]
     * sized to the ImageView (capped by [VisualizationEngine.DISPLAY_MAX_EDGE]).
     */
    private fun decodeReferenceForDisplay(refPath: String) {
        imgMain.post {
            val viewW = imgMain.width.coerceAtLeast(1)
            val viewH = imgMain.height.coerceAtLeast(1)
            val reqW = viewW.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            val reqH = viewH.coerceAtMost(VisualizationEngine.DISPLAY_MAX_EDGE)
            refDecodeJob?.cancel()
            refDecodeJob = lifecycleScope.launch(Dispatchers.IO) {
                val bmp = BitmapDecode.decodeFileForView(refPath, reqW, reqH)
                withContext(Dispatchers.Main) {
                    if (isDestroyed || isFinishing) {
                        bmp?.recycle()
                        return@withContext
                    }
                    cachedBaseImage = bmp
                    imgMain.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Debounce rapid Next/Prev so only the settled frame is decoded. */
    private fun requestFrameLoad(debounced: Boolean) {
        scrubDebounceJob?.cancel()
        if (!debounced) {
            loadFrameData(currentFrameIndex)
            return
        }
        scrubDebounceJob = lifecycleScope.launch {
            delay(SCRUB_DEBOUNCE_MS)
            loadFrameData(currentFrameIndex)
        }
    }

    private fun applyHeatmapMatrix() {
        val hm = cachedHeatmap
        val zoom = imgMain.getZoomMatrix()
        if (hm == null || hm.isRecycled || hm.width <= 0 || imgW <= 0) {
            imgHeatmap.imageMatrix = zoom
        } else {
            val m = Matrix(zoom)
            m.preScale(imgW.toFloat() / hm.width, imgH.toFloat() / hm.height)
            imgHeatmap.imageMatrix = m
        }
        imgHeatmap.invalidate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("LAST_CLOSEST_IDX", inspect.lastClosestIdx)
        outState.putInt("LAST_MAX_IDX", inspect.lastMaxIdx)
        outState.putInt("LAST_MIN_IDX", inspect.lastMinIdx)
        outState.putBoolean("MAX_MIN_ACTIVE", inspect.isMaxMinActive)
        outState.putInt("CURRENT_FRAME", currentFrameIndex)
        outState.putBoolean("SHOWING_SUMMARY", showingSummary)
    }

    private fun loadFrameData(index: Int) {
        if (index < 0 || index >= batchFiles.size) return

        scrubCache.getData(index)?.let { cached ->
            applyLoadedFrame(index, cached)
            prefetchNeighborFrames(index)
            return
        }

        loadFrameJob?.cancel()
        loadFrameJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val data = readFrameDat(index) ?: return@launch
                scrubCache.putData(index, data)

                withContext(Dispatchers.Main) {
                    if (currentFrameIndex != index) return@withContext
                    applyLoadedFrame(index, data)
                }
                prefetchNeighborFrames(index)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // never swallow coroutine cancellation
            } catch (e: OutOfMemoryError) {
                // Error, not Exception — must be caught explicitly or the process dies.
                Timber.e(e, "OOM loading frame $index")
                scrubCache.clear()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ResultViewerActivity, R.string.viewer_frame_oom, Toast.LENGTH_LONG).show()
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.e(e, "Failed to load frame $index")
            }
        }
    }

    /** Warm N±1 into [scrubCache] without touching the UI. */
    private fun prefetchNeighborFrames(center: Int) {
        // Prefetch doubles peak RAM (current + neighbor). Skip when the heap is
        // already tight — heavy PLC frames are several MB of floats each.
        if (!heapHasRoomForPrefetch()) return
        for (delta in intArrayOf(-1, 1)) {
            val neighbor = center + delta
            if (neighbor < 0 || neighbor >= batchFiles.size) continue
            if (scrubCache.getData(neighbor) != null) continue
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (!heapHasRoomForPrefetch()) return@launch
                    val data = readFrameDat(neighbor) ?: return@launch
                    scrubCache.putData(neighbor, data)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // never swallow coroutine cancellation
                } catch (e: OutOfMemoryError) {
                    Timber.w(e, "Prefetch frame %d OOM — clearing scrub cache", neighbor)
                    scrubCache.clear()
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Timber.w(e, "Prefetch frame %d failed", neighbor)
                }
            }
        }
    }

    private fun readFrameDat(index: Int): FloatArray? {
        val file = batchFiles[index]
        val data = DicResult.decodeDatFile(file)
        if (data == null) {
            Timber.e("Invalid file size for frame $index")
        }
        return data
    }

    /** Rough guard: need headroom for another full-frame FloatArray (~file size). */
    private fun heapHasRoomForPrefetch(): Boolean {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val largest = batchFiles.maxOfOrNull { it.length() } ?: return false
        return free > largest * 3
    }

    private fun applyLoadedFrame(index: Int, data: FloatArray) {
        rawData = data
        // A sweep's frames each have their own grid pitch.
        step = sweepSteps?.getOrNull(index) ?: baseStep
        inspect.rebuildSpatialIndex(data, step)
        val displayName = originalDefNames.getOrNull(index) ?: "Frame ${index + 1}"
        if (!showingSummary) {
            tvFrameCounter.text = "$displayName (${index + 1} / ${batchFiles.size})"
        }
        syncFrameNumber()
        updateVisualization(currentDataIndex)
        updateStatsStrip()
        if (inspect.isMaxMinActive) inspect.calculateMaxMin()
        if (inspect.isInspectModeActive && inspect.lastClosestIdx != -1) {
            inspect.refreshCrosshairs()
        }
    }

    private fun updateStickyScaleBar() {
        val pts = floatArrayOf(0f, 0f)
        imgMain.getZoomMatrix().mapPoints(pts)
        val imageTopY = pts[1]

        val barHeight = layoutColorScale.height.toFloat()
        if (barHeight > 0) {
            val targetY = imageTopY - barHeight - 16f
            layoutColorScale.translationY = maxOf(0f, targetY)
        }
    }

    private fun showCustomScaleDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_custom_scale, null)
        val etMax = dialogView.findViewById<TextInputEditText>(R.id.etScaleMax)
        val etMin = dialogView.findViewById<TextInputEditText>(R.id.etScaleMin)

        val isStrain = DicResult.isStrainFieldIndex(currentDataIndex)
        val multiplier = DicResult.strainMultiplier(currentDataIndex)
        val unit = getString(if (isStrain) R.string.scale_unit_strain else R.string.scale_unit_px)

        dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilScaleMax).hint =
            getString(R.string.scale_max_value, unit)
        dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilScaleMin).hint =
            getString(R.string.scale_min_value, unit)

        val existing = customBoundsMap[currentDataIndex]
        if (existing != null) {
            etMin.setText((existing.first * multiplier).toString())
            etMax.setText((existing.second * multiplier).toString())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.scale_dialog_title, currentTypeString))
            .setView(dialogView)
            .setPositiveButton(R.string.apply) { _, _ ->
                val maxVal = etMax.text?.toString()?.toFloatOrNull()
                val minVal = etMin.text?.toString()?.toFloatOrNull()

                if (maxVal != null && minVal != null && maxVal > minVal) {
                    customBoundsMap[currentDataIndex] = Pair(minVal / multiplier, maxVal / multiplier)
                    updateVisualization(currentDataIndex)
                    summary.onScaleChanged(currentDataIndex)
                } else {
                    Toast.makeText(this, R.string.invalid_scale_inputs, Toast.LENGTH_LONG).show()
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

        val forceMin = customBoundsMap[index]?.first
        val forceMax = customBoundsMap[index]?.second
        val heatKey = ScrubFrameCache.HeatKey(
            frame = currentFrameIndex,
            field = index,
            step = step,
            customMin = forceMin,
            customMax = forceMax,
        )

        scrubCache.getHeat(heatKey)?.let { hit ->
            visualizationJob?.cancel()
            showHeatmap(hit.bitmap, hit.minV, hit.maxV, index)
            return
        }

        visualizationJob?.cancel()
        val frameAtStart = currentFrameIndex
        visualizationJob = lifecycleScope.launch(Dispatchers.Default) {
            val result = VisualizationEngine.generateHeatmap(
                data,
                imgW,
                imgH,
                index,
                step,
                forceMin,
                forceMax,
                maxLongEdge = VisualizationEngine.DISPLAY_MAX_EDGE,
            )

            val heatmap = result.first
            val actualMin = result.second
            val actualMax = result.third
            scrubCache.putHeat(
                heatKey,
                ScrubFrameCache.HeatEntry(heatmap, actualMin, actualMax),
            )

            withContext(Dispatchers.Main) {
                if (currentDataIndex != index || currentFrameIndex != frameAtStart) return@withContext
                showHeatmap(heatmap, actualMin, actualMax, index)
            }
        }
    }

    private fun showHeatmap(heatmap: Bitmap, actualMin: Float, actualMax: Float, index: Int) {
        cachedHeatmap = heatmap
        imgHeatmap.setImageBitmap(heatmap)
        applyHeatmapMatrix()

        currentHeatmapMin = actualMin
        currentHeatmapMax = actualMax

        // While the summary is up the labels belong to its whole-sequence scale,
        // not to whichever frame happens to be loaded behind it.
        if (!showingSummary) {
            val isStrain = DicResult.isStrainFieldIndex(index)
            val multiplier = DicResult.strainMultiplier(index)
            val unit = if (isStrain) " [mε]" else " px"
            tvScaleMax.text = "Max: ${ReportBuilder.formatMetric(actualMax * multiplier)}$unit"
            tvScaleMin.text = "Min: ${ReportBuilder.formatMetric(actualMin * multiplier)}$unit"
        }
        isGeneratingHeatmap = false
    }

    private fun buildReportData(): ReportData? =
        rawData?.let { ViewerReportFactory.buildReportData(this, currentFrameIndex, it) }

    private fun buildReportData(frameIndex: Int, data: FloatArray): ReportData? =
        ViewerReportFactory.buildReportData(this, frameIndex, data)

    /** Everything ShareCenter needs, captured from the viewer's state. */
    /**
     * A filename-safe base for exports, drawn from the specimen/reference name so
     * shared files read like "IMG_0768_report.pdf" instead of a generic prefix.
     * Falls back to the session name, then the first deformed frame, then "analysis".
     */
    private fun shareBaseName(): String {
        val record = intent.getStringExtra(DicKeys.SESSION_LOCAL_ID)
            ?.let { runCatching { com.indicvision.semper.data.SessionStore.get(this, it) }.getOrNull() }
        val raw = record?.refName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: record?.name?.takeIf { it.isNotBlank() }
            ?: originalDefNames.firstOrNull()?.substringBeforeLast('.')
            ?: "analysis"
        return raw.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60).ifBlank { "analysis" }
    }

    internal fun buildShareSnapshot(): ShareCenter.Snapshot? {
        val data = rawData ?: return null
        // Snapshot can open with ref path alone while display decode is still in flight.
        val base = cachedBaseImage
        return ShareCenter.Snapshot(
            data = data,
            batchFiles = batchFiles,
            defNames = originalDefNames,
            baseName = shareBaseName(),
            frameIndex = currentFrameIndex,
            imgW = imgW,
            imgH = imgH,
            step = step,
            stepPerFrame = sweepSteps,
            subsetPerFrame = sweepSubsets,
            strainWindowPerFrame = sweepStrainWins,
            dataIndex = currentDataIndex,
            typeString = currentTypeString,
            baseImage = base,
            refImagePath = refImagePath,
            defImagePaths = defImagePaths,
            summary = summary.animation,
            summaryBounds = { index -> summary.boundsFor(index) },
            buildReportAt = { index, frameData -> buildReportData(index, frameData) },
        )
    }

    /** Share sheet (wireframe 08) - targets wired via ShareCenter. */
    private fun showShareSheet() {
        ShareCenter(this).show()
    }

    /** Permanent max/min/mean tiles for the current field + frame. */
    private fun updateStatsStrip() {
        val data = rawData ?: return
        val stats = DicResult.fieldStats(data, currentDataIndex)
        if (stats == null) {
            findViewById<TextView>(R.id.tvStatMax).text = getString(R.string.stat_empty)
            findViewById<TextView>(R.id.tvStatMin).text = getString(R.string.stat_empty)
            findViewById<TextView>(R.id.tvStatMean).text = getString(R.string.stat_empty)
            return
        }
        val unit = if (DicResult.isStrainFieldIndex(currentDataIndex)) " m\u03b5" else " px"
        findViewById<TextView>(R.id.tvStatMax).text = ReportBuilder.formatMetric(stats[0]) + unit
        findViewById<TextView>(R.id.tvStatMin).text = ReportBuilder.formatMetric(stats[1]) + unit
        findViewById<TextView>(R.id.tvStatMean).text = ReportBuilder.formatMetric(stats[2]) + unit
    }

    private fun updateNavButtons() {
        btnPrevFrame.isEnabled = !showingSummary && batchFiles.isNotEmpty()
        btnNextFrame.isEnabled = showingSummary || currentFrameIndex < batchFiles.size - 1

        btnPrevFrame.alpha = if (btnPrevFrame.isEnabled) 1.0f else 0.5f
        btnNextFrame.alpha = if (btnNextFrame.isEnabled) 1.0f else 0.5f
        // The number tracks the buttons, not the decode: a debounced scrub would
        // otherwise leave it a frame behind for as long as the load takes.
        syncFrameNumber()
    }

    // ── Summary slot ─────────────────────────────────────────────────────

    private fun enterSummary() {
        showingSummary = true
        summary.show()
        tvFrameCounter.text = summary.counterText()
        updateNavButtons()
    }

    private fun leaveSummary() {
        showingSummary = false
        summary.hide()
        updateNavButtons()
        // Re-apply the frame's own labels and heatmap after the summary's.
        requestFrameLoad(debounced = false)
    }

    // ── Typed frame jump ─────────────────────────────────────────────────

    private fun wireFrameJump() {
        etFrameNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                commitFrameJump()
                true
            } else {
                false
            }
        }
        etFrameNumber.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitFrameJump()
        }
    }

    /**
     * Applies what is typed in the frame field. Anything unparseable or outside
     * the batch restores the current number rather than jumping somewhere the
     * user did not ask for.
     */
    private fun commitFrameJump() {
        val typed = etFrameNumber.text?.toString()?.trim()?.toIntOrNull()
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
        dismissFrameJumpKeyboard()
    }

    private fun dismissFrameJumpKeyboard() {
        etFrameNumber.clearFocus()
        val ime = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        ime?.hideSoftInputFromWindow(etFrameNumber.windowToken, 0)
    }

    private fun syncFrameNumber() {
        val shown = if (showingSummary) "" else (currentFrameIndex + 1).toString()
        if (etFrameNumber.text?.toString() != shown) etFrameNumber.setText(shown)
    }

    private companion object {
        const val SCRUB_DEBOUNCE_MS = 70L
    }
}
