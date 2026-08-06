package com.indicvision.semper.ui.analysis

import android.view.View
import android.widget.EditText
import com.google.android.material.slider.Slider
import com.indicvision.semper.R
import com.indicvision.semper.data.ParamClipboard

/**
 * Wires the analysis settings sheet listeners (param fields, info buttons,
 * slider label sync). Reset / paste / recommendation logic stays in the Activity
 * so it can touch ViewModel + sweep state without putting disk or network on Main.
 */
@Suppress("LongParameterList") // sheet owns a fixed set of named views + callbacks
class AnalysisSettingsSheetHelper(
    private val root: View,
    private val subset: Slider,
    private val step: Slider,
    private val strain: Slider,
    private val subsetValue: EditText,
    private val stepValue: EditText,
    private val strainValue: EditText,
    private val renderParamField: (EditText, Int) -> Unit,
    private val bindParamField: (EditText, Slider, (() -> Unit)?) -> Unit,
    private val showInfo: (titleRes: Int, bodyRes: Int) -> Unit,
    private val onSubsetUserModified: () -> Unit,
    private val onSubsetRecommendationRefresh: () -> Unit,
    private val onAdvancedReset: () -> Unit,
    private val onPasteParams: () -> Unit,
) {
    fun bind() {
        val updateLabels = {
            renderParamField(subsetValue, subset.value.toInt())
            renderParamField(stepValue, step.value.toInt())
            renderParamField(strainValue, strain.value.toInt())
        }
        updateLabels()

        bindParamField(subsetValue, subset) { onSubsetUserModified() }
        bindParamField(stepValue, step, null)
        bindParamField(strainValue, strain, null)

        root.findViewById<View>(R.id.btnAdvancedReset).setOnClickListener { onAdvancedReset() }
        val pasteChip = root.findViewById<View>(R.id.btnPasteParams)
        pasteChip.setOnClickListener { onPasteParams() }
        refreshPasteVisibility()
        root.findViewById<View>(R.id.btnSubsetInfo)
            .setOnClickListener { showInfo(R.string.subset_size, R.string.info_subset) }
        root.findViewById<View>(R.id.btnStepInfo)
            .setOnClickListener { showInfo(R.string.step_size_density, R.string.info_step) }
        root.findViewById<View>(R.id.btnStrainInfo)
            .setOnClickListener { showInfo(R.string.strain_window, R.string.info_strain_window) }

        subset.addOnChangeListener { _, _, fromUser ->
            if (fromUser) {
                onSubsetUserModified()
                onSubsetRecommendationRefresh()
            }
            updateLabels()
        }
        step.addOnChangeListener { _, _, _ -> updateLabels() }
        strain.addOnChangeListener { _, _, _ -> updateLabels() }
    }

    /** Show Paste only when the sweep clipboard has values. */
    fun refreshPasteVisibility() {
        val pasteChip = root.findViewById<View>(R.id.btnPasteParams)
        pasteChip.visibility =
            if (ParamClipboard.peek(root.context) != null) View.VISIBLE else View.GONE
    }
}
