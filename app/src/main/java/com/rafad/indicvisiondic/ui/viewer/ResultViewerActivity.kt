package com.rafad.indicvisiondic.ui.viewer
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.report.EngineStats
import com.rafad.indicvisiondic.report.ReportBuilder
import com.rafad.indicvisiondic.report.ReportData
import com.rafad.indicvisiondic.report.RoiData
import com.rafad.indicvisiondic.report.VisualizationEngine
import com.rafad.indicvisiondic.ui.analysis.VsgPlotView
import com.rafad.indicvisiondic.ui.analysis.VsgStudy
import com.rafad.indicvisiondic.ui.common.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private lateinit var imgMain: TouchImageView
    private lateinit var imgHeatmap: ImageView

    // UI - Batch Controls
    private lateinit var btnPrevFrame: ImageButton
    private lateinit var btnNextFrame: ImageButton
    private lateinit var tvFrameCounter: TextView

    // UI - Scale Bar
    private lateinit var layoutColorScale: LinearLayout
    private lateinit var tvScaleMax: TextView
    private lateinit var tvScaleMin: TextView

    private lateinit var btnInputCoords: Button
    private lateinit var toggleMaxMin: ToggleButton

    // UI - Inspector Components
    private lateinit var toggleInspect: ToggleButton
    private lateinit var cardInspectorHud: androidx.cardview.widget.CardView
    private lateinit var tvInspectorData: TextView
    private lateinit var cardMaxMinHud: androidx.cardview.widget.CardView
    private lateinit var tvMaxMinData: TextView
    private lateinit var glassShield: InspectOverlayView

    private var rawData: FloatArray? = null
    private var imgW = 0
    private var imgH = 0

    /**
     * Step size of the frame on screen. A parameter sweep varies it from frame
     * to frame — rendering, point picking and the report all key off it — so it
     * is re-read whenever a frame loads rather than fixed at launch.
     */
    private var step = 5

    /** Step size for an ordinary analysis, where every frame shares one. */
    private var baseStep = 5

    // A sweep hands over one setting triple per frame; null for a normal run.
    private var sweepSubsets: IntArray? = null
    private var sweepSteps: IntArray? = null
    private var sweepStrainWins: IntArray? = null

    /** Axis of the study's line cut through the ROI centre. */
    private var lineCutHorizontal = true

    /** True when the frames are parameter combinations rather than images. */
    private val isSweep: Boolean get() = sweepSteps != null

    // ROI Tracking Variables for the PDF
    private var roiX = 0
    private var roiY = 0
    private var roiW = 0
    private var roiH = 0

    private var cachedBaseImage: Bitmap? = null
    private var cachedHeatmap: Bitmap? = null
    private var currentTypeString: String = "U"
    private var isGeneratingHeatmap = false

    // Batch Data State
    private var batchFiles: List<File> = emptyList()
    private var originalDefNames: List<String> = emptyList()

    // Raw image sources for the export bundle (best-effort; may be absent on reopen)
    private var refImagePath: String? = null
    private var defImagePaths: List<String> = emptyList()
    private var currentFrameIndex = 0
    private var loadFrameJob: Job? = null
    private var visualizationJob: Job? = null

    // States
    private var currentDataIndex = 2
    private var isInspectModeActive = false
    private var isMaxMinActive = false

    private var lastClosestIdx = -1
    private var lastMaxIdx = -1
    private var lastMinIdx = -1
    private var currentDefPath: String? = null
    private var currentHeatmapMin = 0f
    private var currentHeatmapMax = 0f

    private val customBoundsMap = mutableMapOf<Int, Pair<Float, Float>>()

    // Storage Access Framework: "Save to device" writes a generated artifact to a
    // user-chosen location. The picker is async, so the pending file is held here.
    private var pendingSaveFile: File? = null
    private val saveDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uri = result.data?.data
            val src = pendingSaveFile
            pendingSaveFile = null
            if (result.resultCode != RESULT_OK || uri == null || src == null) return@registerForActivityResult
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        } != null
                    } catch (e: Exception) {
                        Timber.e(e, "Save to device failed")
                        false
                    }
                }
                Toast.makeText(
                    this@ResultViewerActivity,
                    if (ok) R.string.save_success else R.string.save_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    /** Launches the system "create document" picker to save [file] to the device. */
    internal fun saveFileToDevice(file: File, mime: String) {
        pendingSaveFile = file
        saveDocumentLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = mime
                putExtra(Intent.EXTRA_TITLE, file.name)
            },
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_viewer)

        if (savedInstanceState != null) {
            lastClosestIdx = savedInstanceState.getInt("LAST_CLOSEST_IDX", -1)
            lastMaxIdx = savedInstanceState.getInt("LAST_MAX_IDX", -1)
            lastMinIdx = savedInstanceState.getInt("LAST_MIN_IDX", -1)
            isMaxMinActive = savedInstanceState.getBoolean("MAX_MIN_ACTIVE", false)
            currentFrameIndex = savedInstanceState.getInt("CURRENT_FRAME", 0)
        } else {
            // A lattice node tap asks to open on a specific frame; clamped once
            // the batch is loaded below.
            currentFrameIndex = intent.getIntExtra(DicKeys.START_FRAME, 0)
        }

        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)

        btnPrevFrame = findViewById(R.id.btnPrevFrame)
        btnNextFrame = findViewById(R.id.btnNextFrame)
        tvFrameCounter = findViewById(R.id.tvFrameCounter)

        layoutColorScale = findViewById(R.id.layoutColorScale)
        tvScaleMax = findViewById(R.id.tvScaleMax)
        tvScaleMin = findViewById(R.id.tvScaleMin)

        // Edge-to-edge: keep the top control bar out from under the status bar
        // and lift the frame scrubber above the nav-bar gesture area.
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

        // Load the TRUE Reference Image for the background!
        val refPath = intent.getStringExtra(DicKeys.REF_PATH)
        if (refPath != null) {
            cachedBaseImage = BitmapFactory.decodeFile(refPath)
            imgMain.setImageBitmap(cachedBaseImage)
            imgMain.setTrueImageDimensions(imgW, imgH)
        }

        // Save the DefPath strictly for the PDF Generator!
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

        if (batchFiles.isNotEmpty()) {
            // A START_FRAME (or restored index) past the batch would load nothing.
            currentFrameIndex = currentFrameIndex.coerceIn(0, batchFiles.lastIndex)
            loadFrameData(currentFrameIndex)
            updateNavButtons()
        } else {
            com.google.android.material.snackbar.Snackbar.make(findViewById(android.R.id.content), R.string.no_batch_data, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
        }

        btnPrevFrame.setOnClickListener {
            if (currentFrameIndex > 0) {
                currentFrameIndex--
                loadFrameData(currentFrameIndex)
                updateNavButtons()
            }
        }

        btnNextFrame.setOnClickListener {
            if (currentFrameIndex < batchFiles.size - 1) {
                currentFrameIndex++
                loadFrameData(currentFrameIndex)
                updateNavButtons()
            }
        }

        imgMain.onMatrixChangedListener = {
            imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
            imgHeatmap.invalidate()
            refreshCrosshairs()
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
                updateStatsStrip()
                if (isMaxMinActive) calculateMaxMin()
                refreshCrosshairs()
            }

        findViewById<View>(R.id.btnViewerBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnViewerHome).setOnClickListener {
            val home = android.content.Intent(this, com.rafad.indicvisiondic.ui.home.HomeActivity::class.java)
            home.flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            startActivity(home)
            finish()
        }
        findViewById<View>(R.id.btnViewerShare).setOnClickListener { showShareSheet() }
        findViewById<View>(R.id.btnViewerSettingsInfo).setOnClickListener { showSettingsUsedSheet() }

        layoutColorScale.setOnClickListener { showCustomScaleDialog() }

        // (settings-used sheet is built in showSettingsUsedSheet)

        toggleInspect.setOnCheckedChangeListener { _, isChecked ->
            isInspectModeActive = isChecked
            manageGlassShieldState()
            refreshCrosshairs()
        }

        toggleMaxMin.isChecked = isMaxMinActive
        toggleMaxMin.setOnCheckedChangeListener { _, isChecked ->
            isMaxMinActive = isChecked
            if (isChecked) calculateMaxMin()
            manageGlassShieldState()
            refreshCrosshairs()
        }

        btnInputCoords.setOnClickListener { showCoordinateInputDialog() }

        glassShield.setOnTouchListener { _, event ->
            if (!isInspectModeActive) {
                imgMain.dispatchTouchEvent(event)
                return@setOnTouchListener true
            }

            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val pts = floatArrayOf(event.x, event.y)
                val inverse = android.graphics.Matrix()
                imgMain.imageMatrix.invert(inverse)
                inverse.mapPoints(pts)
                findNearestDataPoint(pts[0], pts[1])
            }
            true
        }

        imgMain.post {
            refreshCrosshairs()
            updateStickyScaleBar()
            if (isMaxMinActive && lastMaxIdx == -1) {
                calculateMaxMin()
                refreshCrosshairs()
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
            com.rafad.indicvisiondic.data.SessionStore.updateHeadline(
                this,
                localId,
                "$currentTypeString max ${ReportBuilder.formatMetric(stats[0])} $unit",
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loadFrameJob?.cancel()
        visualizationJob?.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("LAST_CLOSEST_IDX", lastClosestIdx)
        outState.putInt("LAST_MAX_IDX", lastMaxIdx)
        outState.putInt("LAST_MIN_IDX", lastMinIdx)
        outState.putBoolean("MAX_MIN_ACTIVE", isMaxMinActive)
        outState.putInt("CURRENT_FRAME", currentFrameIndex)
    }

    private fun computeMaxMinIndices(): Pair<Int, Int> {
        val data = rawData ?: return -1 to -1
        val extrema = ReportBuilder.computeFieldExtrema(data, currentDataIndex, absoluteStrainValues = false)
        return extrema.maxIdx to extrema.minIdx
    }

    private fun loadFrameData(index: Int) {
        if (index < 0 || index >= batchFiles.size) return

        loadFrameJob?.cancel()
        loadFrameJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = batchFiles[index]
                val data = DicResult.decodeDatBytes(file.readBytes())
                if (data == null) {
                    Timber.e("Invalid file size for frame $index")
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    rawData = data
                    // A sweep's frames each have their own grid pitch.
                    step = sweepSteps?.getOrNull(index) ?: baseStep
                    val displayName = originalDefNames.getOrNull(index) ?: "Frame ${index + 1}"
                    tvFrameCounter.text = "$displayName (${index + 1} / ${batchFiles.size})"
                    updateVisualization(currentDataIndex)
                    updateStatsStrip()
                    if (isMaxMinActive) calculateMaxMin()
                    if (isInspectModeActive && lastClosestIdx != -1) refreshCrosshairs()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Timber.e(e, "Failed to load frame $index")
                }
            }
        }
    }

    private fun updateStickyScaleBar() {
        val pts = floatArrayOf(0f, 0f)
        imgMain.imageMatrix.mapPoints(pts)
        val imageTopY = pts[1]

        val barHeight = layoutColorScale.height.toFloat()
        if (barHeight > 0) {
            val targetY = imageTopY - barHeight - 16f
            layoutColorScale.translationY = maxOf(0f, targetY)
        }
    }

    private fun manageGlassShieldState() {
        if (isInspectModeActive || isMaxMinActive) {
            glassShield.visibility = View.VISIBLE
        } else {
            glassShield.visibility = View.GONE
        }
    }

    private fun calculateMaxMin() {
        val (maxIdx, minIdx) = computeMaxMinIndices()
        lastMaxIdx = maxIdx
        lastMinIdx = minIdx
    }

    private fun findNearestDataPoint(physX: Float, physY: Float) {
        val data = rawData ?: return
        var closestIdx = -1
        var minDistSq = Float.MAX_VALUE

        val searchRadius = step * 1.5f
        val searchRadiusSq = searchRadius * searchRadius

        for (i in data.indices step DicResult.STRIDE) {
            val dx = data[i] - physX
            val dy = data[i + 1] - physY
            val distSq = dx * dx + dy * dy

            if (distSq < minDistSq && distSq <= searchRadiusSq) {
                val corr = data[i + DicResult.IDX_ZNSSD]
                if (DicResult.isAcceptedPoint(corr)) {
                    minDistSq = distSq
                    closestIdx = i
                }
            }
        }

        lastClosestIdx = closestIdx
        refreshCrosshairs()
    }

    private fun refreshCrosshairs() {
        val data = rawData ?: return

        val isStrain = DicResult.isStrainFieldIndex(currentDataIndex)
        val multiplier = DicResult.strainMultiplier(currentDataIndex)
        val unit = if (isStrain) "mε" else "px"

        if (isInspectModeActive) {
            cardInspectorHud.visibility = View.VISIBLE

            if (lastClosestIdx != -1 && lastClosestIdx < data.size) {
                val actualX = data[lastClosestIdx].toInt()
                val actualY = data[lastClosestIdx + 1].toInt()
                val value = data[lastClosestIdx + currentDataIndex] * multiplier

                tvInspectorData.text = "Loc: ($actualX, $actualY)\n$currentTypeString: ${ReportBuilder.formatMetric(value)} $unit"

                val pts = floatArrayOf(actualX.toFloat(), actualY.toFloat())
                imgMain.imageMatrix.mapPoints(pts)
                glassShield.updatePosition(pts[0], pts[1])
            } else {
                glassShield.hide()
                tvInspectorData.text = "Out of bounds / No Data"
            }
        } else {
            glassShield.hide()
            cardInspectorHud.visibility = View.GONE
        }

        if (isMaxMinActive && lastMaxIdx != -1 && lastMinIdx != -1) {
            val maxX = data[lastMaxIdx].toInt()
            val maxY = data[lastMaxIdx + 1].toInt()
            val minX = data[lastMinIdx].toInt()
            val minY = data[lastMinIdx + 1].toInt()

            val maxV = data[lastMaxIdx + currentDataIndex] * multiplier
            val minV = data[lastMinIdx + currentDataIndex] * multiplier

            val ptsMax = floatArrayOf(maxX.toFloat(), maxY.toFloat())
            val ptsMin = floatArrayOf(minX.toFloat(), minY.toFloat())
            imgMain.imageMatrix.mapPoints(ptsMax)
            imgMain.imageMatrix.mapPoints(ptsMin)

            glassShield.updateMaxMinPositions(ptsMax[0], ptsMax[1], ptsMin[0], ptsMin[1])

            tvMaxMinData.text = "🔴 MAX: ($maxX, $maxY) = ${ReportBuilder.formatMetric(maxV)} $unit\n🔵 MIN: ($minX, $minY) = ${ReportBuilder.formatMetric(minV)} $unit"
            cardMaxMinHud.visibility = View.VISIBLE
        } else {
            glassShield.hideMaxMin()
            cardMaxMinHud.visibility = View.GONE
        }
    }

    private fun showCoordinateInputDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_coordinate_input, null)
        val etX = dialogView.findViewById<TextInputEditText>(R.id.etCoordX)
        val etY = dialogView.findViewById<TextInputEditText>(R.id.etCoordY)
        dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCoordX).hint =
            getString(R.string.coord_hint_x, imgW)
        dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilCoordY).hint =
            getString(R.string.coord_hint_y, imgH)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.coord_dialog_title)
            .setView(dialogView)
            .setPositiveButton(R.string.find) { _, _ ->
                val x = etX.text?.toString()?.toFloatOrNull()
                val y = etY.text?.toString()?.toFloatOrNull()

                if (x != null && y != null) {
                    if (x < 0 || x > imgW || y < 0 || y > imgH) {
                        Toast.makeText(this, R.string.coord_out_of_bounds, Toast.LENGTH_LONG).show()
                    } else {
                        if (!isInspectModeActive) toggleInspect.isChecked = true
                        findNearestDataPoint(x, y)
                    }
                } else {
                    Toast.makeText(this, R.string.invalid_input, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                } else {
                    Toast.makeText(this, R.string.invalid_scale_inputs, Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(R.string.auto_scale) { _, _ ->
                customBoundsMap.remove(currentDataIndex)
                updateVisualization(currentDataIndex)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateVisualization(index: Int) {
        val data = rawData ?: return
        isGeneratingHeatmap = true

        val forceMin = customBoundsMap[index]?.first
        val forceMax = customBoundsMap[index]?.second

        visualizationJob?.cancel()
        visualizationJob = lifecycleScope.launch(Dispatchers.Default) {
            val result = VisualizationEngine.generateHeatmap(
                data,
                imgW,
                imgH,
                index,
                step,
                forceMin,
                forceMax,
            )

            val heatmap = result.first
            val actualMin = result.second
            val actualMax = result.third

            val isStrain = DicResult.isStrainFieldIndex(index)
            val multiplier = DicResult.strainMultiplier(index)
            val unit = if (isStrain) " [mε]" else " px"

            withContext(Dispatchers.Main) {
                cachedHeatmap = heatmap
                imgHeatmap.setImageBitmap(heatmap)
                imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
                imgHeatmap.invalidate()

                currentHeatmapMin = actualMin
                currentHeatmapMax = actualMax

                tvScaleMax.text = "Max: ${ReportBuilder.formatMetric(actualMax * multiplier)}$unit"
                tvScaleMin.text = "Min: ${ReportBuilder.formatMetric(actualMin * multiplier)}$unit"
                isGeneratingHeatmap = false
            }
        }
    }

    private fun buildReportData(): ReportData? =
        rawData?.let { buildReportData(currentFrameIndex, it) }

    /**
     * Report data for [frameIndex] built from its own [data]. Everything that
     * varies frame to frame is read by index — the step size a sweep changes
     * per combination, the settings its cover quotes, and the deformed image
     * name — so an all-frames report describes each frame correctly instead of
     * repeating the one on screen.
     */
    private fun buildReportData(frameIndex: Int, data: FloatArray): ReportData? {
        val baseImg = cachedBaseImage ?: return null

        val frameStep = sweepSteps?.getOrNull(frameIndex) ?: baseStep
        val frameSubset = sweepSubsets?.getOrNull(frameIndex) ?: intent.getIntExtra(DicKeys.SUBSET_SIZE, 41)
        val frameStrainWin = sweepStrainWins?.getOrNull(frameIndex)
            ?: intent.getIntExtra(DicKeys.STRAIN_WINDOW, 15)

        val statsArray = intent.getFloatArrayExtra(DicKeys.ENGINE_STATS) ?: FloatArray(16)
        val engineStats = if (statsArray.size >= 16) {
            EngineStats.fromArray(statsArray)
        } else {
            EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        val realDefImg = currentDefPath?.let { BitmapFactory.decodeFile(it) } ?: baseImg

        // buildReport keeps only a downscaled copy of the cover images, so the
        // full-size decode above is ours to free — and an all-frames report
        // calls this once per frame.
        return buildReportWith(
            data,
            baseImg,
            realDefImg,
            frameIndex,
            frameStep,
            frameSubset,
            frameStrainWin,
            engineStats,
        ).also { if (realDefImg !== baseImg) realDefImg.recycle() }
    }

    @Suppress("LongParameterList") // one call site; all of it is per-frame state
    private fun buildReportWith(
        data: FloatArray,
        baseImg: Bitmap,
        realDefImg: Bitmap,
        frameIndex: Int,
        frameStep: Int,
        frameSubset: Int,
        frameStrainWin: Int,
        engineStats: EngineStats,
    ): ReportData {
        return ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = baseImg,
                defImgForCover = realDefImg,
                imgW = imgW,
                imgH = imgH,
                step = frameStep,
                sessionId = intent.getStringExtra(DicKeys.SESSION_ID) ?: "Local_Offline_Mode",
                specimenName = intent.getStringExtra(DicKeys.REF_NAME)?.substringBeforeLast(".") ?: "Batch Analysis",
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = frameSubset,
                strainWindow = frameStrainWin,
                strainMethod = intent.getStringExtra(DicKeys.STRAIN_METHOD) ?: "VSG",
                roiData = RoiData(roiX, roiY, roiW, roiH),
                engineStats = engineStats,
                referenceImageName = intent.getStringExtra(DicKeys.REF_NAME) ?: "reference.png",
                deformedImageName = originalDefNames.getOrNull(frameIndex) ?: "Frame_${frameIndex + 1}",
            ),
        )
    }

    /** Everything ShareCenter needs, captured from the viewer's state. */
    internal fun buildShareSnapshot(): ShareCenter.Snapshot? {
        val data = rawData ?: return null
        val base = cachedBaseImage ?: return null
        return ShareCenter.Snapshot(
            data = data,
            batchFiles = batchFiles,
            defNames = originalDefNames,
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
            buildReportAt = { index, frameData -> buildReportData(index, frameData) },
        )
    }

    /** Share sheet (wireframe 08) - targets wired via ShareCenter. */
    private fun showShareSheet() {
        ShareCenter(this).show()
    }

    /**
     * The engine settings this result was produced with.
     *
     * Read from the intent extras the viewer was launched with — not from
     * [com.rafad.indicvisiondic.data.DicSettings] — so a result opened later
     * still shows the values it was actually computed with, even if the user has
     * changed their settings since.
     */
    private fun showSettingsUsedSheet() {
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_settings_used, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.tvSettingsUsedSpecimen).text =
            intent.getStringExtra(DicKeys.REF_NAME)?.removePrefix("Ref: ").orEmpty()

        val rows = view.findViewById<LinearLayout>(R.id.settingsUsedRows)
        val roiW = intent.getIntExtra(DicKeys.ROI_W, 0)
        val roiH = intent.getIntExtra(DicKeys.ROI_H, 0)

        // A sweep varies the settings frame by frame, so the sheet must describe
        // the combination on screen rather than the one the run started with.
        val frame = currentFrameIndex
        val subset = sweepSubsets?.getOrNull(frame) ?: intent.getIntExtra(DicKeys.SUBSET_SIZE, 0)
        val strainWin = sweepStrainWins?.getOrNull(frame) ?: intent.getIntExtra(DicKeys.STRAIN_WINDOW, 0)

        val entries = buildList {
            add(getString(R.string.setting_subset) to getString(R.string.setting_px_fmt, subset))
            add(getString(R.string.setting_step) to getString(R.string.setting_px_fmt, step))
            add(
                getString(R.string.setting_strain_window) to
                    getString(R.string.setting_subsets_fmt, strainWin),
            )
            if (isSweep) {
                add(
                    getString(R.string.setting_vsg) to getString(
                        R.string.setting_px_fmt,
                        VsgStudy.vsgFor(step, strainWin),
                    ),
                )
            }
            add(
                getString(R.string.setting_strain_method) to
                    (intent.getStringExtra(DicKeys.STRAIN_METHOD) ?: "VSG"),
            )
            // ROI is only meaningful when one was actually recorded.
            if (roiW > 0 && roiH > 0) {
                add(
                    getString(R.string.setting_roi) to getString(
                        R.string.setting_roi_fmt,
                        roiW,
                        roiH,
                        intent.getIntExtra(DicKeys.ROI_X, 0),
                        intent.getIntExtra(DicKeys.ROI_Y, 0),
                    ),
                )
            }
            add(
                getString(R.string.setting_image_size) to getString(
                    R.string.setting_size_fmt,
                    intent.getIntExtra(DicKeys.IMG_W, 0),
                    intent.getIntExtra(DicKeys.IMG_H, 0),
                ),
            )
        }

        entries.forEachIndexed { index, (label, value) ->
            if (index > 0) rows.addView(settingsDivider())
            rows.addView(settingsRow(label, value))
        }
        if (isSweep) populateLineCut(view)
        sheet.show()
    }

    /**
     * Strain along the cut through the centre of the ROI, all three components
     * at once (guide step 4). The cut is the same physical line for every
     * combination of the sweep, so scrubbing frames compares like with like.
     */
    private fun populateLineCut(sheetView: View) {
        val data = rawData ?: return
        val section = sheetView.findViewById<View>(R.id.lineCutSection)
        val plot = sheetView.findViewById<VsgPlotView>(R.id.plotLineCut)
        val line = VsgStudy.centreLine(roiX, roiY, roiW, roiH, lineCutHorizontal)
        val tolerance = step / 2f

        val labels = listOf(R.string.field_exx, R.string.field_eyy, R.string.field_exy)
        val series = VsgStudy.STRAIN_COMPONENTS.mapIndexed { slot, component ->
            VsgPlotView.Series(
                label = getString(labels[slot]),
                color = plot.paletteColor(slot),
                points = VsgStudy.profileAlong(data, component, line, tolerance),
                markers = false,
            )
        }
        if (series.all { it.points.isEmpty() }) {
            section.visibility = View.GONE
            return
        }

        section.visibility = View.VISIBLE
        sheetView.findViewById<TextView>(R.id.tvLineCutTitle).setText(R.string.line_cut_title)
        sheetView.findViewById<TextView>(R.id.tvLineCutLegend).text = lineCutLegend(
            getString(if (lineCutHorizontal) R.string.axis_x else R.string.axis_y),
            line.position,
            plot,
        )
        plot.setData(
            series,
            getString(if (lineCutHorizontal) R.string.line_cut_axis_x else R.string.line_cut_axis_y),
            getString(R.string.line_cut_axis_strain),
        )
    }

    /** Prefix plus colour-matched Exx / Eyy / Exy labels for the line-cut plot. */
    private fun lineCutLegend(axis: String, position: Float, plot: VsgPlotView): CharSequence {
        val prefix = getString(R.string.line_cut_legend_prefix_fmt, axis, position)
        val parts = listOf(
            getString(R.string.line_cut_legend_exx) to plot.paletteColor(0),
            getString(R.string.line_cut_legend_eyy) to plot.paletteColor(1),
            getString(R.string.line_cut_legend_exy) to plot.paletteColor(2),
        )
        val spanned = android.text.SpannableStringBuilder(prefix).append(' ')
        parts.forEachIndexed { index, (label, color) ->
            if (index > 0) spanned.append(" · ")
            val start = spanned.length
            spanned.append(label)
            spanned.setSpan(
                android.text.style.ForegroundColorSpan(color),
                start,
                spanned.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return spanned
    }

    private fun settingsRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(
            TextView(this).apply {
                text = label
                setTextColor(getColor(R.color.text_secondary))
                textSize = SETTINGS_ROW_SP
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(this).apply {
                text = value
                setTextColor(getColor(R.color.text_primary))
                textSize = SETTINGS_ROW_SP
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            },
        )
        return row
    }

    private fun settingsDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            1,
        ).apply {
            topMargin = SETTINGS_DIVIDER_MARGIN
            bottomMargin = SETTINGS_DIVIDER_MARGIN
        }
        setBackgroundColor(getColor(R.color.surface_outline))
    }

    private companion object {
        const val SETTINGS_ROW_SP = 13f
        const val SETTINGS_DIVIDER_MARGIN = 10
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
        btnPrevFrame.isEnabled = currentFrameIndex > 0
        btnNextFrame.isEnabled = currentFrameIndex < batchFiles.size - 1

        btnPrevFrame.alpha = if (btnPrevFrame.isEnabled) 1.0f else 0.5f
        btnNextFrame.alpha = if (btnNextFrame.isEnabled) 1.0f else 0.5f
    }
}
