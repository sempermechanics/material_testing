package com.indicvision.semper.ui.analysis

import android.app.Activity
import android.content.Intent
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.BeamEdgeTaps
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.viewer.TouchImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bending's scale and load point: the student taps the beam's top edge and
 * then its bottom edge, right under the loading nose, on the reference photo.
 * Each mark is drawn as full-length crosshair lines, so the horizontal one can
 * be laid along the edge. The bottom mark is held on the top mark's vertical
 * line ([BeamTapPlacement]). The thickness they measured over the tapped
 * pixels is the photo's mm per pixel, and the midpoint is where
 * [com.indicvision.semper.report.BeamDeflection] reads δ. Pinch and pan to
 * zoom in; a tap after both are placed moves the mark nearer in height. Returns
 * [DicKeys.BEAM_EDGE_TAPS] in true reference pixels.
 */
class BeamEdgeTapActivity : AppCompatActivity() {

    private lateinit var photo: TouchImageView
    private lateinit var overlay: BeamEdgeTapOverlay
    private lateinit var tvReadout: TextView
    private lateinit var tvStep: TextView
    private lateinit var btnSave: MaterialButton

    private var thicknessMm = 0f
    private var imageWidth = 0
    private var imageHeight = 0
    private var marks = BeamTapPlacement.Marks()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_beam_edge_tap)
        photo = findViewById(R.id.imgBeamPhoto)
        overlay = findViewById(R.id.overlayBeamTaps)
        tvReadout = findViewById(R.id.tvBeamReadout)
        tvStep = findViewById(R.id.tvBeamStep)
        btnSave = findViewById(R.id.btnBeamSave)
        Insets.padTop(findViewById(R.id.headerChrome))
        Insets.padBottom(findViewById(R.id.bottomToolbar))

        thicknessMm = intent.getFloatExtra(DicKeys.BEAM_THICKNESS_MM, 0f)
        imageWidth = intent.getIntExtra(DicKeys.IMAGE_WIDTH, 0)
        imageHeight = intent.getIntExtra(DicKeys.IMAGE_HEIGHT, 0)
        val saved = savedInstanceState?.getFloatArray(DicKeys.BEAM_EDGE_TAPS)
            ?: intent.getFloatArrayExtra(DicKeys.BEAM_EDGE_TAPS)
        restore(saved)

        findViewById<MaterialButton>(R.id.btnBeamCancel).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.btnBeamReset).setOnClickListener {
            marks = BeamTapPlacement.Marks()
            refresh()
        }
        btnSave.setOnClickListener { saveAndFinish() }
        photo.onMatrixChangedListener = { overlay.imageToView = photo.getZoomMatrix() }
        photo.onTapListener = ::onTap
        refresh()
        loadPhoto()
    }

    private fun loadPhoto() {
        val file = intent.getStringExtra(DicKeys.IMAGE_FILE_PATH)?.let(::File)?.takeIf(File::exists)
        if (file == null) {
            Toast.makeText(this, R.string.roi_image_not_found, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Twice the screen, so a zoomed-in edge is still sharp enough to tap.
        val screen = resources.displayMetrics
        val maxEdge = PREVIEW_OVERSAMPLE * max(screen.widthPixels, screen.heightPixels)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            val longEdge = max(imageWidth, imageHeight).takeIf { it > 0 } ?: maxEdge
            val loaded = ReferencePreviewLoader.load(
                ReferencePreviewLoader.Request(bytes, imageWidth, imageHeight, min(longEdge, maxEdge)),
            )
            val bitmap = loaded.bitmap
            if (bitmap == null) {
                Toast.makeText(this@BeamEdgeTapActivity, R.string.roi_decode_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            imageWidth = loaded.width
            imageHeight = loaded.height
            photo.setImageBitmap(bitmap)
            photo.setTrueImageDimensions(imageWidth, imageHeight)
            overlay.imageToView = photo.getZoomMatrix()
        }
    }

    private fun onTap(viewX: Float, viewY: Float) {
        if (imageWidth <= 0 || imageHeight <= 0) return
        val inverse = Matrix()
        if (!photo.getZoomMatrix().invert(inverse)) return
        val pts = floatArrayOf(viewX, viewY)
        inverse.mapPoints(pts)
        marks = BeamTapPlacement.place(
            marks,
            pts[0].coerceIn(0f, imageWidth.toFloat()),
            pts[1].coerceIn(0f, imageHeight.toFloat()),
        )
        refresh()
    }

    private fun taps(): BeamEdgeTaps? {
        val t = marks.top
        val b = marks.bottom
        return if (t != null && b != null) BeamEdgeTaps(t.x, t.y, b.x, b.y) else null
    }

    private fun refresh() {
        overlay.top = marks.top?.let { PointF(it.x, it.y) }
        overlay.bottom = marks.bottom?.let { PointF(it.x, it.y) }
        val taps = taps()
        tvStep.setText(
            when {
                marks.top == null -> R.string.beam_tap_step_top
                marks.bottom == null -> R.string.beam_tap_step_bottom
                else -> R.string.beam_tap_step_done
            },
        )
        btnSave.isEnabled = taps?.isSet == true
        tvReadout.text = when {
            taps == null -> getString(R.string.beam_tap_readout_empty, format(thicknessMm))
            !taps.isSet -> getString(R.string.beam_tap_too_close)
            else -> readout(taps)
        }
    }

    private fun readout(taps: BeamEdgeTaps): String {
        val px = taps.thicknessPx
        val line = getString(
            R.string.beam_tap_readout_fmt,
            format(thicknessMm),
            px.roundToInt(),
            String.format(Locale.US, "%.4f", thicknessMm / px),
        )
        if (px >= BeamEdgeTaps.PRECISE_THICKNESS_PX) return line
        // One pixel of slip moves the scale, and so δ, by 1/px; E goes as 1/δ: ~100/px %.
        val slipPercent = (PERCENT / px).roundToInt()
        return line + "\n" + getString(R.string.beam_tap_precision_warn_fmt, px.roundToInt(), slipPercent)
    }

    private fun saveAndFinish() {
        val taps = taps()?.takeIf { it.isSet } ?: return
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(DicKeys.BEAM_EDGE_TAPS, taps.toArray()),
        )
        finish()
    }

    private fun restore(values: FloatArray?) {
        val taps = BeamEdgeTaps.fromArray(values)
        if (!taps.isSet) return
        marks = BeamTapPlacement.Marks(
            BeamTapPlacement.Mark(taps.topX, taps.topY),
            BeamTapPlacement.Mark(taps.bottomX, taps.bottomY),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        taps()?.let { outState.putFloatArray(DicKeys.BEAM_EDGE_TAPS, it.toArray()) }
    }

    private fun format(mm: Float): String =
        if (mm == mm.toLong().toFloat()) mm.toLong().toString() else String.format(Locale.US, "%.2f", mm)

    private companion object {
        const val PREVIEW_OVERSAMPLE = 2
        const val PERCENT = 100f
    }
}
