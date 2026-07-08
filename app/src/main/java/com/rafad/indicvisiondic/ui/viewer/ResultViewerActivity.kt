package com.rafad.indicvisiondic.ui.viewer
import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.*
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.report.EngineStats
import com.rafad.indicvisiondic.report.PdfReportGenerator
import com.rafad.indicvisiondic.report.ReportBuilder
import com.rafad.indicvisiondic.report.ReportData
import com.rafad.indicvisiondic.report.RoiData
import com.rafad.indicvisiondic.report.VisualizationEngine
import com.rafad.indicvisiondic.ui.Insets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Results browser: renders displacement/strain heatmaps over the reference
 * image, with frame scrubbing (batch runs), point inspection, min/max
 * markers, custom color scales, and all exports (PDF/CSV/PNG/ZIP via
 * [ResultExporter]).
 */
class ResultViewerActivity : AppCompatActivity() {

    private lateinit var imgMain: TouchImageView
    private lateinit var imgHeatmap: ImageView
    private lateinit var spinnerType: Spinner

    // UI - Batch Controls
    private lateinit var btnPrevFrame: ImageButton
    private lateinit var btnNextFrame: ImageButton
    private lateinit var tvFrameCounter: TextView

    // UI - Scale Bar
    private lateinit var layoutColorScale: LinearLayout
    private lateinit var tvScaleMax: TextView
    private lateinit var tvScaleMin: TextView

