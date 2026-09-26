package com.indicvision.semper.ui.analysis

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.indicvision.semper.R
import com.indicvision.semper.data.BeamEdgeTaps
import com.indicvision.semper.data.TestType
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bending's load-point row on the load card, shown as **Beam height → Set**
 * (the taps span the beam's height, t, under the load): whether the beam's edges are
 * tapped on the reference, the scale they give, and the button that opens
 * [BeamEdgeTapActivity] (launched by the Activity through [onMark]). The
 * taps replace the strain-axis toggle for bending: the beam runs across the
 * top-to-bottom line, so the axis follows from them.
 */
class LoadPointRow(
    root: View,
    private val viewModel: AnalysisViewModel,
    private val onMark: () -> Unit,
    private val onChanged: () -> Unit,
) {
    private val row: View = root.findViewById(R.id.rowLoadPoint)
    private val tvMeta: TextView = row.findViewById(R.id.tvLoadPointMeta)
    private val button: MaterialButton = row.findViewById(R.id.btnLoadPoint)

    init {
        val bending = viewModel.testType == TestType.BENDING
        row.isVisible = bending
        root.findViewById<View>(R.id.rowLoadAxis).isVisible = !bending
        button.setOnClickListener { onMark() }
        refresh()
    }

    /** Adopts taps the editor returned: the scale, the probe, and the beam's axis. */
    fun onPicked(taps: BeamEdgeTaps) {
        if (!taps.isSet) return
        viewModel.geometry = viewModel.geometry.copy(loadPoint = taps)
        // Edges one above the other: the beam lies along x.
        viewModel.loadAxisX = abs(taps.bottomY - taps.topY) >= abs(taps.bottomX - taps.topX)
        refresh()
        onChanged()
    }

    fun refresh() {
        val taps = viewModel.geometry.loadPoint
        val thickness = viewModel.geometry.thicknessMm
        button.setText(if (taps.isSet) R.string.load_point_remark else R.string.load_point_mark)
        tvMeta.text = if (taps.isSet && thickness > 0f) {
            row.context.getString(
                R.string.load_point_meta_fmt,
                taps.thicknessPx.roundToInt(),
                String.format(Locale.US, "%.4f", thickness / taps.thicknessPx),
            )
        } else {
            row.context.getString(R.string.load_point_unset)
        }
    }
}
