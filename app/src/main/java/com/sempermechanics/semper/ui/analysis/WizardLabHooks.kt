package com.sempermechanics.semper.ui.analysis

import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.load.AnalysisLoadCard
import com.sempermechanics.semper.ui.analysis.rerun.LabRerun
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel

/**
 * The lab test's state on [StaticAnalysisActivity]: its load card, only
 * inflated for tests that take a load per frame ([setupLoadCard]); the two
 * result launchers the card opens; and a Home Re-run's lab test until its
 * photos are in.
 */
internal class WizardLabHooks(activity: StaticAnalysisActivity) {
    var loadCard: AnalysisLoadCard? = null

    /** A Home Re-run's lab test until its photos are in ([applyRerunLoads]); null otherwise. */
    var rerun: LabRerun? = null

    val pickLoadCsv = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadCard?.onCsvPicked(uri)
    }
    val pickLoadPoint = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val taps = it.data?.getFloatArrayExtra(IntentKeys.BEAM_EDGE_TAPS)
        if (it.resultCode == Activity.RESULT_OK) loadCard?.loadPoint?.onPicked(BeamEdgeTaps.fromArray(taps))
    }

    /** Typed masses onto the copied frames; step 1 when a load log must be imported again. */
    fun applyRerunLoads(viewModel: AnalysisViewModel) {
        rerun?.applyLoadsTo(viewModel)
        rerun = null
    }
}