    private lateinit var spinnerExportType: Spinner
    private lateinit var btnExportExecute: ImageButton
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
    private var step = 5

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
    private var pdfProgressDialog: androidx.appcompat.app.AlertDialog? = null
    private val exportActions = mutableListOf<() -> Unit>()
    private val exporter by lazy { ResultExporter(applicationContext, lifecycleScope) }

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
        }

        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)
        spinnerType = findViewById(R.id.spinnerResultType)

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

        spinnerExportType = findViewById(R.id.spinnerExportType)
        btnExportExecute = findViewById(R.id.btnExportExecute)

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
        step = intent.getIntExtra(DicKeys.STEP, 5)

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

        if (batchDirPath != null) {
            val dir = File(batchDirPath)
            if (dir.exists() && dir.isDirectory) {
                batchFiles = dir.listFiles { file -> file.extension == "dat" }?.sortedBy { it.name } ?: emptyList()
            }
        }

        if (batchFiles.isNotEmpty()) {
            loadFrameData(currentFrameIndex)
            updateNavButtons()
        } else {
            Toast.makeText(this, R.string.no_batch_data, Toast.LENGTH_LONG).show()
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

        val options = arrayOf("U", "V", "Exx", "Eyy", "Exy")
        // White text for both the selected value and the (dark) dropdown popup
        spinnerType.adapter = ArrayAdapter(this, R.layout.spinner_item_white, options).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_white)
        }
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                currentTypeString = options[position]
                currentDataIndex = position + 2
                updateVisualization(currentDataIndex)

                if (isMaxMinActive) calculateMaxMin()
                refreshCrosshairs()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        layoutColorScale.setOnClickListener { showCustomScaleDialog() }

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

        val exportOptions = mutableListOf<String>()
        exportActions.clear()
        val exportLabels = resources.getStringArray(R.array.export_options)
        exportOptions.add(exportLabels[0])
        exportActions.add { exportMergedImage() }
        exportOptions.add(exportLabels[1])
        exportActions.add { generatePdfReport() }
        exportOptions.add(exportLabels[2])
        exportActions.add { exportToCSV() }

        if (batchFiles.size > 1) {
            exportOptions.add(exportLabels[3])
            exportActions.add { exportAllImagesZip() }
            exportOptions.add(exportLabels[4])
            exportActions.add { exportAllDataCsv() }
        }

        val adapter = ArrayAdapter(this, R.layout.spinner_item_white, exportOptions)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_white)
        spinnerExportType.adapter = adapter

        btnExportExecute.setOnClickListener {
            exportActions.getOrNull(spinnerExportType.selectedItemPosition)?.invoke()
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

    override fun onDestroy() {
        super.onDestroy()
        loadFrameJob?.cancel()
        visualizationJob?.cancel()
        pdfProgressDialog?.dismiss()
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
                    Log.e("ResultViewer", "Invalid file size for frame $index")
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    rawData = data
                    val displayName = originalDefNames.getOrNull(index) ?: "Frame ${index + 1}"
                    tvFrameCounter.text = "$displayName (${index + 1} / ${batchFiles.size})"
                    updateVisualization(currentDataIndex)
                    if (isMaxMinActive) calculateMaxMin()
                    if (isInspectModeActive && lastClosestIdx != -1) refreshCrosshairs()
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e("ResultViewer", "Failed to load frame $index", e)
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

    private fun exportToCSV() {
        val data = rawData
        if (data == null) {
            Toast.makeText(this, R.string.no_data_to_save, Toast.LENGTH_SHORT).show()
            return
        }
        exporter.exportCsv(data, originalDefNames, currentFrameIndex)
    }

    private fun generatePdfReport() {
        if (isGeneratingHeatmap || cachedBaseImage == null) {
            Toast.makeText(this, R.string.wait_for_heatmap, Toast.LENGTH_SHORT).show()
            return
        }

        val progressView = layoutInflater.inflate(R.layout.dialog_pdf_progress, null)
        val tvMessage = progressView.findViewById<TextView>(R.id.tvPdfProgressMessage)
        val progressIndicator = progressView.findViewById<LinearProgressIndicator>(R.id.pdfProgressIndicator)

        pdfProgressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pdf_generating_title)
            .setView(progressView)
            .setCancelable(false)
            .create()
        pdfProgressDialog?.show()

        val imgName = originalDefNames.getOrNull(currentFrameIndex)?.substringBeforeLast(".") ?: "Frame_${currentFrameIndex + 1}"
        // Timestamp keeps exports from different sessions of the same frame
        // from colliding in Documents/IndicVision ("report (1).pdf", …).
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "inDIC_MasterReport_${imgName}_$timestamp.pdf"

        lifecycleScope.launch {
            val reportData = withContext(Dispatchers.Default) {
                buildReportData()
            }

            if (reportData == null) {
                pdfProgressDialog?.dismiss()
                Toast.makeText(this@ResultViewerActivity, R.string.pdf_parse_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val (uri, outputStream) = withContext(Dispatchers.IO) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/IndicVision")
                }
                val resolver = applicationContext.contentResolver
                val destUri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                val stream = destUri?.let { resolver.openOutputStream(it) }
                Pair(destUri, stream)
            }

            if (uri == null || outputStream == null) return@launch

            PdfReportGenerator.generate(reportData, outputStream).collect { progress ->
                when (progress) {
                    is PdfReportGenerator.Progress.Status -> {
                        runOnUiThread {
                            tvMessage.text = progress.message
                            progressIndicator.progress = progress.percent
                        }
                    }
                    is PdfReportGenerator.Progress.Complete -> {
                        withContext(Dispatchers.IO) { outputStream.close() }
                        pdfProgressDialog?.dismiss()
                        Toast.makeText(this@ResultViewerActivity, R.string.pdf_saved, Toast.LENGTH_LONG).show()

                        reportData.fieldResults.forEach { it.bakedHeatmap.recycle() }
                    }
                    is PdfReportGenerator.Progress.Error -> {
                        withContext(Dispatchers.IO) { outputStream.close() }
                        pdfProgressDialog?.dismiss()
                        Toast.makeText(this@ResultViewerActivity, R.string.pdf_error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun buildReportData(): ReportData? {
        val baseImg = cachedBaseImage ?: return null
        val data = rawData ?: return null

        val statsArray = intent.getFloatArrayExtra(DicKeys.ENGINE_STATS) ?: FloatArray(16)
        val engineStats = if (statsArray.size >= 16) {
            EngineStats.fromArray(statsArray)
        } else {
            EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }

        val realDefImg = currentDefPath?.let { BitmapFactory.decodeFile(it) } ?: baseImg

        return ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = baseImg,
                defImgForCover = realDefImg,
                imgW = imgW,
                imgH = imgH,
                step = step,
                sessionId = intent.getStringExtra(DicKeys.SESSION_ID) ?: "Local_Offline_Mode",
                specimenName = intent.getStringExtra(DicKeys.REF_NAME)?.substringBeforeLast(".") ?: "Batch Analysis",
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = intent.getIntExtra(DicKeys.SUBSET_SIZE, 41),
                strainWindow = intent.getIntExtra(DicKeys.STRAIN_WINDOW, 15),
                strainMethod = intent.getStringExtra(DicKeys.STRAIN_METHOD) ?: "VSG",
                roiData = RoiData(roiX, roiY, roiW, roiH),
                engineStats = engineStats,
                referenceImageName = intent.getStringExtra(DicKeys.REF_NAME) ?: "reference.png",
                deformedImageName = originalDefNames.getOrNull(currentFrameIndex) ?: "Frame_${currentFrameIndex + 1}",
            ),
        )
    }

    private fun updateNavButtons() {
        btnPrevFrame.isEnabled = currentFrameIndex > 0
        btnNextFrame.isEnabled = currentFrameIndex < batchFiles.size - 1

        btnPrevFrame.alpha = if (btnPrevFrame.isEnabled) 1.0f else 0.5f
        btnNextFrame.alpha = if (btnNextFrame.isEnabled) 1.0f else 0.5f
    }

    private fun exportAllImagesZip() {
        val base = cachedBaseImage
        if (batchFiles.isEmpty() || base == null) {
            Toast.makeText(this, R.string.no_data_to_save, Toast.LENGTH_SHORT).show()
            return
        }
        exporter.exportAllImagesZip(
            batchSnapshot(),
            base,
            imgW,
            imgH,
            currentDataIndex,
            step,
            currentTypeString,
        )
    }

    private fun exportAllDataCsv() {
        if (batchFiles.isEmpty()) {
            Toast.makeText(this, R.string.no_data_to_save, Toast.LENGTH_SHORT).show()
            return
        }
        exporter.exportAllDataCsv(batchSnapshot())
    }

    private fun exportMergedImage() {
        if (isGeneratingHeatmap) {
            Toast.makeText(this, R.string.heatmap_drawing_wait, Toast.LENGTH_SHORT).show()
            return
        }

        val base = cachedBaseImage
        val overlay = cachedHeatmap
        if (base == null || overlay == null) {
            Toast.makeText(this, R.string.export_images_missing, Toast.LENGTH_SHORT).show()
            return
        }

        val (maxIdx, minIdx) = computeMaxMinIndices()
        exporter.exportMergedImage(
            base, overlay, rawData ?: FloatArray(0),
            imgW, imgH, currentDataIndex, currentTypeString,
            currentHeatmapMin, currentHeatmapMax, maxIdx, minIdx,
            originalDefNames, currentFrameIndex,
        )
    }

    private fun batchSnapshot() = ResultExporter.BatchSnapshot(
        batchFiles = batchFiles,
        originalDefNames = originalDefNames,
        refName = intent.getStringExtra(DicKeys.REF_NAME)?.substringBeforeLast("."),
    )
}
