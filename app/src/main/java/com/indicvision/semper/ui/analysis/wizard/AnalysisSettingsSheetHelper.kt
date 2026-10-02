package com.indicvision.semper.ui.analysis.wizard

import android.app.Activity
import android.view.View
import android.widget.EditText
import com.google.android.material.slider.Slider
import com.indicvision.semper.R
import com.indicvision.semper.data.prefs.ParamClipboard
import com.indicvision.semper.databinding.WizardStepSettingsContentBinding
import com.indicvision.semper.ui.analysis.recommend.StrainWindowText
import com.indicvision.semper.ui.analysis.sweep.VsgStudy
import com.indicvision.semper.ui.common.bindInfo
import com.indicvision.semper.ui.common.commitOnDone
import com.indicvision.semper.ui.common.showUnlessEditing
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Wires the analysis settings sheet listeners (param fields, info buttons,
 * slider label sync). Reset / paste / recommendation logic stays in the Activity
 * so it can touch ViewModel + sweep state without putting disk or network on Main.
 */
@Suppress("LongParameterList") // the sheet's callbacks, each a different Activity action
class AnalysisSettingsSheetHelper(
    private val activity: Activity,
    private val settings: WizardStepSettingsContentBinding,
    private val bindParamField: (EditText, Slider, (() -> Unit)?) -> Unit,
    private val onSubsetUserModified: () -> Unit,
    private val onSubsetRecommendationRefresh: () -> Unit,
    private val onAdvancedReset: () -> Unit,
    private val onPasteParams: () -> Unit,
    private val onParamsChanged: () -> Unit = {},
) {
    private val subset = settings.etSubsetSize
    private val step = settings.etStepSize
    private val overlap = settings.etOverlap
    private val strain = settings.etStrainWindow
    private val subsetValue = settings.tvSubsetValue
    private val stepValue = settings.tvStepValue
    private val overlapValue = settings.tvOverlapValue
    private val strainValue = settings.tvStrainValue

    /** The VSG in px the window in points gives at the current step. */
    private val strainVsg = settings.tvStrainVsg

    /** True while code is writing the overlap/step pair, not the user. */
    private var bindingOverlap = false

    fun bind() {
        val updateLabels = {
            subsetValue.showUnlessEditing(subset.value.toInt().toString())
            stepValue.showUnlessEditing(step.value.toInt().toString())
            strainValue.showUnlessEditing(strain.value.toInt().toString())
            strainVsg.text = StrainWindowText.vsgAt(strainVsg.context, strain.value.toInt(), step.value.toInt())
        }
        applyStepRangeForSubset()
        syncOverlapFromStep()
        updateLabels()

        bindParamField(subsetValue, subset) {
            onSubsetUserModified()
            onParamsChanged()
        }
        bindParamField(stepValue, step) { onParamsChanged() }
        bindParamField(strainValue, strain) { onParamsChanged() }
        bindOverlapField()

        settings.btnAdvancedReset.setOnClickListener { onAdvancedReset() }
        settings.btnPasteParams.setOnClickListener { onPasteParams() }
        refreshPasteVisibility()
        settings.btnSubsetInfo.bindInfo(activity, R.string.subset_size, R.string.info_subset)
        settings.btnStepInfo.bindInfo(activity, R.string.step_size_density, R.string.info_step)
        settings.btnOverlapInfo.bindInfo(activity, R.string.subset_overlap, R.string.info_subset_overlap)
        settings.btnStrainInfo.bindInfo(activity, R.string.strain_window, R.string.info_strain_window)

        subset.addOnChangeListener { _, _, fromUser ->
            if (!bindingOverlap) applyStepRangeForSubset()
            if (fromUser) {
                onSubsetUserModified()
                onSubsetRecommendationRefresh()
                onParamsChanged()
            }
            if (!bindingOverlap) syncOverlapFromStep()
            updateLabels()
        }
        step.addOnChangeListener { _, _, fromUser ->
            if (fromUser) onParamsChanged()
            if (!bindingOverlap) syncOverlapFromStep()
            updateLabels()
        }
        overlap.addOnChangeListener { _, value, fromUser ->
            if (bindingOverlap) return@addOnChangeListener
            if (fromUser) {
                applyOverlapToStep(overlapFromSlider(value))
                onParamsChanged()
            }
            renderOverlapField()
        }
        strain.addOnChangeListener { _, _, fromUser ->
            if (fromUser) onParamsChanged()
            updateLabels()
        }
    }

    /** Recompute overlap after Reset or Paste writes subset/step. */
    fun syncFromStep() {
        applyStepRangeForSubset()
        syncOverlapFromStep()
        stepValue.showUnlessEditing(step.value.toInt().toString())
        strainVsg.text = StrainWindowText.vsgAt(strainVsg.context, strain.value.toInt(), step.value.toInt())
    }

    /** Show Paste only when the sweep clipboard has values. */
    fun refreshPasteVisibility() {
        settings.btnPasteParams.visibility =
            if (ParamClipboard.peek(activity) != null) View.VISIBLE else View.GONE
    }

    private fun applyStepRangeForSubset() {
        val maxStep = VsgStudy.maxStepFor(subset.value.toInt()).toFloat()
        if (step.value > maxStep) step.value = maxStep
        if (step.valueTo != maxStep) step.valueTo = maxStep
    }

    private fun syncOverlapFromStep() {
        bindingOverlap = true
        overlap.value = overlapSliderValue(
            VsgStudy.overlapFor(subset.value.toInt(), step.value.toInt()),
        )
        renderOverlapField()
        bindingOverlap = false
    }

    private fun applyOverlapToStep(overlapVal: Double) {
        bindingOverlap = true
        applyStepRangeForSubset()
        step.value = VsgStudy.stepSizeFor(subset.value.toInt(), overlapVal).toFloat()
        overlap.value = overlapSliderValue(
            VsgStudy.overlapFor(subset.value.toInt(), step.value.toInt()),
        )
        renderOverlapField()
        stepValue.showUnlessEditing(step.value.toInt().toString())
        bindingOverlap = false
    }

    private fun bindOverlapField() {
        val commit = {
            val typed = overlapValue.text.toString().trim().replace(',', '.').toDoubleOrNull()
            val value = typed ?: overlap.value.toDouble()
            applyOverlapToStep(value)
            onParamsChanged()
        }
        overlapValue.commitOnDone(onDone = commit)
        overlapValue.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commit() }
    }

    private fun renderOverlapField() {
        overlapValue.showUnlessEditing(String.format(Locale.US, "%.2f", overlapFromSlider(overlap.value)))
    }

    /** The overlap slider counts hundredths. */
    private fun overlapSliderValue(raw: Double): Float {
        val hundredths = (VsgStudy.clampOverlap(raw) * HUNDREDTHS).roundToInt()
        return hundredths.coerceIn(
            (VsgStudy.MIN_OVERLAP * HUNDREDTHS).toInt(),
            (VsgStudy.MAX_OVERLAP * HUNDREDTHS).toInt(),
        ).toFloat()
    }

    private fun overlapFromSlider(sliderValue: Float): Double =
        VsgStudy.clampOverlap(sliderValue.toDouble() / HUNDREDTHS)

    private companion object {
        const val HUNDREDTHS = 100.0
    }
}
