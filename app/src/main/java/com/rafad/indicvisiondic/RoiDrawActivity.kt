package com.rafad.indicvisiondic

import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.math.roundToInt

class RoiDrawActivity : AppCompatActivity() {

    private lateinit var imgRoiCanvas: ImageView
    private lateinit var overlayRoi: StudioOverlayView
    private lateinit var tvHud: TextView
    private lateinit var rgDrawMode: RadioGroup
    private lateinit var btnSaveRoi: Button
    private lateinit var btnCancelRoi: Button
    private lateinit var btnResetRoi: Button

    private var realImageWidth = 0
    private var realImageHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roi_draw)

        // 1. Bind Views
        imgRoiCanvas = findViewById(R.id.imgRoiCanvas)
        overlayRoi = findViewById(R.id.overlayRoi)
        tvHud = findViewById(R.id.tvHud)
        rgDrawMode = findViewById(R.id.rgDrawMode)
        btnSaveRoi = findViewById(R.id.btnSaveRoi)
        btnCancelRoi = findViewById(R.id.btnCancelRoi)
        btnResetRoi = findViewById(R.id.btnResetRoi)

        // 2. Get Intent Data
        val imageFilePath = intent.getStringExtra("IMAGE_FILE_PATH")
        realImageWidth = intent.getIntExtra("IMAGE_WIDTH", 0)
        realImageHeight = intent.getIntExtra("IMAGE_HEIGHT", 0)

        overlayRoi.realImageWidth = realImageWidth
        overlayRoi.realImageHeight = realImageHeight

        // 3. Load Image safely (Fixes the OpenGL clipping issue)
        if (imageFilePath != null) {
            val file = File(imageFilePath)
            if (file.exists()) {
                val bytes = file.readBytes()
                // 🚀 Scale exactly to device screen width to bypass texture limits
                val screenWidth = resources.displayMetrics.widthPixels
                val bitmap = IndicVisionNativeLib.getPreviewFromBytes(bytes, screenWidth)

                if (bitmap == null) {
                    Toast.makeText(this, "Failed to decode image!", Toast.LENGTH_LONG).show()
                } else {
                    imgRoiCanvas.setImageBitmap(bitmap)
                    imgRoiCanvas.post {
                        overlayRoi.imageView = imgRoiCanvas

                        // 🚀 Restore previous drawing if screen was rotated
                        if (savedInstanceState != null) {
                            val left = savedInstanceState.getFloat("ROI_L", -1f)
                            if (left != -1f) {
                                val top = savedInstanceState.getFloat("ROI_T")
                                val right = savedInstanceState.getFloat("ROI_R")
                                val bottom = savedInstanceState.getFloat("ROI_B")
                                overlayRoi.restoreRelativeRoi(RectF(left, top, right, bottom))
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Error: Image file not found", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // 4. Handle Draw Modes
        rgDrawMode.setOnCheckedChangeListener { _, checkedId ->
            overlayRoi.currentMode = when (checkedId) {
                R.id.rbSquare -> StudioOverlayView.RoiMode.SQUARE
                R.id.rbCircle -> StudioOverlayView.RoiMode.CIRCLE
                R.id.rbEllipse -> StudioOverlayView.RoiMode.ELLIPSE
                R.id.rbFreeform -> StudioOverlayView.RoiMode.FREEFORM
                else -> StudioOverlayView.RoiMode.RECTANGLE
            }
            tvHud.text = "Switched to ${overlayRoi.currentMode} mode"
        }

        // Restore Radio Button state after rotation
        if (savedInstanceState != null) {
            rgDrawMode.check(savedInstanceState.getInt("DRAW_MODE", R.id.rbRect))
        }

        // 5. Manual Reset Button Logic
        btnResetRoi.setOnClickListener {
            overlayRoi.reset()
            tvHud.text = "Canvas Cleared"
        }

        // 6. Live Updates
        overlayRoi.onRoiChangedListener = { roi ->
            if (roi.width() > 0 && roi.height() > 0) {
                val w = roi.width().roundToInt()
                val h = roi.height().roundToInt()
                val x = roi.left.roundToInt()
                val y = roi.top.roundToInt()
                tvHud.text = "ROI: $w x $h px  |  Pos: ($x, $y)"
            } else {
                tvHud.text = "Select tool and draw"
            }
        }

        // 7. Save Button Logic
        btnSaveRoi.setOnClickListener {
            if (!overlayRoi.hasValidRoi) {
                Toast.makeText(this, "Draw a shape first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val finalRoi = overlayRoi.getRelativeRoi()
            val rectX = finalRoi.left.toInt().coerceAtLeast(0)
            val rectY = finalRoi.top.toInt().coerceAtLeast(0)
            val rectW = finalRoi.width().toInt().coerceAtMost(realImageWidth - rectX)
            val rectH = finalRoi.height().toInt().coerceAtMost(realImageHeight - rectY)

            if (rectW <= 0 || rectH <= 0) {
                Toast.makeText(this, "Invalid ROI size", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val resultIntent = Intent()
            resultIntent.putExtra("ROI_X", rectX)
            resultIntent.putExtra("ROI_Y", rectY)
            resultIntent.putExtra("ROI_W", rectW)
            resultIntent.putExtra("ROI_H", rectH)

            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }

        btnCancelRoi.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    // 🚀 THE LIFECYCLE SAVER: Triggers right before the screen rotates
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("DRAW_MODE", rgDrawMode.checkedRadioButtonId)

        if (overlayRoi.hasValidRoi && overlayRoi.currentMode != StudioOverlayView.RoiMode.FREEFORM) {
            val relativeRoi = overlayRoi.getRelativeRoi()
            outState.putFloat("ROI_L", relativeRoi.left)
            outState.putFloat("ROI_T", relativeRoi.top)
            outState.putFloat("ROI_R", relativeRoi.right)
            outState.putFloat("ROI_B", relativeRoi.bottom)
        }
    }
}