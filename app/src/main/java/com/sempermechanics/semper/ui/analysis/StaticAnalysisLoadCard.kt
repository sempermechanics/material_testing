package com.sempermechanics.semper.ui.analysis

import android.content.Intent
import android.text.TextUtils
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.load.AnalysisLoadCard
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/*
 * The wizard's machine-load card on [StaticAnalysisActivity]: building it, and
 * the bending load-point editor it opens. The launchers and the card itself stay
 * on the Activity (result launchers register at construction); the steps are
 * here, as extensions.
 */

/**
 * The machine-load card exists only for tests with a load log (tensile and
 * bending; plain 2D DIC has none). It sits in a ViewStub so a type without
 * one never pays for its views, and so the host layout stays under lint's
 * TooManyViews cap.
 */
internal fun StaticAnalysisActivity.setupLoadCard() {
    if (!viewModel.testType.hasMachineLoad) return
    val root = binding.stubLoadCard.inflate()
    loadCard = AnalysisLoadCard(
        activity = this,
        viewModel = viewModel,
        root = root,
        pickers = AnalysisLoadCard.Pickers(
            loadCsv = { pickLoadCsv.launch(AnalysisLoadCard.CSV_MIME_TYPES) },
            loadPoint = ::openLoadPointEditor,
        ),
        onChanged = ::checkReady,
        confirmOpenFaq = ::confirmOpenFaq,
    )
    if (viewModel.testType == TestType.BENDING) bindBendingStrainInfo()
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
    val bytes = viewModel.refBytes
    val thickness = viewModel.geometry.thicknessMm
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
            .putExtra(IntentKeys.IMAGE_WIDTH, viewModel.realRefWidth)
            .putExtra(IntentKeys.IMAGE_HEIGHT, viewModel.realRefHeight)
            .putExtra(IntentKeys.BEAM_THICKNESS_MM, thicknessMm)
            .putExtra(IntentKeys.BEAM_EDGE_TAPS, viewModel.geometry.loadPoint.toArray())
        pickLoadPoint.launch(intent)
    }
}
