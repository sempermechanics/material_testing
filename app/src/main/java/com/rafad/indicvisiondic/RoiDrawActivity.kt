package com.rafad.indicvisiondic

import android.app.Activity
import android.content.Intent
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.math.max
import kotlin.math.min

class RoiDrawActivity : AppCompatActivity() {

    private lateinit var imgRoiCanvas: ImageView
    private lateinit var overlayRoi: StudioOverlayView
    private lateinit var tvHud: TextView
    private lateinit var rgDrawMode: RadioGroup
    private lateinit var btnSaveRoi: Button
    private lateinit var btnCancelRoi: Button

    private var realImageWidth = 0
    private var realImageHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roi_draw)

        imgRoiCanvas = findViewById(R.id.imgRoiCanvas)
        overlayRoi = findViewById(R.id.overlayRoi)
        tvHud = findViewById(R.id.tvHud)
        rgDrawMode = findViewById(R.id.rgDrawMode)
        btnSaveRoi = findViewById(R.id.btnSaveRoi)
        btnCancelRoi = findViewById(R.id.btnCancelRoi)

        val imageFilePath = intent.getStringExtra("IMAGE_FILE_PATH")
        realImageWidth = intent.getIntExtra("IMAGE_WIDTH", 0)
        realImageHeight = intent.getIntExtra("IMAGE_HEIGHT", 0)

        if (imageFilePath != null) {
            try {
                val file = File(imageFilePath)
                if (file.exists()) {
                    val bytes = file.readBytes()
                    val bitmap = IndicVisionNativeLib.getPreviewFromBytes(bytes, 1000)
                    imgRoiCanvas.setImageBitmap(bitmap)

                    // --- CRITICAL FIX: Link Overlay after Layout ---
                    imgRoiCanvas.post {
                        overlayRoi.imageView = imgRoiCanvas
                    }
                    // -----------------------------------------------
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to load image memory", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        // Optional: Log the bounds to verify it works
        overlayRoi.onBoundsUpdated = { bounds ->
            Log.d("ROI_DEBUG", "Calculated Image Bounds: $bounds")
        }

        rgDrawMode.setOnCheckedChangeListener { _, checkedId ->
            overlayRoi.currentMode = when (checkedId) {
                R.id.rbSquare -> StudioOverlayView.RoiMode.SQUARE
                R.id.rbCircle -> StudioOverlayView.RoiMode.CIRCLE
                R.id.rbEllipse -> StudioOverlayView.RoiMode.ELLIPSE
                R.id.rbFreeform -> StudioOverlayView.RoiMode.FREEFORM
                else -> StudioOverlayView.RoiMode.RECTANGLE
            }
        }

        overlayRoi.onDrawListener = { screenBounds ->
            val imgTopLeft = mapScreenToImage(screenBounds.left, screenBounds.top)
            val imgBottomRight = mapScreenToImage(screenBounds.right, screenBounds.bottom)

            if (imgTopLeft != null && imgBottomRight != null) {
                val rectX = imgTopLeft[0].toInt()
                val rectY = imgTopLeft[1].toInt()
                val rectW = (imgBottomRight[0] - imgTopLeft[0]).toInt()
                val rectH = (imgBottomRight[1] - imgTopLeft[1]).toInt()

                tvHud.text = "ROI: $rectW x $rectH px  |  Pos: ($rectX, $rectY)"
            }
        }

        btnSaveRoi.setOnClickListener {
            if (!overlayRoi.hasValidRoi) {
                Toast.makeText(this, "Draw a shape first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val screenBounds = overlayRoi.getBoundingBox()
            val imgTopLeft = mapScreenToImage(screenBounds.left, screenBounds.top)
            val imgBottomRight = mapScreenToImage(screenBounds.right, screenBounds.bottom)

            if (imgTopLeft != null && imgBottomRight != null) {
                val rectX = max(0, imgTopLeft[0].toInt())
                val rectY = max(0, imgTopLeft[1].toInt())
                val rectW = min(realImageWidth - rectX, (imgBottomRight[0] - imgTopLeft[0]).toInt())
                val rectH = min(realImageHeight - rectY, (imgBottomRight[1] - imgTopLeft[1]).toInt())

                val resultIntent = Intent()
                resultIntent.putExtra("ROI_X", rectX)
                resultIntent.putExtra("ROI_Y", rectY)
                resultIntent.putExtra("ROI_W", rectW)
                resultIntent.putExtra("ROI_H", rectH)
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            }
        }

        btnCancelRoi.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun mapScreenToImage(touchX: Float, touchY: Float): FloatArray? {
        val drawable = imgRoiCanvas.drawable ?: return null
        val inv = Matrix()
        imgRoiCanvas.imageMatrix.invert(inv)

        val pts = floatArrayOf(touchX, touchY)
        inv.mapPoints(pts)

        val scaleX = realImageWidth.toFloat() / drawable.intrinsicWidth.toFloat()
        val scaleY = realImageHeight.toFloat() / drawable.intrinsicHeight.toFloat()

        val finalX = (pts[0] * scaleX).coerceIn(0f, realImageWidth.toFloat())
        val finalY = (pts[1] * scaleY).coerceIn(0f, realImageHeight.toFloat())

        return floatArrayOf(finalX, finalY)
    }
}