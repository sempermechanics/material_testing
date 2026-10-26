package com.sempermechanics.semper.ui.analysis

import android.app.Activity
import android.content.Intent
import android.text.TextUtils
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.load.AnalysisLoadCard
import com.sempermechanics.semper.ui.analysis.rerun.LabRerun
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.editLab
import com.sempermechanics.semper.ui.analysis.wizard.lab
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/*
 * The wizard's lab test on [StaticAnalysisActivity]: the test it opens on, the
 * machine-load card (building it), and the bending load-point editor it opens.
 * The launchers and the card itself are [WizardLabHooks], built with the
 * Activity (result launchers register at construction); the steps are here,
 * as extensions.
 */

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

/**
 * The lab test the wizard opens on, then its load card ([setupLoadCard]). The
 * test type stays on the Intent (not consumed): a recreation after process
 * death re-reads it, and the view model is authoritative once set. On a
 * [firstOpen] from Home's Re-run of a lab test, its dimensions and taps go in
 * before the load card reads them (TD-280); so do a bundled example's
 * dimensions (Try it, ADR-031). On a first open it sets [strainWindow] to the
 * test's default; the slider keeps its own value across recreation.
 *
 * @return that Re-run's lab test, whose typed masses go onto the frames once
 *   they are copied ([LabRerun.applyLoadsTo]); null otherwise, an example included
 */
internal fun StaticAnalysisActivity.openLabTest(firstOpen: Boolean, strainWindow: Slider): LabRerun? {
    TestType.fromWire(intent.getStringExtra(IntentKeys.TEST_TYPE))?.let { type ->
        viewModel.editLab { it.copy(testType = type) }
    }
    val isRerun = intent.hasExtra(IntentKeys.RERUN_SESSION_ID)
    val lab = if (firstOpen && (isRerun || intent.hasExtra(IntentKeys.EXAMPLE_ID))) {
        LabRerun.from(intent)?.also { it.applyTo(viewModel) }
    } else {
        null
    }
    setupLoadCard()
    if (firstOpen) strainWindow.value = viewModel.lab.testType.defaultStrainWindow.toFloat()
    return lab.takeIf { isRerun }
}

/**
 * The machine-load card exists only for tests with a load log (tensile and
 * bending; plain 2D DIC has none). It sits in a ViewStub so a type without
 * one never pays for its views, and so the host layout stays under lint's
 * TooManyViews cap.
 */
private fun StaticAnalysisActivity.setupLoadCard() {
    if (!viewModel.lab.testType.hasMachineLoad) return
    val root = binding.stubLoadCard.inflate()
    labHooks.loadCard = AnalysisLoadCard(
        activity = this,
        viewModel = viewModel,
        root = root,
        pickers = AnalysisLoadCard.Pickers(
            loadCsv = { labHooks.pickLoadCsv.launch(AnalysisLoadCard.CSV_MIME_TYPES) },
            loadPoint = ::openLoadPointEditor,
        ),
        onChanged = ::checkReady,
        confirmOpenFaq = ::confirmOpenFaq,
    )
    if (viewModel.lab.testType == TestType.BENDING) bindBendingStrainInfo()
}

/**
 * Bending's strain window starts wider (`TestType.defaultStrainWindow`), so
 * its ⓘ adds why to Semper's text. Runs after the settings page bound the
 * button (`WizardParamFields.bind`), and replaces that listener.
 */
private fun StaticAnalysisActivity.bindBendingStrainInfo() {
    findViewById<View>(R.id.btnStrainInfo).setOnClickListener {
        val body = TextUtils.concat(
            getText(R.string.info_strain_window),
            " ",
            getText(R.string.info_strain_window_bending),
        )
        Dialogs.info(this, getText(R.string.strain_window), body)
    }
}

/** Bending: tap the beam's edges on the reference; needs the photo and the thickness first. */
private fun StaticAnalysisActivity.openLoadPointEditor() {
    val bytes = viewModel.inputs.value.refBytes
    val thickness = viewModel.lab.geometry.thicknessMm
    when {
        bytes == null -> Feedback.toast(this, R.string.load_image_first)
        thickness <= 0f -> Feedback.toast(this, R.string.load_point_need_thickness, long = true)
        else -> openBeamEdgeTaps(bytes, thickness)
    }
}

/**
 * Hands the reference to [BeamEdgeTapActivity] through a cache file, as the
 * ROI studio gets it. The copy runs on [Dispatchers.IO]: the reference is
 * tens of megabytes for a RAW frame.
 */
private fun StaticAnalysisActivity.openBeamEdgeTaps(bytes: ByteArray, thicknessMm: Float) {
    val tempFile = File(cacheDir, CacheJanitor.TEMP_ROI_REF)
    lifecycleScope.launch {
        val written = withContext(Dispatchers.IO) {
            try {
                tempFile.writeBytes(bytes)
                true
            } catch (e: IOException) {
                Timber.e(e, "Failed to write temp beam-tap reference file")
                false
            }
        }
        if (!written) {
            Feedback.toast(this@openBeamEdgeTaps, R.string.failed_save_temp_file)
            return@launch
        }
        val intent = Intent(this@openBeamEdgeTaps, BeamEdgeTapActivity::class.java)
            .putExtra(IntentKeys.IMAGE_FILE_PATH, tempFile.absolutePath)
            .putExtra(IntentKeys.IMAGE_WIDTH, viewModel.inputs.value.refSize.width)
            .putExtra(IntentKeys.IMAGE_HEIGHT, viewModel.inputs.value.refSize.height)
            .putExtra(IntentKeys.BEAM_THICKNESS_MM, thicknessMm)
            .putExtra(IntentKeys.BEAM_EDGE_TAPS, viewModel.lab.geometry.loadPoint.toArray())
        labHooks.pickLoadPoint.launch(intent)
    }
}
