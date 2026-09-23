package com.indicvision.semper.ui.analysis

import android.view.View
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.indicvision.semper.R
import java.util.Locale

/**
 * The load card's time-offset row, shown only when a video's frames were
 * matched to the load log by time: how many seconds after the reference
 * frame the machine started logging. A student who pressed record before
 * starting the machine enters the gap here instead of trimming the video.
 */
class LoadSyncRow(
    root: View,
    private val viewModel: AnalysisViewModel,
    private val onChanged: () -> Unit,
) {
    private val row: View = root.findViewById(R.id.rowLoadSync)
    private val field: EditText = row.findViewById(R.id.etLoadLogStart)

    init {
        if (viewModel.loadLogStartS != 0f) field.setText(format(viewModel.loadLogStartS))
        field.doAfterTextChanged { text ->
            val seconds = text?.toString()?.trim()?.toFloatOrNull() ?: 0f
            if (seconds != viewModel.loadLogStartS) {
                viewModel.loadLogStartS = seconds
                viewModel.refreshMachineLoads()
                onChanged()
            }
        }
    }

    fun refresh(timeMatched: Boolean) {
        row.isVisible = timeMatched
    }

    private fun format(seconds: Float): String = String.format(Locale.US, "%.1f", seconds)
}
