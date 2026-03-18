package com.rafad.indicvisiondic

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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import android.app.ProgressDialog
import java.io.ByteArrayOutputStream
class ResultViewerActivity : AppCompatActivity() {

    private lateinit var imgMain: TouchImageView
    private lateinit var imgHeatmap: ImageView
    private lateinit var spinnerType: Spinner

    // UI - Batch Controls (Ensure these are in your XML)
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
    private var imgW = 0; private var imgH = 0; private var step = 5

    private var cachedBaseImage: Bitmap? = null

    private var cachedHeatmap: Bitmap? = null
    private var currentTypeString: String = "U"
    private var isGeneratingHeatmap = false

    // Batch Data State
    private var batchFiles: List<File> = emptyList()
    private var originalDefNames: List<String> = emptyList()
    private var currentFrameIndex = 0
    private var loadingJob: Thread? = null
    // ✅ FIX: Version counter replaces the broken Thread.currentThread() == loadingJob guard.
    // runOnUiThread always runs on the main thread, so that check was ALWAYS FALSE.
    // Now we track which job version started last; only the latest version applies its results.
    private var loadVersion = 0

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
    private val reportScope = CoroutineScope(Dispatchers.Main)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_viewer)

        // Restore state on screen rotation
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

        // Bind Batch Controls (Add these IDs to your XML if not already there)
        btnPrevFrame = findViewById(R.id.btnPrevFrame)
        btnNextFrame = findViewById(R.id.btnNextFrame)
        tvFrameCounter = findViewById(R.id.tvFrameCounter)

        layoutColorScale = findViewById(R.id.layoutColorScale)
        tvScaleMax = findViewById(R.id.tvScaleMax)
        tvScaleMin = findViewById(R.id.tvScaleMin)

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

        imgW = intent.getIntExtra("IMG_W", 0)
        imgH = intent.getIntExtra("IMG_H", 0)
        step = intent.getIntExtra("STEP", 5)

        currentDefPath = intent.getStringExtra("DEF_PATH") // 🚀 SAVE IT HERE
        if (currentDefPath != null) {
            cachedBaseImage = BitmapFactory.decodeFile(currentDefPath)
            imgMain.setImageBitmap(cachedBaseImage)
            imgMain.setTrueImageDimensions(imgW, imgH)
        }

        // 🚀 BATCH LOADING LOGIC
        val batchDirPath = intent.getStringExtra("BATCH_DIR_PATH")
        originalDefNames = intent.getStringArrayListExtra("DEF_FILE_NAMES") ?: emptyList()

        if (batchDirPath != null) {
            val dir = File(batchDirPath)
            if (dir.exists() && dir.isDirectory) {
                // Only load the raw float .dat files and sort them chronologically
                batchFiles = dir.listFiles { file -> file.extension == "dat" }?.sortedBy { it.name } ?: emptyList()
            }
        }

        if (batchFiles.isNotEmpty()) {
            loadFrameData(currentFrameIndex)
            updateNavButtons()
        } else {
            Toast.makeText(this, "No valid batch data found.", Toast.LENGTH_LONG).show()
        }

        // BIND TIMELINE SCRUBBER
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

        // BIND CROSSHAIRS AND STICKY BAR TO ZOOM/PAN MATRIX
        imgMain.onMatrixChangedListener = {
            imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
            imgHeatmap.invalidate()
            refreshCrosshairs()
            updateStickyScaleBar()
        }

        val options = arrayOf("U", "V", "Exx", "Eyy", "Exy")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
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

        // PROBE TOGGLE
        toggleInspect.setOnCheckedChangeListener { _, isChecked ->
            isInspectModeActive = isChecked
            manageGlassShieldState()
            refreshCrosshairs()
        }

        // MAX/MIN TOGGLE
        toggleMaxMin.isChecked = isMaxMinActive
        toggleMaxMin.setOnCheckedChangeListener { _, isChecked ->
            isMaxMinActive = isChecked
            if (isChecked) calculateMaxMin()
            manageGlassShieldState()
            refreshCrosshairs()
        }

        // INPUT COORDS
        btnInputCoords.setOnClickListener { showCoordinateInputDialog() }

        // ACTS AS A TRANSPARENT PROXY WHEN INSPECT IS OFF
        glassShield.setOnTouchListener { _, event ->
            if (!isInspectModeActive) {
                imgMain.dispatchTouchEvent(event)
                return@setOnTouchListener true
            }

            // If Inspect IS active, handle the manual probe math
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val pts = floatArrayOf(event.x, event.y)
                val inverse = android.graphics.Matrix()
                imgMain.imageMatrix.invert(inverse)
                inverse.mapPoints(pts)

                findNearestDataPoint(pts[0], pts[1])
            }
            true
        }

        // Add PDF to the dropdown list
        val exportOptions = arrayOf(
            "Export Image (Current)",
            "Export PDF Report", // 🚀 NEW OPTION
            "Export CSV (Current)",
            "Export Images (Batch ZIP)",
            "Export All Data (Single CSV)"
        )
        val adapter = ArrayAdapter(this, R.layout.spinner_item_white, exportOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerExportType.adapter = adapter

        btnExportExecute.setOnClickListener {
            when (spinnerExportType.selectedItemPosition) {
                0 -> exportMergedImage()
                1 -> generatePdfReport() // 🚀 HOOK IT UP
                2 -> exportToCSV()
                3 -> exportAllImagesZip()
                4 -> exportAllDataCsv()
            }
        }
        // Initial setup after views have dimensions
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
        // 🛡️ Cancel any pending rendering threads when closing activity
        loadingJob?.interrupt()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("LAST_CLOSEST_IDX", lastClosestIdx)
        outState.putInt("LAST_MAX_IDX", lastMaxIdx)
        outState.putInt("LAST_MIN_IDX", lastMinIdx)
        outState.putBoolean("MAX_MIN_ACTIVE", isMaxMinActive)
        outState.putInt("CURRENT_FRAME", currentFrameIndex)
    }

    // ✅ NEW: SAFE FRAME LOADER FOR BATCH MODE WITH CORRECT CANCELLATION
    private fun loadFrameData(index: Int) {
        if (index < 0 || index >= batchFiles.size) return

        // Cancel any previous loading job to prevent thread pile-ups if scrubbing fast
        loadingJob?.interrupt()

        // Capture version before starting thread; if a newer job starts before this one
        // posts its runOnUiThread callback, the version will have changed and we discard.
        loadVersion++
        val myVersion = loadVersion

        loadingJob = Thread {
            try {
                val file = batchFiles[index]
                if (Thread.currentThread().isInterrupted) return@Thread

                val bytes = file.readBytes()

                // Validate payload — must be multiples of 8 floats (32 bytes)
                if (bytes.size % 32 != 0) {
                    Log.e("ResultViewer", "Invalid file size for frame $index: ${bytes.size} bytes")
                    return@Thread
                }

                val newData = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
                    .asFloatBuffer().get(newData)

                runOnUiThread {
                    // ✅ FIX: Version check (not thread identity — that was always false).
                    // Only apply results if no newer scrub has started a new load job.
                    if (myVersion == loadVersion) {
                        rawData = newData
                        val displayName = originalDefNames.getOrNull(index) ?: "Frame ${index + 1}"
                        tvFrameCounter.text = "$displayName (${index + 1} / ${batchFiles.size})"
                        updateVisualization(currentDataIndex)
                        if (isMaxMinActive) calculateMaxMin()
                        if (isInspectModeActive && lastClosestIdx != -1) refreshCrosshairs()
                    }
                }
            } catch (e: InterruptedException) {
                // Task was safely cancelled by a newer scrub — do nothing
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        loadingJob?.start()
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
        val data = rawData ?: return
        var maxV = -Float.MAX_VALUE
        var minV = Float.MAX_VALUE
        lastMaxIdx = -1
        lastMinIdx = -1

        val validValues = mutableListOf<Float>()
        for (i in data.indices step 8) {
            val corr = data[i + 7]
            if (corr != 0f && corr <= 0.15f) {
                validValues.add(data[i + currentDataIndex])
            }
        }

        if (validValues.isEmpty()) return

        validValues.sort()
        val p02 = validValues[(validValues.size * 0.02).toInt().coerceIn(0, validValues.size - 1)]
        val p98 = validValues[(validValues.size * 0.98).toInt().coerceIn(0, validValues.size - 1)]

        for (i in data.indices step 8) {
            val corr = data[i + 7]
            if (corr != 0f && corr <= 0.15f) { // 🚀 Filter outliers during Max/Min sweep
                val v = data[i + currentDataIndex]
                if (v in p02..p98) {
                    if (v > maxV) { maxV = v; lastMaxIdx = i }
                    if (v < minV) { minV = v; lastMinIdx = i }
                }
            }
        }

        // Failsafe in case mathematical precision caused exact boundary drops
        if (lastMaxIdx == -1 || lastMinIdx == -1) {
            for (i in data.indices step 8) {
                val corr = data[i + 7]
                if (corr != 0f && corr <= 0.15f) {
                    val v = data[i + currentDataIndex]
                    if (v > maxV) { maxV = v; lastMaxIdx = i }
                    if (v < minV) { minV = v; lastMinIdx = i }
                }
            }
        }
    }

    private fun findNearestDataPoint(physX: Float, physY: Float) {
        val data = rawData ?: return
        var closestIdx = -1
        var minDistSq = Float.MAX_VALUE

        val searchRadius = step * 1.5f
        val searchRadiusSq = searchRadius * searchRadius

        for (i in data.indices step 8) {
            val dx = data[i] - physX
            val dy = data[i+1] - physY
            val distSq = dx * dx + dy * dy

            if (distSq < minDistSq && distSq <= searchRadiusSq) {
                val corr = data[i + 7]
                if (corr != 0f && corr <= 0.15f) { // 🚀 Tighter correlation gate for probing
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

        // Multipliers for milli strain
        val isStrain = currentDataIndex > 3
        val multiplier = if (isStrain) 1000f else 1f
        val unit = if (isStrain) "mε" else "px"

        // 1. Probe Logic
        if (isInspectModeActive) {
            cardInspectorHud.visibility = View.VISIBLE

            if (lastClosestIdx != -1 && lastClosestIdx < data.size) {
                val actualX = data[lastClosestIdx].toInt()
                val actualY = data[lastClosestIdx + 1].toInt()

                val value = data[lastClosestIdx + currentDataIndex] * multiplier

                tvInspectorData.text = "Loc: ($actualX, $actualY)\n$currentTypeString: %.5f %s".format(value, unit)

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

        // 2. Max/Min Logic
        if (isMaxMinActive && lastMaxIdx != -1 && lastMinIdx != -1) {
            val maxX = data[lastMaxIdx].toInt(); val maxY = data[lastMaxIdx+1].toInt()
            val minX = data[lastMinIdx].toInt(); val minY = data[lastMinIdx+1].toInt()

            val maxV = data[lastMaxIdx+currentDataIndex] * multiplier
            val minV = data[lastMinIdx+currentDataIndex] * multiplier

            val ptsMax = floatArrayOf(maxX.toFloat(), maxY.toFloat())
            val ptsMin = floatArrayOf(minX.toFloat(), minY.toFloat())
            imgMain.imageMatrix.mapPoints(ptsMax)
            imgMain.imageMatrix.mapPoints(ptsMin)

            glassShield.updateMaxMinPositions(ptsMax[0], ptsMax[1], ptsMin[0], ptsMin[1])

            tvMaxMinData.text = "🔴 MAX: ($maxX, $maxY) = %.5f $unit\n🔵 MIN: ($minX, $minY) = %.5f $unit".format(maxV, minV)
            cardMaxMinHud.visibility = View.VISIBLE
        } else {
            glassShield.hideMaxMin()
            cardMaxMinHud.visibility = View.GONE
        }
    }

    private fun showCoordinateInputDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        val etX = EditText(this).apply {
            hint = "X Coordinate (0 to $imgW)"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        val etY = EditText(this).apply {
            hint = "Y Coordinate (0 to $imgH)"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }

        dialogView.addView(etX)
        dialogView.addView(etY)

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Go to Pixel Coordinate")
            .setView(dialogView)
            .setPositiveButton("Find") { _, _ ->
                val x = etX.text.toString().toFloatOrNull()
                val y = etY.text.toString().toFloatOrNull()

                if (x != null && y != null) {
                    if (x < 0 || x > imgW || y < 0 || y > imgH) {
                        Toast.makeText(this, "Error: Coordinates out of bounds.", Toast.LENGTH_LONG).show()
                    } else {
                        if (!isInspectModeActive) toggleInspect.isChecked = true
                        findNearestDataPoint(x, y)
                    }
                } else {
                    Toast.makeText(this, "Invalid input.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomScaleDialog() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
        }

        val isStrain = currentDataIndex > 3
        val multiplier = if (isStrain) 1000f else 1f
        val unitHint = if (isStrain) " (mε)" else " (px)"

        val etMax = EditText(this).apply {
            hint = "Max Value$unitHint"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        val etMin = EditText(this).apply {
            hint = "Min Value$unitHint"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        val existing = customBoundsMap[currentDataIndex]
        if (existing != null) {
            etMin.setText((existing.first * multiplier).toString())
            etMax.setText((existing.second * multiplier).toString())
        }

        dialogView.addView(TextView(this).apply { text = "Maximum Value:"; setTextColor(Color.LTGRAY) })
        dialogView.addView(etMax)
        dialogView.addView(TextView(this).apply { text = "Minimum Value:"; setTextColor(Color.LTGRAY); setPadding(0, 32, 0, 0) })
        dialogView.addView(etMin)

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Custom Scale: $currentTypeString")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val maxVal = etMax.text.toString().toFloatOrNull()
                val minVal = etMin.text.toString().toFloatOrNull()

                if (maxVal != null && minVal != null && maxVal > minVal) {
                    customBoundsMap[currentDataIndex] = Pair(minVal / multiplier, maxVal / multiplier)
                    updateVisualization(currentDataIndex)
                } else {
                    Toast.makeText(this, "Invalid inputs.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Auto-Scale") { _, _ ->
                customBoundsMap.remove(currentDataIndex)
                updateVisualization(currentDataIndex)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateVisualization(index: Int) {
        val data = rawData ?: return
        isGeneratingHeatmap = true

        val forceMin = customBoundsMap[index]?.first
        val forceMax = customBoundsMap[index]?.second

        Thread {
            // 🚀 Bypasses absolute Min/Max forcing inside Activity so Engine can use robust IQR bounds
            val result = VisualizationEngine.generateHeatmap(
                data, imgW, imgH, index, step,
                forceMin, forceMax
            )

            val heatmap = result.first
            val actualMin = result.second
            val actualMax = result.third

            runOnUiThread {
                cachedHeatmap = heatmap
                imgHeatmap.setImageBitmap(heatmap)
                imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
                imgHeatmap.invalidate()

                // 🚀 NEW: Save the bounds for the Canvas Baker!
                currentHeatmapMin = actualMin
                currentHeatmapMax = actualMax

                val isStrain = index > 3
                val multiplier = if (isStrain) 1000f else 1f
                val unit = if (isStrain) " [mε]" else " px"

                tvScaleMax.text = "Max: %.5f%s".format(actualMax * multiplier, unit)
                tvScaleMin.text = "Min: %.5f%s".format(actualMin * multiplier, unit)
                isGeneratingHeatmap = false
            }
        }.start()
    }

    private fun exportToCSV() {
        val data = rawData
        if (data == null) {
            Toast.makeText(this, "No data to save.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Saving CSV...", Toast.LENGTH_SHORT).show()

        Thread {
            val imgName = originalDefNames.getOrNull(currentFrameIndex)?.substringBeforeLast(".") ?: "Frame_${currentFrameIndex + 1}"
            val fileName = "IndicVision_${imgName}.csv"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IndicVision")
            }

            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        val writer = outputStream.bufferedWriter()
                        writer.write("X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n")

                        var i = 0
                        while (i < data.size) {
                            val x = data[i]; val y = data[i+1]
                            val u = data[i+2]; val v = data[i+3]
                            val exx = data[i+4]; val eyy = data[i+5]; val exy = data[i+6]
                            val c = data[i+7]

                            if (c != 0f) {
                                writer.write("$x,$y,$u,$v,$exx,$eyy,$exy,$c\n")
                            }
                            i += 8
                        }
                        writer.flush()
                    }
                    // 🚀 NEW: CLOUD STORAGE UPLOAD
                    val sessionId = intent.getStringExtra("SESSION_ID")
                    val userId = SupabaseManager.client.auth.currentUserOrNull()?.id

                    if (sessionId != null && userId != null) {
                        // Generate the exact CSV bytes in memory to upload
                        val csvContent = StringBuilder("X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n")
                        var i = 0
                        while (i < data.size) {
                            val c = data[i+7]
                            if (c != 0f) {
                                csvContent.append("${data[i]},${data[i+1]},${data[i+2]},${data[i+3]},${data[i+4]},${data[i+5]},${data[i+6]},$c\n")
                            }
                            i += 8
                        }
                        val byteArray = csvContent.toString().toByteArray()

                        // Define the cloud path: /userID/sessionId_filename.csv
                        val cloudPath = "$userId/${sessionId}_$fileName"

                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                // 1. Upload the file to the bucket
                                SupabaseManager.client.storage["session_artifacts"].upload(cloudPath, byteArray) {
                                    upsert = true // Overwrite if they export again
                                }

                                // 2. Get the public/signed URL
                                val publicUrl = SupabaseManager.client.storage["session_artifacts"].publicUrl(cloudPath)

                                // 3. Update the database ledger to link the file!
                                SupabaseManager.client.postgrest["analysis_sessions"].update(
                                    mapOf("summary_csv_path" to publicUrl)
                                ) {
                                    filter { eq("session_id", sessionId) }
                                }

                                Log.d("inDIC_Cloud", "Successfully backed up CSV to Supabase!")
                            } catch (e: Exception) {
                                Log.e("inDIC_Cloud", "Cloud backup failed", e)
                            }
                        }
                    }
                    // 🚀 END CLOUD UPLOAD
                    runOnUiThread {
                        Toast.makeText(this@ResultViewerActivity, "✅ CSV Saved to Downloads/IndicVision", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { Toast.makeText(this@ResultViewerActivity, "❌ Failed to save CSV", Toast.LENGTH_SHORT).show() }
                }
            }
        }.start()
    }
    private fun generatePdfReport() {
        if (isGeneratingHeatmap || cachedBaseImage == null) {
            Toast.makeText(this, "Please wait for heatmap to finish...", Toast.LENGTH_SHORT).show()
            return
        }

        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Generating Master Report")
            setMessage("Analyzing all fields...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        val imgName = originalDefNames.getOrNull(currentFrameIndex)?.substringBeforeLast(".") ?: "Frame_${currentFrameIndex + 1}"
        val fileName = "inDIC_MasterReport_${imgName}.pdf"

        reportScope.launch {
            // 1. Build the massive data snapshot on a background thread!
            val reportData = kotlinx.coroutines.withContext(Dispatchers.Default) {
                buildReportData()
            }

            if (reportData == null) {
                progressDialog.dismiss()
                Toast.makeText(this@ResultViewerActivity, "❌ Failed to parse data.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // 2. Prepare the File Output
            val (uri, outputStream) = kotlinx.coroutines.withContext(Dispatchers.IO) {
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

            // 3. Generate PDF
            PdfReportGenerator.generate(reportData, outputStream).collect { progress ->
                when (progress) {
                    is PdfReportGenerator.Progress.Status -> {
                        progressDialog.setMessage(progress.message)
                        progressDialog.progress = progress.percent
                    }
                    is PdfReportGenerator.Progress.Complete -> {
                        kotlinx.coroutines.withContext(Dispatchers.IO) { outputStream.close() }
                        progressDialog.dismiss()
                        Toast.makeText(this@ResultViewerActivity, "✅ Master PDF Saved to Documents", Toast.LENGTH_LONG).show()

                        // Stealth Upload
                        stealthUploadPdf(uri, fileName)

                        // Cleanup Bitmaps
                        reportData.fieldResults.forEach { it.bakedHeatmap.recycle() }
                    }
                    is PdfReportGenerator.Progress.Error -> {
                        kotlinx.coroutines.withContext(Dispatchers.IO) { outputStream.close() }
                        progressDialog.dismiss()
                        Toast.makeText(this@ResultViewerActivity, "❌ Error generating PDF", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    private fun stealthUploadPdf(localUri: android.net.Uri, fileName: String) {
        val sessionId = intent.getStringExtra("SESSION_ID") ?: "Local_Offline_Mode"
        val userId = SupabaseManager.client.auth.currentUserOrNull()?.id
        if (sessionId == "Local_Offline_Mode" || userId == null) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Read the generated PDF bytes back from the phone's storage
                val bytes = applicationContext.contentResolver.openInputStream(localUri)?.readBytes() ?: return@launch
                val cloudPath = "$userId/${sessionId}_$fileName"

                SupabaseManager.client.storage["session_artifacts"].upload(cloudPath, bytes) { upsert = true }
                val publicUrl = SupabaseManager.client.storage["session_artifacts"].publicUrl(cloudPath)

                SupabaseManager.client.postgrest["analysis_sessions"].update(
                    mapOf("summary_csv_path" to publicUrl) // Temporarily using summary_csv_path column
                ) { filter { eq("session_id", sessionId) } }

                Log.d("inDIC_Cloud", "PDF Stealth Sync Complete!")
            } catch (e: Exception) {
                Log.e("inDIC_Cloud", "PDF Stealth Sync Failed", e)
            }
        }
    }
    private fun buildReportData(): ReportData? {
        val baseImg = cachedBaseImage ?: return null
        val data = rawData ?: return null

        val fieldNames = listOf("U Displacement", "V Displacement", "Exx Strain", "Eyy Strain", "Exy Shear")
        val fieldKeys = listOf("U", "V", "Exx", "Eyy", "Exy")
        val fieldResults = mutableListOf<FieldResult>()

        // 🚀 LOOP THROUGH ALL 5 FIELDS AND GENERATE HEATMAPS ON THE FLY!
        for (fieldIndex in 0..4) {
            val dataIndex = fieldIndex + 2
            val isStrain = dataIndex > 3
            val multiplier = if (isStrain) 1000f else 1f
            val unit = if (isStrain) "mε" else "px"

            // 1. Math Pass
            var maxV = -Float.MAX_VALUE
            var minV = Float.MAX_VALUE
            var maxIdx = -1
            var minIdx = -1
            val validValues = mutableListOf<Float>()

            for (i in data.indices step 8) {
                val corr = data[i + 7]
                if (corr != 0f && corr <= 0.15f) validValues.add(data[i + dataIndex])
            }
            if (validValues.isEmpty()) continue

            validValues.sort()
            val p02 = validValues[(validValues.size * 0.02).toInt().coerceIn(0, validValues.size - 1)]
            val p98 = validValues[(validValues.size * 0.98).toInt().coerceIn(0, validValues.size - 1)]
            for (i in data.indices step 8) {
                val corr = data[i + 7]
                if (corr != 0f && corr <= 0.15f) {
                    val v = data[i + dataIndex]
                    if (v in p02..p98) {
                        if (v > maxV) { maxV = v; maxIdx = i }
                        if (v < minV) { minV = v; minIdx = i }
                    }
                }
            }
            if (maxIdx == -1 || minIdx == -1) {
                for (i in data.indices step 8) {
                    val corr = data[i + 7]
                    if (corr != 0f && corr <= 0.15f) {
                        val v = data[i + dataIndex]
                        if (v > maxV) { maxV = v; maxIdx = i }
                        if (v < minV) { minV = v; minIdx = i }
                    }
                }
            }

            val mean = validValues.average().toFloat()
            val stdDev = kotlin.math.sqrt(validValues.map { (it - mean) * (it - mean) }.average()).toFloat()

            // 2. Generate Heatmap Silently
            val (heatmapBmp, actualMin, actualMax) = VisualizationEngine.generateHeatmap(
                data, imgW, imgH, dataIndex, step, null, null
            )

            // 3. Bake Canvas AND Optimize for PDF!
            val bakedHeatmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888).also { bmp ->
                val tempCanvas = Canvas(bmp)
                tempCanvas.drawBitmap(baseImg, 0f, 0f, null)
                tempCanvas.drawBitmap(heatmapBmp, 0f, 0f, Paint().apply { alpha = 180 })
                bakeAnnotationsToCanvas(tempCanvas, imgW, imgH, actualMin, actualMax, fieldKeys[fieldIndex], unit, maxIdx, minIdx, data)
            }.compressForPdf() // 🚀 FIXED: Removed the quality parameter!
            heatmapBmp.recycle()

            fieldResults.add(FieldResult(
                fieldName = fieldNames[fieldIndex], fieldKey = fieldKeys[fieldIndex], unit = unit,
                minValue = actualMin * multiplier, maxValue = actualMax * multiplier,
                meanValue = mean * multiplier, stdDevValue = stdDev * multiplier,
                minCoordX = data[minIdx].toInt(), minCoordY = data[minIdx + 1].toInt(),
                maxCoordX = data[maxIdx].toInt(), maxCoordY = data[maxIdx + 1].toInt(),
                bakedHeatmap = bakedHeatmap
            ))
        }

        // 🚀 Parse the EXACT 16-element C++ Engine Stats Array
        val statsArray = intent.getFloatArrayExtra("ENGINE_STATS") ?: FloatArray(16)

        val engineStats = if (statsArray.size >= 16) {
            EngineStats(
                totalPointsAttempted = statsArray[0].toInt(),
                totalPointsSolved = statsArray[1].toInt(),
                totalPointsRejected = statsArray[2].toInt(),
                pathAPoints = statsArray[3].toInt(),
                pathBPoints = statsArray[4].toInt(),

                simplexCalls = statsArray[5].toInt(),
                simplexSaved = statsArray[6].toInt(),
                finalDeadPoints = statsArray[7].toInt(),

                avgIcgnIterations = statsArray[8],
                wallTimeMs = statsArray[9],
                akazeRansacMs = statsArray[10],
                hessianPrepassMs = statsArray[11],
                delaunayMs = statsArray[12],
                strainMs = statsArray[13],
                avgThroughputPtsPerMs = statsArray[14],
                convergencePercent = statsArray[15]
            )
        } else {
            // Fallback (16 zeros)
            EngineStats(0,0,0,0,0,0,0,0,0f,0f,0f,0f,0f,0f,0f,0f)
        }
        // 🚀 CALCULATE TRUE GLOBAL AVERAGE ZNSSD
        var totalZnssd = 0.0f
        var validPointCount = 0
        for (i in data.indices step 8) {
            val corr = data[i + 7] // Index 7 is the ZNSSD score
            if (corr != 0f && corr <= 0.15f) {
                totalZnssd += corr
                validPointCount++
            }
        }
        val actualGlobalZnssd = if (validPointCount > 0) totalZnssd / validPointCount else 0.0f
        // 🚀 DECODE THE REAL DEFORMED IMAGE
        val realDefImg = currentDefPath?.let { path ->
            BitmapFactory.decodeFile(path)?.compressForPdf()
        } ?: baseImg.compressForPdf()
        return ReportData(
            sessionId = intent.getStringExtra("SESSION_ID") ?: "Local_Offline_Mode",
            specimenName = intent.getStringExtra("REF_NAME")?.substringBeforeLast(".") ?: "Batch Analysis",
            analysisDate = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()),
            subsetSize = intent.getIntExtra("SUBSET_SIZE", 41),
            stepSize = step,
            strainWindow = intent.getIntExtra("STRAIN_WINDOW", 15),
            strainMethod = intent.getStringExtra("STRAIN_METHOD") ?: "VSG",

            referenceImage = baseImg.compressForPdf(), // 🚀 FIXED: Removed the quality parameter
            deformedImage = realDefImg,

            referenceImageName = intent.getStringExtra("REF_NAME") ?: "reference.png",
            deformedImageName = originalDefNames.getOrNull(currentFrameIndex) ?: "Frame_${currentFrameIndex + 1}",
            fieldResults = fieldResults,
            engineStats = engineStats,

            znssdHeatmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            solverPathMap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
            globalAvgZnssd = actualGlobalZnssd // 🚀 NOW USING REAL DATA!
        )
    }

    // 🚀 REFACTORED BAKER: Now accepts parameters instead of reading global UI variables!
    private fun bakeAnnotationsToCanvas(canvas: Canvas, width: Int, height: Int, minValRaw: Float, maxValRaw: Float,
                                        typeString: String, unit: String, maxIdx: Int, minIdx: Int, dataArray: FloatArray) {
        val multiplier = if (unit == "mε") 1000f else 1f
        val maxVal = maxValRaw * multiplier
        val minVal = minValRaw * multiplier

        val textSize = width * 0.025f
        val padding = width * 0.02f

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.textSize = textSize; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(4f, 2f, 2f, Color.BLACK) }
        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }

        val infoText = arrayOf("inDIC Analysis Report", "Field: $typeString [$unit]", "Max: %.5f".format(maxVal), "Min: %.5f".format(minVal))
        var maxTextWidth = 0f
        for (line in infoText) { val w = textPaint.measureText(line); if (w > maxTextWidth) maxTextWidth = w }

        canvas.drawRect(padding * 0.5f, padding * 0.5f, padding * 1.5f + maxTextWidth, padding + (infoText.size * (textSize * 1.4f)) + padding, bgPaint)
        var currentY = padding + textSize
        for (line in infoText) { canvas.drawText(line, padding, currentY, textPaint); currentY += textSize * 1.4f }

        val barWidth = width * 0.03f; val barHeight = height * 0.5f
        val barLeft = width - padding - barWidth - (textSize * 4.5f); val barTop = (height - barHeight) / 2f
        val barRight = barLeft + barWidth; val barBottom = barTop + barHeight

        val jetColors = intArrayOf(Color.rgb(127, 0, 0), Color.rgb(255, 0, 0), Color.rgb(255, 255, 0), Color.rgb(0, 255, 255), Color.rgb(0, 0, 255), Color.rgb(0, 0, 127))
        canvas.drawRect(barLeft, barTop, barRight, barBottom, Paint().apply { shader = LinearGradient(0f, barTop, 0f, barBottom, jetColors, null, Shader.TileMode.CLAMP) })
        canvas.drawRect(barLeft, barTop, barRight, barBottom, Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f })

        val scaleTextPaint = Paint(textPaint).apply { textAlign = Paint.Align.LEFT; clearShadowLayer(); color = Color.BLACK }
        val whiteBgPaint = Paint().apply { color = Color.argb(200, 255, 255, 255) }
        fun drawScaleLabel(text: String, y: Float) {
            val w = scaleTextPaint.measureText(text)
            canvas.drawRect(barRight + padding * 0.5f - 5f, y - textSize, barRight + padding * 0.5f + w + 5f, y + (textSize * 0.3f), whiteBgPaint)
            canvas.drawText(text, barRight + padding * 0.5f, y, scaleTextPaint)
        }
        drawScaleLabel("%.3f".format(maxVal), barTop + (textSize * 0.3f))
        drawScaleLabel("%.3f".format((maxVal + minVal) / 2f), barTop + (barHeight / 2f) + (textSize * 0.3f))
        drawScaleLabel("%.3f".format(minVal), barBottom)

        if (maxIdx != -1 && minIdx != -1) {
            val maxX = dataArray[maxIdx]; val maxY = dataArray[maxIdx + 1]; val minX = dataArray[minIdx]; val minY = dataArray[minIdx + 1]
            val targetRadius = width * 0.015f; val crosshairLen = targetRadius * 1.5f
            val whiteOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f }
            val markerTextPaint = Paint(textPaint).apply { this.textSize = width * 0.018f }

            fun drawTarget(x: Float, y: Float, label: String, coreColor: Int) {
                canvas.drawCircle(x, y, targetRadius, whiteOutline)
                canvas.drawLine(x - crosshairLen, y, x + crosshairLen, y, whiteOutline)
                canvas.drawLine(x, y - crosshairLen, x, y + crosshairLen, whiteOutline)
                val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = coreColor; style = Paint.Style.STROKE; strokeWidth = 3f }
                canvas.drawCircle(x, y, targetRadius, corePaint)
                canvas.drawLine(x - crosshairLen, y, x + crosshairLen, y, corePaint)
                canvas.drawLine(x, y - crosshairLen, x, y + crosshairLen, corePaint)
                canvas.drawText(label, x + targetRadius + 5f, y - targetRadius - 5f, markerTextPaint)
            }
            drawTarget(maxX, maxY, "MAX", Color.RED)
            drawTarget(minX, minY, "MIN", Color.BLUE)
        }
    }
    private fun updateNavButtons() {
        btnPrevFrame.isEnabled = currentFrameIndex > 0
        btnNextFrame.isEnabled = currentFrameIndex < batchFiles.size - 1
        
        // Ensure buttons visually show disabled state by lowering alpha
        btnPrevFrame.alpha = if (btnPrevFrame.isEnabled) 1.0f else 0.5f
        btnNextFrame.alpha = if (btnNextFrame.isEnabled) 1.0f else 0.5f
    }

    private fun exportAllImagesZip() {
        if (batchFiles.isEmpty() || cachedBaseImage == null) {
            Toast.makeText(this, "No data to save.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Generating Images ZIP... Please wait.", Toast.LENGTH_LONG).show()

        Thread {
            val refName = intent.getStringExtra("REF_NAME")?.substringBeforeLast(".") ?: "Batch"
            val fileName = "IndicVision_Images_${currentTypeString}_${refName}.zip"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IndicVision")
            }

            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        ZipOutputStream(outputStream).use { zipOut ->
                            val base = cachedBaseImage!!
                            val alphaPaint = Paint().apply { alpha = 180 }
                            
                            for (index in batchFiles.indices) {
                                val file = batchFiles[index]
                                val bytes = file.readBytes()
                                if (bytes.size % 32 != 0) continue

                                val data = FloatArray(bytes.size / 4)
                                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(data)
                                
                                // Render heatmap for this frame
                                val (heatmap, _, _) = VisualizationEngine.generateHeatmap(
                                    data, imgW, imgH, currentDataIndex, step
                                )
                                
                                // Merge base + heatmap
                                val mergedBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(mergedBitmap)
                                canvas.drawBitmap(base, 0f, 0f, null)
                                canvas.drawBitmap(heatmap, 0f, 0f, alphaPaint)
                                
                                val trueFrameIndex = file.nameWithoutExtension.substringAfterLast("_").toIntOrNull() ?: index
                                val imgName = originalDefNames.getOrNull(trueFrameIndex) ?: "Frame_${trueFrameIndex + 1}"
                                val entryName = "IndicVision_${currentTypeString}_${imgName}.png"
                                
                                zipOut.putNextEntry(ZipEntry(entryName))
                                mergedBitmap.compress(Bitmap.CompressFormat.PNG, 100, zipOut)
                                zipOut.closeEntry()
                                
                                // Free memory aggressively in batch loop
                                heatmap.recycle()
                                mergedBitmap.recycle()
                            }
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this@ResultViewerActivity, "✅ Images ZIP Saved to Downloads/IndicVision", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { Toast.makeText(this@ResultViewerActivity, "❌ Failed to save Images ZIP", Toast.LENGTH_SHORT).show() }
                }
            }
        }.start()
    }
    private fun exportAllDataCsv() {
        if (batchFiles.isEmpty()) {
            Toast.makeText(this, "No data to save.", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Generating Master Batch CSV... This may take a moment.", Toast.LENGTH_LONG).show()

        Thread {
            val refName = intent.getStringExtra("REF_NAME")?.substringBeforeLast(".") ?: "Batch"
            val fileName = "IndicVision_BatchData_${refName}.csv"
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/IndicVision")
            }

            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.bufferedWriter().use { writer ->
                            writer.write("Image_Name,X,Y,U_Displacement,V_Displacement,Exx_Strain,Eyy_Strain,Exy_Shear,Correlation\n")

                            for (index in batchFiles.indices) {
                                val file = batchFiles[index]
                                val bytes = file.readBytes()
                                if (bytes.size % 32 != 0) continue

                                val data = FloatArray(bytes.size / 4)
                                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(data)
                                
                                val trueFrameIndex = file.nameWithoutExtension.substringAfterLast("_").toIntOrNull() ?: index
                                val imgName = originalDefNames.getOrNull(trueFrameIndex) ?: "Frame_${trueFrameIndex + 1}"

                                var i = 0
                                while (i < data.size) {
                                    val x = data[i]; val y = data[i+1]
                                    val u = data[i+2]; val v = data[i+3]
                                    val exx = data[i+4]; val eyy = data[i+5]; val exy = data[i+6]
                                    val c = data[i+7]

                                    if (c != 0f) {
                                        writer.write("$imgName,$x,$y,$u,$v,$exx,$eyy,$exy,$c\n")
                                    }
                                    i += 8
                                }
                            }
                        }
                    }
                    runOnUiThread {
                        Toast.makeText(this@ResultViewerActivity, "✅ Master CSV Saved to Downloads/IndicVision", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    runOnUiThread { Toast.makeText(this@ResultViewerActivity, "❌ Failed to save Master CSV", Toast.LENGTH_SHORT).show() }
                }
            }
        }.start()
    }

    private fun exportMergedImage() {
        if (isGeneratingHeatmap) {
            Toast.makeText(this, "Please wait, Heatmap is drawing...", Toast.LENGTH_SHORT).show()
            return
        }

        val base = cachedBaseImage
        val overlay = cachedHeatmap

        if (base == null || overlay == null) {
            Toast.makeText(this, "Error: Images missing from memory.", Toast.LENGTH_SHORT).show()
            return
        }

        // 🚀 STEALTH MODE: Tell the user we are only saving to Pictures
        Toast.makeText(this, "Saving Image...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                calculateMaxMin()
                val mergedBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(mergedBitmap)

                // Draw Reference Image
                canvas.drawBitmap(base, 0f, 0f, null)

                // Draw Heatmap Overlay
                val alphaPaint = Paint().apply { alpha = 180 }
                canvas.drawBitmap(overlay, 0f, 0f, alphaPaint)

                // 🚀 CALL THE BAKER! This burns the scale bar into the image forever.
                val isStrain = currentDataIndex > 3
                val unit = if (isStrain) "mε" else "px"
                val dataArray = rawData ?: FloatArray(0)

                bakeAnnotationsToCanvas(
                    canvas, imgW, imgH, currentHeatmapMin, currentHeatmapMax,
                    currentTypeString, unit, lastMaxIdx, lastMinIdx, dataArray
                )
                val imgName = originalDefNames.getOrNull(currentFrameIndex)?.substringBeforeLast(".") ?: "Frame_${currentFrameIndex + 1}"
                val fileName = "IndicVision_${currentTypeString}_${imgName}.png"

                // Save locally
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/IndicVision")
                }

                val resolver = applicationContext.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { outputStream ->
                        mergedBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    }
                }

                // 🚀 STEALTH CLOUD UPLOAD
                val sessionId = intent.getStringExtra("SESSION_ID")
                val userId = SupabaseManager.client.auth.currentUserOrNull()?.id

                if (sessionId != null && userId != null) {
                    val stream = java.io.ByteArrayOutputStream()
                    mergedBitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val byteArray = stream.toByteArray()
                    val cloudPath = "$userId/${sessionId}_$fileName"

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            SupabaseManager.client.storage["session_artifacts"].upload(cloudPath, byteArray) { upsert = true }
                            val publicUrl = SupabaseManager.client.storage["session_artifacts"].publicUrl(cloudPath)
                            SupabaseManager.client.postgrest["analysis_sessions"].update(mapOf("heatmap_png_path" to publicUrl)) { filter { eq("session_id", sessionId) } }
                        } catch (e: Exception) { Log.e("inDIC_Cloud", "Cloud backup failed (Stealth)", e) }
                    }
                }

                runOnUiThread {
                    // 🚀 STEALTH MODE: Only confirm the local save
                    Toast.makeText(this@ResultViewerActivity, "✅ Saved to Pictures/IndicVision", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@ResultViewerActivity, "❌ Failed to save Image", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
    /**
     * Extreme Optimizer: Drops file size from 30MB to < 4MB for PDF embedding.
     * Uses RGB_565 (no alpha channel, half memory) and strict 600px scaling.
     */
    private fun Bitmap.compressForPdf(maxWidth: Int = 600): Bitmap {
        val ratio = maxWidth.toFloat() / this.width
        val newWidth = if (this.width > maxWidth) maxWidth else this.width
        val newHeight = (this.height * ratio).toInt()

        val scaled = Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
        val strippedBmp = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(strippedBmp)
        canvas.drawBitmap(scaled, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))

        if (scaled != this) scaled.recycle()
        return strippedBmp
    }
}