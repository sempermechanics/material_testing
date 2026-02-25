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
    private lateinit var tvScaleMax: TextView

    // Export Buttons
    private lateinit var btnExportCsv: Button
    private lateinit var btnExportImage: Button

    // Inspector UI Components
    private lateinit var toggleInspect: ToggleButton
    private lateinit var cardInspectorHud: androidx.cardview.widget.CardView
    private lateinit var tvInspectorData: TextView
    private lateinit var glassShield: View // 🚀 The new touch barrier

    private var rawData: FloatArray? = null
    private var imgW = 0
    private var imgH = 0
    private var step = 5

    private var cachedBaseImage: Bitmap? = null
    private var cachedHeatmap: Bitmap? = null
    private var currentTypeString: String = "U_Displacement"
    private var isGeneratingHeatmap = false

    // Inspector States
    private var currentDataIndex = 2
    private var isInspectModeActive = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_viewer)

        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)
        spinnerType = findViewById(R.id.spinnerResultType)
        tvScaleMax = findViewById(R.id.tvScaleMax)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnExportImage = findViewById(R.id.btnExportImage)

        // Bind New UI
        toggleInspect = findViewById(R.id.toggleInspect)
        cardInspectorHud = findViewById(R.id.cardInspectorHud)
        tvInspectorData = findViewById(R.id.tvInspectorData)
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

        imgMain.onMatrixChangedListener = {
            imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
            imgHeatmap.invalidate()
        }

        val options = arrayOf("U Displacement", "V Displacement", "Exx Strain", "Eyy Strain", "Exy Shear")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                currentTypeString = options[position].replace(" ", "_")
                currentDataIndex = position + 2
                updateVisualization(currentDataIndex)

                // Hide HUD when changing types to prevent stale data display
                if (isInspectModeActive) {
                    tvInspectorData.text = "Tap image to inspect"
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // 🚀 TOGGLE LOGIC: Controls the Glass Shield
        toggleInspect.setOnCheckedChangeListener { _, isChecked ->
            isInspectModeActive = isChecked
            if (isChecked) {
                // Activate the shield and show the prompt
                glassShield.visibility = View.VISIBLE
                cardInspectorHud.visibility = View.VISIBLE
                tvInspectorData.text = "Tap image to inspect"
            } else {
                // Remove the shield so imgMain can be zoomed/panned again
                glassShield.visibility = View.GONE
                cardInspectorHud.visibility = View.GONE
            }
        }

        // 🚀 THE GLASS SHIELD TOUCH LISTENER
        // This ONLY runs when Inspect Mode is ON, because the shield is GONE otherwise.
        glassShield.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {

                // 1. Math: Map touch to physical image pixels using the underlying image matrix
                val pts = floatArrayOf(event.x, event.y)
                val inverse = android.graphics.Matrix()
                imgMain.imageMatrix.invert(inverse)
                inverse.mapPoints(pts)

                val physX = pts[0]
                val physY = pts[1]

                // 2. Data Scan
                val data = rawData
                if (data != null) {
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

                    // 3. UI Update
                    if (closestIdx != -1) {
                        val actualX = data[closestIdx].toInt()
                        val actualY = data[closestIdx + 1].toInt()
                        val value = data[closestIdx + currentDataIndex]

                        val unit = if (currentDataIndex > 3) "ε" else "px"
                        val valName = spinnerType.selectedItem.toString()

                        tvInspectorData.text = "Loc: ($actualX, $actualY)\n$valName: %.5f %s".format(value, unit)
                    } else {
                        tvInspectorData.text = "Out of bounds / No Data"
                    }
                }
            }
            // Always consume the touch so it doesn't leak through to the image below
            true
        }

        btnExportCsv.setOnClickListener { exportToCSV() }
        btnExportImage.setOnClickListener { exportMergedImage() }
    }

    private fun updateVisualization(index: Int) {
        val data = rawData ?: return
        isGeneratingHeatmap = true

        Thread {
            val result = VisualizationEngine.generateHeatmap(data, imgW, imgH, index, step)
            val heatmap = result.first
            val minV = result.second
            val maxV = result.third

            runOnUiThread {
                cachedHeatmap = heatmap
                imgHeatmap.setImageBitmap(heatmap)
                imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
                imgHeatmap.invalidate()

                val unit = if (index > 3) " [ε]" else " px"
                tvScaleMax.text = "%.4f%s\n\n\n\n\n\n\n\n\n%.4f%s".format(maxV, unit, minV, unit)
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