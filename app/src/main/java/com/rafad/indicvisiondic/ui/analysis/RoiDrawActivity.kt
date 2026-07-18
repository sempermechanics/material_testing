package com.rafad.indicvisiondic.ui.analysis
import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.IndicVisionNativeLib
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.ui.common.Insets
import java.io.File
import kotlin.math.roundToInt

/**
 * Full-screen region-of-interest editor: draw rectangles or freehand masks
 * over the reference image; the resulting mask PNG limits which pixels the
 * engine tracks.
 */
class RoiDrawActivity : AppCompatActivity() {

    private lateinit var imgRoiCanvas: ImageView
    private lateinit var overlayRoi: StudioOverlayView
    private lateinit var tvHud: TextView
    private lateinit var rgDrawMode: MaterialButtonToggleGroup
    private lateinit var btnSaveRoi: MaterialButton
    private lateinit var btnCancelRoi: MaterialButton
    private lateinit var btnResetRoi: MaterialButton

    private var realImageWidth = 0
    private var realImageHeight = 0
    private lateinit var switchSubtractMode: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_roi_draw)

        imgRoiCanvas = findViewById(R.id.imgRoiCanvas)
        overlayRoi = findViewById(R.id.overlayRoi)
        tvHud = findViewById(R.id.tvHud)
        rgDrawMode = findViewById(R.id.rgDrawMode)
        btnSaveRoi = findViewById(R.id.btnSaveRoi)
        btnCancelRoi = findViewById(R.id.btnCancelRoi)
        btnResetRoi = findViewById(R.id.btnResetRoi)
        switchSubtractMode = findViewById(R.id.switchSubtractMode)

        Insets.padTop(tvHud)
        Insets.padBottom(findViewById(R.id.bottomToolbar))

        val imageFilePath = intent.getStringExtra(DicKeys.IMAGE_FILE_PATH)
        realImageWidth = intent.getIntExtra(DicKeys.IMAGE_WIDTH, 0)
        realImageHeight = intent.getIntExtra(DicKeys.IMAGE_HEIGHT, 0)

        overlayRoi.realImageWidth = realImageWidth
        overlayRoi.realImageHeight = realImageHeight

        if (imageFilePath != null) {
            val file = File(imageFilePath)
            if (file.exists()) {
                val bytes = file.readBytes()
                val screenWidth = resources.displayMetrics.widthPixels
                val bitmap = IndicVisionNativeLib.getPreviewFromBytes(bytes, screenWidth)

                if (bitmap == null) {
                    Toast.makeText(this, R.string.roi_decode_failed, Toast.LENGTH_LONG).show()
                } else {
                    imgRoiCanvas.setImageBitmap(bitmap)
                    imgRoiCanvas.post {
                        overlayRoi.imageView = imgRoiCanvas

                        if (savedInstanceState != null) {
                            val left = savedInstanceState.getFloat(DicKeys.ROI_L, -1f)
                            if (left != -1f) {
                                val top = savedInstanceState.getFloat(DicKeys.ROI_T)
                                val right = savedInstanceState.getFloat(DicKeys.ROI_R)
                                val bottom = savedInstanceState.getFloat(DicKeys.ROI_B)
                                overlayRoi.restoreRelativeRoi(RectF(left, top, right, bottom))
                            }
                        }
                    }
                }
            } else {
                Toast.makeText(this, R.string.roi_image_not_found, Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        rgDrawMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            overlayRoi.currentMode = when (checkedId) {
                R.id.rbSquare -> StudioOverlayView.RoiMode.SQUARE
                R.id.rbCircle -> StudioOverlayView.RoiMode.CIRCLE
                R.id.rbEllipse -> StudioOverlayView.RoiMode.ELLIPSE
                R.id.rbFreeform -> StudioOverlayView.RoiMode.FREEFORM
                else -> StudioOverlayView.RoiMode.RECTANGLE
            }
            tvHud.text = getString(R.string.roi_hud_mode_switched, overlayRoi.currentMode)
        }

        if (savedInstanceState != null) {
            rgDrawMode.check(savedInstanceState.getInt(DicKeys.DRAW_MODE, R.id.rbRect))
        }

        switchSubtractMode.setOnCheckedChangeListener { _, isChecked ->
            overlayRoi.isSubtractMode = isChecked
            tvHud.text = getString(
                if (isChecked) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_draw,
            )
        }

        btnCancelRoi.setOnClickListener { finish() }

        btnResetRoi.setOnClickListener {
            overlayRoi.reset()
            tvHud.text = getString(R.string.roi_hud_canvas_cleared)
        }

        overlayRoi.onRoiChangedListener = { roi ->
            if (roi.width() > 0 && roi.height() > 0) {
                tvHud.text = getString(
                    R.string.roi_hud_dimensions,
                    roi.width().roundToInt(),
                    roi.height().roundToInt(),
                    roi.left.roundToInt(),
                    roi.top.roundToInt(),
                )
            } else {
                tvHud.text = getString(R.string.roi_hud_select_tool)
            }
        }

        btnSaveRoi.setOnClickListener {
            val rectX: Int
            val rectY: Int
            val rectW: Int
            val rectH: Int
            val maskBytes: ByteArray

            if (!overlayRoi.hasValidRoi && overlayRoi.holes.isEmpty()) {
                rectX = 0
                rectY = 0
                rectW = realImageWidth
                rectH = realImageHeight
                maskBytes = ByteArray(realImageWidth * realImageHeight) { 255.toByte() }
                Toast.makeText(this, R.string.roi_full_image_selected, Toast.LENGTH_SHORT).show()
            } else {
                if (overlayRoi.hasValidRoi) {
                    val finalRoi = overlayRoi.getRelativeRoi()
                    rectX = finalRoi.left.toInt().coerceAtLeast(0)
                    rectY = finalRoi.top.toInt().coerceAtLeast(0)
                    rectW = finalRoi.width().toInt().coerceAtMost(realImageWidth - rectX)
                    rectH = finalRoi.height().toInt().coerceAtMost(realImageHeight - rectY)
                } else {
                    rectX = 0
                    rectY = 0
                    rectW = realImageWidth
                    rectH = realImageHeight
                }

                if (rectW <= 0 || rectH <= 0) {
                    Toast.makeText(this, R.string.roi_invalid_size, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                maskBytes = overlayRoi.generateMaskBytes()
            }

            val maskFile = File(cacheDir, "roi_mask_cache.bin")
            java.io.FileOutputStream(maskFile).use { it.write(maskBytes) }

            val resultIntent = Intent()
            resultIntent.putExtra(DicKeys.ROI_X, rectX)
            resultIntent.putExtra(DicKeys.ROI_Y, rectY)
            resultIntent.putExtra(DicKeys.ROI_W, rectW)
            resultIntent.putExtra(DicKeys.ROI_H, rectH)
            resultIntent.putExtra(DicKeys.MASK_FILE_PATH, maskFile.absolutePath)

            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(DicKeys.DRAW_MODE, rgDrawMode.checkedButtonId)

        if (overlayRoi.hasValidRoi && overlayRoi.currentMode != StudioOverlayView.RoiMode.FREEFORM) {
            val relativeRoi = overlayRoi.getRelativeRoi()
            outState.putFloat(DicKeys.ROI_L, relativeRoi.left)
            outState.putFloat(DicKeys.ROI_T, relativeRoi.top)
            outState.putFloat(DicKeys.ROI_R, relativeRoi.right)
            outState.putFloat(DicKeys.ROI_B, relativeRoi.bottom)
        }
    }
}
