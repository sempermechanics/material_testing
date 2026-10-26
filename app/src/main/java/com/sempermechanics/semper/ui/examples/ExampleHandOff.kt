package com.sempermechanics.semper.ui.examples

import android.content.Context
import android.content.Intent
import com.sempermechanics.semper.data.examples.ExampleAssetProvider
import com.sempermechanics.semper.data.examples.ExampleManifest
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.rerun.LabRerun

/**
 * Try it: the wizard opened on a bundled set as if its files had been picked
 * (ADR-031). The photos and the load log are the provider's URIs, in order;
 * the test, its dimensions and the ROI ride the Intent the way a Re-run's do
 * ([LabRerun]). Bending's taps are left for the student to make.
 */
internal object ExampleHandOff {

    fun intent(context: Context, set: ExampleManifest): Intent {
        val type = requireNotNull(set.testType) { "Example ${set.id} has an unknown test type" }
        fun uri(file: String) = ExampleAssetProvider.uriFor(context, set.id, file).toString()
        val intent = Intent(context, StaticAnalysisActivity::class.java)
            .putExtra(IntentKeys.TEST_TYPE, type.wireName)
            .putExtra(IntentKeys.EXAMPLE_ID, set.id)
            .putExtra(IntentKeys.EXAMPLE_ROI, set.roi.toIntArray())
            .putExtra(IntentKeys.EXAMPLE_IMAGE_SIZE, set.imageSize.toIntArray())
            .putExtra(IntentKeys.PICKED_REF_URI, uri(set.reference))
            .putStringArrayListExtra(IntentKeys.PICKED_DEF_URIS, ArrayList(set.deformed.map(::uri)))
        val specimen = set.specimen
        if (type.hasMachineLoad && specimen != null) {
            LabRerun(
                testType = type,
                crossSectionMm2 = specimen.crossSectionMm2,
                loadAxisX = specimen.loadAxisX,
                geometry = specimen.geometry,
            ).putOn(intent)
        }
        set.loadLog?.let { intent.putExtra(IntentKeys.PICKED_LOAD_CSV_URI, uri(it.file)) }
        return intent
    }
}
