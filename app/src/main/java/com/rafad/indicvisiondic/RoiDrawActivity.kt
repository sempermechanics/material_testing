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
    private lateinit var switchSubtractMode: Switch // 🚀 NEW: Subtract Toggle

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
        switchSubtractMode = findViewById(R.id.switchSubtractMode) // 🚀 NEW

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
        // 🚀 NEW: Connect the Subtract Mode Switch
        switchSubtractMode.setOnCheckedChangeListener { _, isChecked ->
            overlayRoi.isSubtractMode = isChecked
            tvHud.text = if (isChecked) "Mode: ERASE (Holes)" else "Mode: DRAW (Material)"
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
            val rectX: Int; val rectY: Int; val rectW: Int; val rectH: Int
            val maskBytes: ByteArray

            if (!overlayRoi.hasValidRoi && overlayRoi.holes.isEmpty()) {
                // 🚀 SCENARIO 1: Absolutely empty canvas. Pure Full Image.
                rectX = 0; rectY = 0; rectW = realImageWidth; rectH = realImageHeight
                maskBytes = ByteArray(realImageWidth * realImageHeight) { 255.toByte() }
                Toast.makeText(this, "Full Image Selected", Toast.LENGTH_SHORT).show()
            } else {
                // 🚀 SCENARIO 2: Custom Mask (Either an ADD shape exists, OR Holes exist on the Full Image)
                if (overlayRoi.hasValidRoi) {
                    val finalRoi = overlayRoi.getRelativeRoi()
                    rectX = finalRoi.left.toInt().coerceAtLeast(0)
                    rectY = finalRoi.top.toInt().coerceAtLeast(0)
                    rectW = finalRoi.width().toInt().coerceAtMost(realImageWidth - rectX)
                    rectH = finalRoi.height().toInt().coerceAtMost(realImageHeight - rectY)
                } else {
                    // Full image bounds because they only drew holes
                    rectX = 0; rectY = 0; rectW = realImageWidth; rectH = realImageHeight
                }

                if (rectW <= 0 || rectH <= 0) {
                    Toast.makeText(this, "Invalid ROI size", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                maskBytes = overlayRoi.generateMaskBytes()
            }

            // Save to cache and return Intent
            val maskFile = File(cacheDir, "roi_mask_cache.bin")
            java.io.FileOutputStream(maskFile).use { it.write(maskBytes) }

            val resultIntent = Intent()
            resultIntent.putExtra("ROI_X", rectX)
            resultIntent.putExtra("ROI_Y", rectY)
            resultIntent.putExtra("ROI_W", rectW)
            resultIntent.putExtra("ROI_H", rectH)
            resultIntent.putExtra("MASK_FILE_PATH", maskFile.absolutePath)

            setResult(Activity.RESULT_OK, resultIntent)
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