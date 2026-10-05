package com.indicvision.semper.ui.viewer

import com.indicvision.semper.R
import com.indicvision.semper.data.TypedLoads
import com.indicvision.semper.data.loadOfFrame
import com.indicvision.semper.report.StressStrain
import java.util.Locale

/**
 * The Details sheet's mechanical rows for the frame on screen: the specimen's
 * dimensions, its load (kg for bending, N otherwise) and the stress it gives.
 * None on the summary, or when the frame has no load.
 */
object ViewerFrameRows {

    fun of(host: ResultViewerActivity): List<Pair<String, String>> {
        val loadN = host.loadsN.loadOfFrame(host.plannedFrameIndex(host.currentFrameIndex))
            ?.takeUnless { host.isShowingSummary }
            ?: return emptyList()
        val model = host.stressModel
        val stress = model.stressMPa(loadN)
        fun row(labelRes: Int, valueRes: Int, value: Float): Pair<String, String> =
            host.getString(labelRes) to host.getString(valueRes, fmt(value))
        return buildList {
            model.dimensions.forEach { (dimension, value) ->
                if (value > 0f) {
                    add(row(ViewerStressStrainHelper.dimensionLabelRes(dimension), unitRes(dimension), value))
                }
            }
            if (model is StressStrain.Model.Flexural) {
                add(row(R.string.settings_used_load_bending, R.string.settings_used_kg_fmt, TypedLoads.kg(loadN)))
            } else {
                add(row(R.string.settings_used_load, R.string.settings_used_n_fmt, loadN))
            }
            if (!stress.isNaN()) {
                add(row(ViewerStressStrainHelper.stressLabelRes(model), R.string.settings_used_mpa_fmt, stress))
            }
        }
    }

    private fun unitRes(dimension: StressStrain.Dimension): Int =
        if (dimension == StressStrain.Dimension.CROSS_SECTION) R.string.settings_used_mm2_fmt else R.string.settings_used_mm_fmt

    private fun fmt(value: Float): String = String.format(Locale.US, "%.3f", value).trimEnd('0').trimEnd('.')
}
