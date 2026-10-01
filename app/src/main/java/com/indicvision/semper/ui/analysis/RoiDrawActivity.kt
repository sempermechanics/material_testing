// ROI drawing/editing: dense gesture hit-testing and canvas math read clearest
// as cohesive methods, so the structural rules are suppressed for this file.
@file:Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod")

@file:SuppressLint("SetTextI18n")

package com.indicvision.semper.ui.analysis

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.databinding.ActivityRoiDrawBinding
import com.indicvision.semper.field.ImageSize
import com.indicvision.semper.field.Roi
import com.indicvision.semper.field.fromImageRect
import com.indicvision.semper.field.getRoiEdges
import com.indicvision.semper.field.getRoiEditorImageSize
import com.indicvision.semper.field.putRoiEdges
import com.indicvision.semper.field.putRoiExtras
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.ui.analysis.roi.StudioOverlayMaskEncoder
import com.indicvision.semper.ui.analysis.roi.StudioOverlayView
import com.indicvision.semper.ui.analysis.wizard.ReferencePreviewLoader
import com.indicvision.semper.ui.common.Feedback
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.common.hideKeyboard
import com.indicvision.semper.ui.common.onButtonChecked
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen region-of-interest editor: draw or type a rectangular crop over
 * the reference image; optional erase punches exclude regions from the mask.
 */
