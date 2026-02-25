package com.rafad.indicvisiondic

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
import kotlin.math.sqrt

class ResultViewerActivity : AppCompatActivity() {

    private lateinit var imgMain: TouchImageView
    private lateinit var imgHeatmap: ImageView
    private lateinit var spinnerType: Spinner
    private lateinit var tvScaleMax: TextView

    // New: Point Inspector UI
    private var tvPointInfo: TextView? = null

    // Export Buttons
    private lateinit var btnExportCsv: Button
    private lateinit var btnExportImage: Button

    private var rawData: FloatArray? = null
    private var imgW = 0
    private var imgH = 0
    private var step = 5

    // Variables for Exporter
    private var cachedBaseImage: Bitmap? = null
    private var cachedHeatmap: Bitmap? = null
    private var currentTypeString: String = "Displacement"
    private var isGeneratingHeatmap = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_viewer)

        // 1. Bind UI
        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)
        spinnerType = findViewById(R.id.spinnerResultType)
        tvScaleMax = findViewById(R.id.tvScaleMax)

        // Try to find the inspector text view (Safe if missing)
        tvPointInfo = findViewById(R.id.tvPointInfo)

        btnExportCsv = findViewById(R.id.btnExportCsv)
        btnExportImage = findViewById(R.id.btnExportImage)

        // 2. Get Intent Data
        imgW = intent.getIntExtra("IMG_W", 0)
        imgH = intent.getIntExtra("IMG_H", 0)
        step = intent.getIntExtra("STEP", 5)

        // Read raw data
        val dataPath = intent.getStringExtra("DATA_PATH")
        if (dataPath != null) {
            val file = File(dataPath)
            if (file.exists()) {
                val bytes = file.readBytes()
                rawData = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(rawData)
            }
        }

        // Load deformed image into memory
        val defPath = intent.getStringExtra("DEF_PATH")
        if (defPath != null) {
            cachedBaseImage = BitmapFactory.decodeFile(defPath)
            imgMain.setImageBitmap(cachedBaseImage)
            // Important: Tell TouchImageView the real dimensions for accurate coordinate mapping
            imgMain.setTrueImageDimensions(imgW, imgH)
        }

        // 3. Matrix Sync (Keeps heatmap locked to base image)
        imgMain.onMatrixChangedListener = {
            imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
            imgHeatmap.invalidate()
        }

        // 4. Setup Spinner
        val options = arrayOf("U Displacement", "V Displacement", "Exx Strain", "Eyy Strain", "Exy Shear")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                currentTypeString = options[position].replace(" ", "_")
                updateVisualization(position + 2)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // 5. Setup Export Listeners
        btnExportCsv.setOnClickListener { exportToCSV() }
        btnExportImage.setOnClickListener { exportMergedImage() }

        // 6. Setup Point Inspector (Touch Listener)
        setupPointInspector()
    }

    private fun setupPointInspector() {
        imgMain.setOnTouchListener { _, event ->
            // Only process if we have data and a TextView to show it in
            if (rawData == null || tvPointInfo == null) return@setOnTouchListener false

            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                // A. Map Screen Touch -> Physical Image Coordinates
                val pts = floatArrayOf(event.x, event.y)
                val inverse = Matrix()
                // Use getZoomMatrix() from your TouchImageView to get the current transform
                imgMain.getZoomMatrix().invert(inverse)
                inverse.mapPoints(pts)

                val physX = pts[0]
                val physY = pts[1]

                // B. Find Nearest DIC Grid Point
                // (Optimization: We search 1D array, but we could spatially hash for speed if needed)
                val data = rawData!!
                var closestIdx = -1
                var minDist = 50f // Search radius (pixels)

                // rawData format: [x, y, u, v, exx, eyy, exy, corr]... repeated
                for (i in data.indices step 8) {
                    val gx = data[i]
                    val gy = data[i+1]

                    // Simple distance check
                    val dx = gx - physX
                    val dy = gy - physY
                    val dist = sqrt((dx*dx + dy*dy).toDouble()).toFloat()

                    if (dist < minDist) {
                        minDist = dist
                        closestIdx = i
                    }
                }

                // C. Update UI
                if (closestIdx != -1) {
                    val u = data[closestIdx + 2]
                    val v = data[closestIdx + 3]
                    val exx = data[closestIdx + 4]
                    val eyy = data[closestIdx + 5]

                    val infoText = "Point (${physX.toInt()}, ${physY.toInt()})\n" +
                            "U: %.3f px  V: %.3f px\n".format(u, v) +
                            "Exx: %.4f  Eyy: %.4f".format(exx, eyy)

                    tvPointInfo?.text = infoText
                    tvPointInfo?.visibility = View.VISIBLE
                } else {
                    tvPointInfo?.text = "No data point nearby"
                }
            }
            // Return false so the touch event propagates to TouchImageView for zooming/panning
            false
        }
    }

    private fun updateVisualization(index: Int) {
        val data = rawData ?: return

        isGeneratingHeatmap = true // Lock export

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

                isGeneratingHeatmap = false // Unlock export
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
                // Create a blank full-resolution canvas
                val mergedBitmap = Bitmap.createBitmap(imgW, imgH, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(mergedBitmap)

                // Draw base image
                canvas.drawBitmap(base, 0f, 0f, null)

                // Draw the translucent heatmap over it exactly as the user sees it
                val alphaPaint = Paint().apply { alpha = 180 }
                canvas.drawBitmap(overlay, 0f, 0f, alphaPaint)

                // Save to Gallery
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