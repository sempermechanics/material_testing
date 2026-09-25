package com.indicvision.semper.ui.analysis

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.indicvision.semper.R
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.data.TestType
import java.util.Locale

/**
 * The bending dimension rows of the load card. Each row is one
 * `row_specimen_dimension` include; only the rows the test's stress model uses
 * are shown, and every edit writes straight back to
 * [AnalysisViewModel.geometry] so a recreated Activity redraws from it.
 */
class SpecimenGeometryFields(
    root: View,
    private val viewModel: AnalysisViewModel,
    private val onChanged: () -> Unit,
) {
    private class Row(
        val view: View,
        val labelRes: Int,
        val read: (SpecimenGeometry) -> Float,
        val write: (SpecimenGeometry, Float) -> SpecimenGeometry,
    )

    private val bending = listOf(
        Row(root.findViewById(R.id.rowSpan), R.string.load_span, { it.spanMm }) { g, v -> g.copy(spanMm = v) },
        Row(root.findViewById(R.id.rowWidth), R.string.load_width, { it.widthMm }) { g, v -> g.copy(widthMm = v) },
        Row(root.findViewById(R.id.rowThickness), R.string.load_thickness, { it.thicknessMm }) { g, v ->
            g.copy(thicknessMm = v)
        },
    )

    init {
        val shown = when (viewModel.testType) {
            TestType.BENDING -> bending
            TestType.TENSILE, TestType.DIC_2D -> emptyList()
        }
        bending.forEach { it.view.isVisible = it in shown }
        shown.forEach(::bind)
    }

    private fun bind(row: Row) {
        row.view.findViewById<TextView>(R.id.tvDimensionLabel).setText(row.labelRes)
        val field = row.view.findViewById<EditText>(R.id.etDimension)
        val current = row.read(viewModel.geometry)
        if (current > 0f) field.setText(format(current))
        field.doAfterTextChanged { text ->
            val value = text?.toString()?.trim()?.toFloatOrNull()?.takeIf { it > 0f } ?: 0f
            viewModel.geometry = row.write(viewModel.geometry, value)
            onChanged()
        }
    }

    private fun format(value: Float): String =
        if (value == value.toLong().toFloat()) value.toLong().toString() else String.format(Locale.US, "%.3f", value)
}