@MainThread
class RoiDrawActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoiDrawBinding

    /** The reference's true size; the preview decode may correct the wizard's. */
    private var imageSize = ImageSize.UNKNOWN

    /** True while syncing manual fields from the overlay — skip apply-on-change loops. */
    private var syncingManualFields = false

    /** True from a Save until the editor finishes: the mask is still being written. */
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoiDrawBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Insets.padTop(binding.headerChrome)
        // The dock rises above the keyboard so the typed X/Y/W/H and Apply stay
        // reachable; the canvas shrinks and the overlay remaps the crop onto the
        // smaller image (StudioOverlayView.updateImageBounds).
        Insets.padBottomAboveIme(binding.bottomToolbar)

        val imageFilePath = intent.getStringExtra(DicKeys.IMAGE_FILE_PATH)
        setImageSize(intent.getRoiEditorImageSize())

        if (imageFilePath != null) {
            // Twice the screen, as the beam-edge editor, so a zoomed-in
            // crop edge still lands on visible speckle.
            val screen = resources.displayMetrics
            val maxEdge = PREVIEW_OVERSAMPLE * max(screen.widthPixels, screen.heightPixels)
            val longEdge = max(imageSize.width, imageSize.height).takeIf { it > 0 } ?: maxEdge

            lifecycleScope.launch {
                // The decoded reference: tens of megabytes for a RAW frame.
                val bytes = withContext(Dispatchers.IO) { readReference(File(imageFilePath)) }
                if (bytes == null) {
                    Feedback.toast(this@RoiDrawActivity, R.string.roi_image_not_found)
                    finish()
                    return@launch
                }
                val loaded = ReferencePreviewLoader.load(
                    ReferencePreviewLoader.Request(bytes, imageSize.width, imageSize.height, min(longEdge, maxEdge)),
                )
                setImageSize(ImageSize(loaded.width, loaded.height))
                val bitmap = loaded.bitmap

                if (bitmap == null) {
                    Feedback.toast(this@RoiDrawActivity, R.string.roi_decode_failed, long = true)
                } else {
                    binding.imgRoiCanvas.setImageBitmap(bitmap)
                    binding.imgRoiCanvas.post {
                        binding.overlayRoi.imageView = binding.imgRoiCanvas

                        savedInstanceState?.getRoiEdges()?.let { saved ->
                            binding.overlayRoi.restoreRelativeRoi(saved)
                            fillManualFields(binding.overlayRoi.getRelativeRoi())
                        }
                    }
                }
            }
        }

        binding.rgEditMode.onButtonChecked { checkedId ->
            val manual = checkedId == R.id.rbModeManual
            setEditMode(manual)
        }

        binding.rgCropErase.onButtonChecked { checkedId ->
            val erase = checkedId == R.id.rbErase
            binding.overlayRoi.isSubtractMode = erase
            binding.tvHud.text = getString(
                if (erase) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_crop,
            )
            syncManualFieldsForMode()
        }

        binding.rgDrawMode.onButtonChecked { checkedId ->
            binding.overlayRoi.currentMode = when (checkedId) {
                R.id.rbSquare -> StudioOverlayView.RoiMode.SQUARE
                else -> StudioOverlayView.RoiMode.RECTANGLE
            }
            binding.tvHud.text = getString(R.string.roi_hud_mode_switched, binding.overlayRoi.currentMode)
        }

        if (savedInstanceState != null) {
            val modeId = savedInstanceState.getInt(DicKeys.DRAW_MODE, R.id.rbRect)
            binding.rgDrawMode.check(if (modeId == R.id.rbSquare) R.id.rbSquare else R.id.rbRect)
            val erase = savedInstanceState.getBoolean(STATE_ERASE, false)
            binding.rgCropErase.check(if (erase) R.id.rbErase else R.id.rbCrop)
            binding.overlayRoi.isSubtractMode = erase
            val editManual = savedInstanceState.getBoolean(STATE_MANUAL, false)
            binding.rgEditMode.check(if (editManual) R.id.rbModeManual else R.id.rbModeDraw)
            setEditMode(editManual)
        } else {
            setEditMode(false)
            binding.tvHud.text = getString(R.string.roi_hud_zoom_hint)
        }

        binding.overlayRoi.onZoomChangedListener = { zoom ->
            binding.tvHud.text = if (zoom > 1f) {
                getString(R.string.roi_hud_zoom, zoom)
            } else {
                getString(R.string.roi_hud_zoom_fit)
            }
        }

        binding.btnCancelRoi.setOnClickListener { finish() }

        val clearCanvas = {
            binding.overlayRoi.reset()
            clearManualFields()
            binding.tvHud.text = getString(R.string.roi_hud_canvas_cleared)
        }
        binding.btnResetRoi.setOnClickListener { clearCanvas() }
        binding.btnResetManualRoi.setOnClickListener { clearCanvas() }

        binding.btnFullImageRoi.setOnClickListener { saveFullImageAndFinish() }

        binding.btnApplyManualRoi.setOnClickListener { applyManualFields() }

        binding.overlayRoi.onRoiChangedListener = { roi ->
            if (binding.rgCropErase.checkedButtonId == R.id.rbErase) {
                val hole = binding.overlayRoi.lastHoleRelative()
                if (hole.width() > 0 && hole.height() > 0) {
                    binding.tvHud.text = getString(
                        R.string.roi_hud_dimensions,
                        hole.width().roundToInt(),
                        hole.height().roundToInt(),
                        hole.left.roundToInt(),
                        hole.top.roundToInt(),
                    )
                    fillManualFields(hole)
                } else {
                    binding.tvHud.text = getString(R.string.roi_hud_mode_erase)
                }
            } else if (roi.width() > 0 && roi.height() > 0) {
                binding.tvHud.text = getString(
                    R.string.roi_hud_dimensions,
                    roi.width().roundToInt(),
                    roi.height().roundToInt(),
                    roi.left.roundToInt(),
                    roi.top.roundToInt(),
                )
                fillManualFields(roi)
            } else {
                binding.tvHud.text = getString(R.string.roi_hud_select_tool)
                if (!syncingManualFields) clearManualFields()
            }
        }

        binding.btnSaveRoi.setOnClickListener { saveAndFinish() }
    }

    /** The reference size, here and on the overlay that maps view px to image px. */
    private fun setImageSize(size: ImageSize) {
        imageSize = size
        binding.overlayRoi.realImageWidth = size.width
        binding.overlayRoi.realImageHeight = size.height
    }

    private fun setEditMode(manual: Boolean) {
        // INVISIBLE (not GONE) keeps bottomToolbar height stable so the
        // fitCenter image does not jump when switching Draw ↔ Manual.
        binding.drawTools.visibility = if (manual) View.INVISIBLE else View.VISIBLE
        binding.manualTools.visibility = if (manual) View.VISIBLE else View.INVISIBLE
        if (!manual) {
            hideSoftKeyboard()
        }
        // Crop/Erase stays visible and keeps its selection in both modes.
        val erase = binding.rgCropErase.checkedButtonId == R.id.rbErase
        binding.overlayRoi.isSubtractMode = erase
        if (manual) {
            syncManualFieldsForMode()
            binding.tvHud.text = getString(
                if (erase) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_manual,
            )
        } else {
            binding.tvHud.text = getString(
                if (erase) R.string.roi_hud_mode_erase else R.string.roi_hud_mode_crop,
            )
        }
    }

    private fun hideSoftKeyboard() {
        val focus = currentFocus ?: return
        focus.hideKeyboard()
        focus.clearFocus()
    }

    /** Prefill manual fields from the main crop or the last erase rect. */
    private fun syncManualFieldsForMode() {
        if (binding.rgCropErase.checkedButtonId == R.id.rbErase) {
            val hole = binding.overlayRoi.lastHoleRelative()
            if (hole.width() > 0f && hole.height() > 0f) {
                fillManualFields(hole)
            } else {
                clearManualFields()
            }
        } else {
            val roi = binding.overlayRoi.getRelativeRoi()
            if (roi.width() > 0f && roi.height() > 0f) {
                fillManualFields(roi)
            } else {
                clearManualFields()
            }
        }
    }

    private fun fillManualFields(roi: RectF) {
        if (roi.width() <= 0f || roi.height() <= 0f) return
        syncingManualFields = true
        binding.etRoiX.setText(roi.left.roundToInt().toString())
        binding.etRoiY.setText(roi.top.roundToInt().toString())
        binding.etRoiW.setText(roi.width().roundToInt().toString())
        binding.etRoiH.setText(roi.height().roundToInt().toString())
        syncingManualFields = false
    }

    private fun clearManualFields() {
        syncingManualFields = true
        binding.etRoiX.text = null
        binding.etRoiY.text = null
        binding.etRoiW.text = null
        binding.etRoiH.text = null
        syncingManualFields = false
    }

    private fun applyManualFields() {
        val x = binding.etRoiX.text?.toString()?.toIntOrNull()
        val y = binding.etRoiY.text?.toString()?.toIntOrNull()
        val w = binding.etRoiW.text?.toString()?.toIntOrNull()
        val h = binding.etRoiH.text?.toString()?.toIntOrNull()
        if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0) {
            Feedback.toast(this, R.string.roi_invalid_size)
            return
        }
        val erase = binding.rgCropErase.checkedButtonId == R.id.rbErase
        val ok = if (erase) {
            binding.overlayRoi.applyImageHole(x, y, w, h)
        } else {
            binding.overlayRoi.applyImageRoi(x, y, w, h)
        }
        if (!ok) {
            Feedback.toast(this, R.string.roi_invalid_size)
        }
    }

    private fun saveFullImageAndFinish() {
        binding.overlayRoi.reset()
        clearManualFields()
        saveAndFinish()
    }

    /**
     * Returns the ROI and its mask to the wizard. The rect is read here, on
     * Main; the mask (one byte per reference pixel, tens of megabytes on a
     * modern sensor) is built on Default and written on IO, so Save no longer
     * freezes the editor for the length of both. Further taps are ignored
     * until it is done.
     */
    private fun saveAndFinish() {
        if (saving) return
        val overlay = binding.overlayRoi
        val roi: Roi
        val buildMask: () -> ByteArray

        if (!overlay.hasValidRoi && overlay.holes.isEmpty()) {
            roi = Roi.full(imageSize)
            val pixels = imageSize.width * imageSize.height
            buildMask = { ByteArray(pixels) { 255.toByte() } }
            Feedback.toast(this, R.string.roi_full_image_selected)
        } else {
            roi = if (overlay.hasValidRoi) {
                Roi.fromImageRect(overlay.getRelativeRoi(), imageSize)
            } else {
                Roi.full(imageSize)
            }

            if (roi.w <= 0 || roi.h <= 0) {
                Feedback.toast(this, R.string.roi_invalid_size)
                return
            }
            val input = overlay.maskInput()
            buildMask = { StudioOverlayMaskEncoder.encode(input) }
        }

        saving = true
        val maskFile = File(cacheDir, CacheJanitor.ROI_MASK_CACHE)
        lifecycleScope.launch {
            val maskBytes = withContext(Dispatchers.Default) { buildMask() }
            val written = withContext(Dispatchers.IO) { writeMask(maskFile, maskBytes) }
            if (!written) {
                saving = false
                Feedback.toast(this@RoiDrawActivity, R.string.failed_save_temp_file)
                return@launch
            }

            val resultIntent = Intent()
                .putRoiExtras(roi)
                .putExtra(DicKeys.MASK_FILE_PATH, maskFile.absolutePath)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(DicKeys.DRAW_MODE, binding.rgDrawMode.checkedButtonId)
        outState.putBoolean(STATE_MANUAL, binding.rgEditMode.checkedButtonId == R.id.rbModeManual)
        outState.putBoolean(STATE_ERASE, binding.rgCropErase.checkedButtonId == R.id.rbErase)

        if (binding.overlayRoi.hasValidRoi) {
            outState.putRoiEdges(binding.overlayRoi.getRelativeRoi())
        }
    }

    private companion object {
        const val STATE_MANUAL = "roi_edit_manual"
        const val STATE_ERASE = "roi_edit_erase"
        const val PREVIEW_OVERSAMPLE = 2
    }
}

/** The reference the wizard staged for the ROI editor, or null when it is gone. */
@WorkerThread
private fun readReference(file: File): ByteArray? = try {
    file.takeIf(File::exists)?.readBytes()
} catch (e: IOException) {
    Timber.e(e, "Could not read the ROI reference")
    null
}

/** Writes the ROI mask the wizard reads back; false when it could not. */
@WorkerThread
private fun writeMask(file: File, bytes: ByteArray): Boolean = try {
    FileOutputStream(file).use { it.write(bytes) }
    true
} catch (e: IOException) {
    Timber.e(e, "Could not write the ROI mask")
    false
}
