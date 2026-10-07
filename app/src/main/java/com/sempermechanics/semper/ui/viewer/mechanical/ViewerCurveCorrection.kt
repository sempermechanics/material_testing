package com.sempermechanics.semper.ui.viewer.mechanical

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import java.util.Locale

/**
 * Tensile Results' Adjust curve: the session's strain and stress scale and
 * bias ([CurveCorrection]), typed after the run to line the curve up with the
 * machine's own export. Apply hands it to
 * [ResultViewerActivity.applyCurveCorrection], which redraws the Results and
 * saves it; Reset goes back to the curve as measured (1 and 0).
 */
object ViewerCurveCorrection {

    private const val STATE_CORRECTION = "CURVE_CORRECTION"

    private class Box(val layout: TextInputLayout, val text: EditText, val scale: Boolean) {
        fun read(): Float? {
            val raw = text.text.toString()
            return if (scale) CurveCorrection.parseScale(raw) else CurveCorrection.parseBias(raw)
        }
    }

    fun show(host: ResultViewerActivity) {
        val current = host.curveCorrection
        val view = host.layoutInflater.inflate(R.layout.dialog_curve_correction, null)
        val boxes = listOf(
            box(view, R.id.tilStrainScale, R.id.etStrainScale, scale = true, current.strainScale),
            box(view, R.id.tilStrainBias, R.id.etStrainBias, scale = false, current.strainBiasMilli),
            box(view, R.id.tilStressScale, R.id.etStressScale, scale = true, current.stressScale),
            box(view, R.id.tilStressBias, R.id.etStressBias, scale = false, current.stressBiasMPa),
        )

        val dialog = MaterialAlertDialogBuilder(host)
            .setTitle(R.string.curve_adjust_title)
            .setView(view)
            .setPositiveButton(R.string.apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.curve_reset) { _, _ -> host.applyCurveCorrection(CurveCorrection.NONE) }
            .create()
        // Set on show so a bad entry keeps the dialog open with the box marked.
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val values = boxes.map { box ->
                    box.read().also { value ->
                        box.layout.error = when {
                            value != null -> null
                            box.scale -> host.getString(R.string.curve_scale_invalid)
                            else -> host.getString(R.string.curve_bias_invalid)
                        }
                    }
                }
                val read = values.filterNotNull()
                if (read.size == boxes.size) {
                    host.applyCurveCorrection(
                        CurveCorrection.fromArray(read.toFloatArray()),
                    )
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun box(view: View, layoutId: Int, textId: Int, scale: Boolean, value: Float): Box {
        val box = Box(view.findViewById(layoutId), view.findViewById(textId), scale)
        box.text.setText(format(value))
        return box
    }

    fun save(outState: Bundle, correction: CurveCorrection) {
        outState.putFloatArray(STATE_CORRECTION, correction.toArray())
    }

    fun restore(savedState: Bundle): CurveCorrection? =
        savedState.getFloatArray(STATE_CORRECTION)?.let(CurveCorrection::fromArray)

    /** Up to six decimals, trailing zeros dropped: 1, 1.05, -0.12. */
    private fun format(value: Float): String =
        String.format(Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
}
