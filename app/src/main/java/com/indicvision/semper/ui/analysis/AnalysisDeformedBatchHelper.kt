@file:Suppress("TooGenericExceptionCaught", "LongMethod", "LongParameterList", "MagicNumber")

package com.indicvision.semper.ui.analysis

import android.net.Uri
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.DicSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Deformed-frame batch import extracted from [StaticAnalysisActivity].
 * Caps to [DicSettings.maxFrames], caches via [FrameImportHelper], then
 * applies ViewModel state on the main thread.
 */
object AnalysisDeformedBatchHelper {

    fun handle(
        activity: AppCompatActivity,
        viewModel: AnalysisViewModel,
        rawUris: List<Uri>,
        cacheDir: File,
        displayName: (Uri) -> String,
        tvResult: TextView,
        onApplied: () -> Unit,
    ) {
        val cap = DicSettings.maxFrames(activity)
        val capped = if (rawUris.size > cap) {
            Toast.makeText(
                activity,
                activity.resources.getQuantityString(R.plurals.frames_capped_fmt, cap, cap),
                Toast.LENGTH_LONG,
            ).show()
            FrameImportHelper.cappedUris(rawUris, cap)
        } else {
            rawUris
        }
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    tvResult.setText(R.string.analysis_caching_images)
                }

                val meta = FrameOrderHelper.loadMeta(activity, capped, displayName)
                val uris = meta.map { it.uri }
                val datesByIndex = meta.map { it.dateMs }

                val batch = FrameImportHelper.importDeformedUris(
                    context = activity,
                    uris = uris,
                    cacheDir = cacheDir,
                    displayName = displayName,
                )
                val frameDates = batch?.filePaths?.map { path ->
                    val name = File(path).name
                    val idx = name.take(4).toIntOrNull()
                    if (idx != null && idx in datesByIndex.indices) datesByIndex[idx] else Long.MAX_VALUE
                }

                withContext(Dispatchers.Main) {
                    viewModel.clearPreviousResults()
                    viewModel.defOrderMode = FrameOrderMode.PICKER
                    viewModel.defOrderDirection = FrameOrderDirection.ASCENDING
                    if (batch != null) {
                        viewModel.defFilePaths = batch.filePaths
                        viewModel.defOriginalNames = batch.originalNames
                        viewModel.defFrameSizes = batch.frameSizes
                        viewModel.defFrameDates = frameDates.orEmpty()
                        viewModel.defFromVideo = batch.fromVideo
                    } else {
                        viewModel.defFilePaths = emptyList()
                        viewModel.defOriginalNames = emptyList()
                        viewModel.defFrameSizes = emptyMap()
                        viewModel.defFrameDates = emptyList()
                        viewModel.defFromVideo = false
                    }
                    tvResult.text = ""
                    onApplied()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling batch")
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.error_loading_images, e.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
