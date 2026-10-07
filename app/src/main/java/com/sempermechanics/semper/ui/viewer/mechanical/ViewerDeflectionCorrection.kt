package com.sempermechanics.semper.ui.viewer.mechanical

import android.content.DialogInterface
import android.os.Bundle
import android.widget.EditText
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.sempermechanics.semper.R
import com.sempermechanics.semper.report.BeamDeflection
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import java.util.Locale

/**
 * Bending Results' Adjust deflection: the session's deflection scale and
 * bias, δ = scale × δ measured + bias, typed after the run. Apply hands it to
 * [ResultViewerActivity.applyDeflectionCorrection], which redraws the Results
 * and saves it; Reset goes back to the camera's δ (1 and 0).
 */
object ViewerDeflectionCorrection {

    private const val STATE_CORRECTION = "DEFLECTION_CORRECTION"

    fun show(host: ResultViewerActivity) {
        val current = BeamDeflection.Correction.of(host.stressModel)
        val view = host.layoutInflater.inflate(R.layout.dialog_deflection_correction, null)
        val scaleBox = view.findViewById<TextInputLayout>(R.id.tilDeflectionScale)
        val biasBox = view.findViewById<TextInputLayout>(R.id.tilDeflectionBias)
        val scaleText = view.findViewById<EditText>(R.id.etDeflectionScale)
        val biasText = view.findViewById<EditText>(R.id.etDeflectionBias)
        scaleText.setText(format(current.scale))
        biasText.setText(format(current.biasMm))

        val dialog = MaterialAlertDialogBuilder(host)
            .setTitle(R.string.deflection_adjust_title)
            .setView(view)
            .setPositiveButton(R.string.apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.deflection_reset) { _, _ ->
                host.applyDeflectionCorrection(BeamDeflection.Correction.NONE)
            }
            .create()
        // Set on show so a bad entry keeps the dialog open with the box marked.
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val scale = BeamDeflection.Correction.parseScale(scaleText.text.toString())
                val bias = BeamDeflection.Correction.parseBias(biasText.text.toString())
                scaleBox.error = if (scale == null) host.getString(R.string.deflection_scale_invalid) else null
                biasBox.error = if (bias == null) host.getString(R.string.deflection_bias_invalid) else null
                if (scale != null && bias != null) {
                    host.applyDeflectionCorrection(BeamDeflection.Correction.of(scale, bias))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    fun save(outState: Bundle, correction: BeamDeflection.Correction) {
        outState.putFloatArray(STATE_CORRECTION, floatArrayOf(correction.scale, correction.biasMm))
    }

    fun restore(savedState: Bundle): BeamDeflection.Correction? =
        savedState.getFloatArray(STATE_CORRECTION)
            ?.takeIf { it.size == 2 }
            ?.let { BeamDeflection.Correction.of(it[0], it[1]) }

    /** Up to four decimals, trailing zeros dropped: 1, 1.05, -0.12. */
    private fun format(value: Float): String =
        String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
}
