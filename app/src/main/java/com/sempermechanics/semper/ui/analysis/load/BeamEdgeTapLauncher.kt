package com.sempermechanics.semper.ui.analysis.load

import android.app.Activity
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.field.ImageSizeExtras
import com.sempermechanics.semper.navigation.DicKeys
import com.sempermechanics.semper.ui.analysis.BeamEdgeTapActivity
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Bending: opens [BeamEdgeTapActivity] on the reference to tap the beam's top
 * and bottom edges, and hands the taps it returns to [onPicked]. It needs the
 * photo and the thickness first, since the taps are scaled by it.
 *
 * Construct it in `onCreate`: it registers its result launcher.
 */
class BeamEdgeTapLauncher(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val onPicked: (BeamEdgeTaps) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val taps = result.data?.getFloatArrayExtra(DicKeys.BEAM_EDGE_TAPS)
            if (result.resultCode == Activity.RESULT_OK) onPicked(BeamEdgeTaps.fromArray(taps))
        }

    /** Opens the tap editor on the reference, or says what is missing first. */
    fun open() {
        val bytes = viewModel.refBytes
        val thickness = viewModel.geometry.thicknessMm
        when {
            bytes == null -> Feedback.toast(activity, R.string.load_image_first)
            thickness <= 0f -> Feedback.toast(activity, R.string.load_point_need_thickness, long = true)
            else -> openEditor(bytes, thickness)
        }
    }

    /**
     * Hands the reference to the editor through the same cache file the ROI
     * studio uses, written on [io]: a RAW reference is tens of megabytes.
     */
    private fun openEditor(bytes: ByteArray, thicknessMm: Float) {
        val tempFile = File(activity.cacheDir, CacheJanitor.TEMP_ROI_REF)
        activity.lifecycleScope.launch {
            val written = withContext(io) {
                try {
                    tempFile.writeBytes(bytes)
                    true
                } catch (e: IOException) {
                    Timber.e(e, "Failed to write temp beam-tap reference file")
                    false
                }
            }
            if (!written) {
                Feedback.toast(activity, R.string.failed_save_temp_file)
                return@launch
            }
            val intent = Intent(activity, BeamEdgeTapActivity::class.java)
                .putExtra(DicKeys.IMAGE_FILE_PATH, tempFile.absolutePath)
                .putExtra(DicKeys.BEAM_THICKNESS_MM, thicknessMm)
                .putExtra(DicKeys.BEAM_EDGE_TAPS, viewModel.geometry.loadPoint.toArray())
            ImageSizeExtras.ROI_EDITOR.put(intent, viewModel.refSize)
            launcher.launch(intent)
        }
    }
}
