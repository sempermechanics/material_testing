package com.rafad.indicvisiondic

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class ResultViewerActivity : AppCompatActivity() {

    private lateinit var imgMain: TouchImageView
    private lateinit var imgHeatmap: ImageView
    private lateinit var spinnerType: Spinner
    private lateinit var tvScaleMax: TextView

    private var rawData: FloatArray? = null
    private var imgW = 0
    private var imgH = 0
    private var step = 5
    private var roiX = 0
    private var roiY = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result_viewer)

        imgMain = findViewById(R.id.imgBaseResult)
        imgHeatmap = findViewById(R.id.imgHeatmapOverlay)
        spinnerType = findViewById(R.id.spinnerResultType)
        tvScaleMax = findViewById(R.id.tvScaleMax)

        imgW = intent.getIntExtra("IMG_W", 0)
        imgH = intent.getIntExtra("IMG_H", 0)
        step = intent.getIntExtra("STEP", 5)

        // CRITICAL: We need the ROI offset!
        roiX = intent.getIntExtra("ROI_X", 0)
        roiY = intent.getIntExtra("ROI_Y", 0)

        // 1. Read raw data
        val dataPath = intent.getStringExtra("DATA_PATH")
        if (dataPath != null) {
            val file = File(dataPath)
            val bytes = file.readBytes()
            rawData = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer().get(rawData)
            diagnoseData() // Print stats to Logcat
        }

        // 2. Load deformed image
        val defPath = intent.getStringExtra("DEF_PATH")
        if (defPath != null) {
            imgMain.setImageBitmap(BitmapFactory.decodeFile(defPath))

            // --- THE FIX: Forcefully inject the true dimensions & Log it! ---
            Log.d("TouchDebug", "Activity forcing dimensions into TouchImageView: W=$imgW, H=$imgH")
            imgMain.setTrueImageDimensions(imgW, imgH)
            // ----------------------------------------------------------------
        }

        // 3. Setup Continuous Matrix Sync (Fixes zoom lag AND initial load)
        imgMain.onMatrixChangedListener = {
            imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
            imgHeatmap.invalidate()
        }

        // 4. Setup Spinner
        val options = arrayOf("U Displacement", "V Displacement", "Exx Strain", "Eyy Strain", "Exy Shear")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                updateVisualization(position + 2)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun updateVisualization(index: Int) {
        val data = rawData ?: return

        Thread {
            val result = VisualizationEngine.generateHeatmap(data, imgW, imgH, index, step)
            val heatmap = result.first
            val minV = result.second
            val maxV = result.third

            runOnUiThread {
                imgHeatmap.setImageBitmap(heatmap)
                imgHeatmap.imageMatrix = imgMain.getZoomMatrix()
                imgHeatmap.invalidate()

                val unit = if (index > 3) " [ε]" else " px"
                tvScaleMax.text = "%.4f%s\n\n\n\n\n\n\n\n\n%.4f%s".format(maxV, unit, minV, unit)
            }
        }.start()
    }

    private fun diagnoseData() {
        val data = rawData ?: return
        Log.d("DataDiagnosis", "=== RAW DATA DIAGNOSIS ===")
        Log.d("DataDiagnosis", "Expected points: ${data.size / 8}")
        for (i in 0 until min(5, data.size / 8)) {
            val idx = i * 8
            Log.d("DataDiagnosis", "Pt $i: X=${data[idx]}, Y=${data[idx+1]}, U=${data[idx+2]}, V=${data[idx+3]}, Exx=${data[idx+4]}")
        }
    }
}