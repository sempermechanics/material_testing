package com.rafad.indicvisiondic

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.*
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ResultViewerActivity : AppCompatActivity() {

    private lateinit var imgMain: TouchImageView
    private lateinit var imgHeatmap: ImageView
    private lateinit var spinnerType: Spinner

    // UI - Scale Bar
    private lateinit var layoutColorScale: LinearLayout
    private lateinit var tvScaleMax: TextView
    private lateinit var tvScaleMin: TextView

    private lateinit var btnExportCsv: Button
    private lateinit var btnExportImage: Button
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

    // States
    private var currentDataIndex = 2
    private var isInspectModeActive = false
    private var isMaxMinActive = false

    private var lastClosestIdx = -1
    private var lastMaxIdx = -1
    private var lastMinIdx = -1

    private val customBoundsMap = mutableMapOf<Int, Pair<Float, Float>>()

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
        }

        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)
        spinnerType = findViewById(R.id.spinnerResultType)

        layoutColorScale = findViewById(R.id.layoutColorScale)
        tvScaleMax = findViewById(R.id.tvScaleMax)
        tvScaleMin = findViewById(R.id.tvScaleMin)

        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnExportImage = findViewById(R.id.btnExportImage)

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

        val dataPath = intent.getStringExtra("DATA_PATH")
        if (dataPath != null) {
            val file = File(dataPath)
            val bytes = file.readBytes()
            rawData = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(rawData)
        }

        val defPath = intent.getStringExtra("DEF_PATH")
        if (defPath != null) {
            cachedBaseImage = BitmapFactory.decodeFile(defPath)
            imgMain.setImageBitmap(cachedBaseImage)
            imgMain.setTrueImageDimensions(imgW, imgH)
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

        // 🚀 UPDATED TOUCH LISTENER: Acts as a transparent proxy when Inspect is OFF
        glassShield.setOnTouchListener { _, event ->
            if (!isInspectModeActive) {
                // 🚀 Manually forward the physical gesture to the TouchImageView underneath!
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
            true // Consume touch so it doesn't leak
        }

        btnExportCsv.setOnClickListener { exportToCSV() }
        btnExportImage.setOnClickListener { exportMergedImage() }

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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("LAST_CLOSEST_IDX", lastClosestIdx)
        outState.putInt("LAST_MAX_IDX", lastMaxIdx)
        outState.putInt("LAST_MIN_IDX", lastMinIdx)
        outState.putBoolean("MAX_MIN_ACTIVE", isMaxMinActive)
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

        for (i in data.indices step 8) {
            val corr = data[i + 7]
            if (corr != 0f && corr <= 0.25f) {
                val v = data[i + currentDataIndex]
                if (v > maxV) { maxV = v; lastMaxIdx = i }
                if (v < minV) { minV = v; lastMinIdx = i }
            }
        }
    }

    // 🚀 FIXED: Enforces boundary checking
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
                if (corr != 0f && corr <= 0.25f) {
                    minDistSq = distSq
                    closestIdx = i
                }
            }
        }

        lastClosestIdx = closestIdx
        refreshCrosshairs()
    }

    // 🚀 FIXED: Hides crosshair and shows Out of Bounds when off the heatmap
    private fun refreshCrosshairs() {
        val data = rawData ?: return

        // 1. Probe Logic
        if (isInspectModeActive) {
            cardInspectorHud.visibility = View.VISIBLE

            if (lastClosestIdx != -1) {
                val actualX = data[lastClosestIdx].toInt()
                val actualY = data[lastClosestIdx + 1].toInt()
                val value = data[lastClosestIdx + currentDataIndex]
                val unit = if (currentDataIndex > 3) "ε" else "px"

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
            val maxV = data[lastMaxIdx+currentDataIndex]
            val minV = data[lastMinIdx+currentDataIndex]

            val ptsMax = floatArrayOf(maxX.toFloat(), maxY.toFloat())
            val ptsMin = floatArrayOf(minX.toFloat(), minY.toFloat())
            imgMain.imageMatrix.mapPoints(ptsMax)
            imgMain.imageMatrix.mapPoints(ptsMin)

            glassShield.updateMaxMinPositions(ptsMax[0], ptsMax[1], ptsMin[0], ptsMin[1])

            val unit = if (currentDataIndex > 3) "ε" else "px"
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

        val etMax = EditText(this).apply {
            hint = "Max Value"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        val etMin = EditText(this).apply {
            hint = "Min Value"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }

        val existing = customBoundsMap[currentDataIndex]
        if (existing != null) {
            etMin.setText(existing.first.toString())
            etMax.setText(existing.second.toString())
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
                    customBoundsMap[currentDataIndex] = Pair(minVal, maxVal)
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
        val customBounds = customBoundsMap[index]

        Thread {
            val result = VisualizationEngine.generateHeatmap(
                data, imgW, imgH, index, step,
                customBounds?.first, customBounds?.second
            )
            val heatmap = result.first
            val minV = result.second
            val maxV = result.third

            runOnUiThread {
                cachedHeatmap = heatmap
                imgHeatmap.setImageBitmap(heatmap)
                imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
                imgHeatmap.invalidate()

                val unit = if (index > 3) " [ε]" else " px"
                tvScaleMax.text = "Max: %.4f%s".format(maxV, unit)
                tvScaleMin.text = "Min: %.4f%s".format(minV, unit)
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
            val fileName = "IndicVision_${System.currentTimeMillis()}.csv"
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

        Toast.makeText(this, "Merging High-Res Image...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val mergedBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(mergedBitmap)

                canvas.drawBitmap(base, 0f, 0f, null)

                val alphaPaint = Paint().apply { alpha = 180 }
                canvas.drawBitmap(overlay, 0f, 0f, alphaPaint)

                val fileName = "IndicVision_${currentTypeString}_${System.currentTimeMillis()}.png"
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
                    runOnUiThread {
                        Toast.makeText(this@ResultViewerActivity, "✅ Image Saved to Pictures/IndicVision", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@ResultViewerActivity, "❌ Failed to save Image", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}