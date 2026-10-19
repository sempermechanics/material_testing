package com.sempermechanics.semper.ui.analysis.rerun

import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderDirection
import com.sempermechanics.semper.ui.analysis.frames.FrameOrderMode
import com.sempermechanics.semper.ui.analysis.run.RunChrome
import com.sempermechanics.semper.ui.analysis.sweep.SweepFrame
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import com.sempermechanics.semper.ui.common.dialog.Feedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Home's Re-run, in the wizard: a saved analysis's inputs are read and its
 * photos copied ([RerunLoader]) with the import overlay up and its Cancel
 * armed, then put in [viewModel] as a new analysis ([applyRerun]). [onApplied]
 * shows them on the wizard's pages; it may suspend, as a preview decode does.
 * [checkReady] re-checks the wizard's buttons once the overlay is down.
 */
internal class RerunImportController(
    private val activity: AppCompatActivity,
    private val viewModel: AnalysisViewModel,
    private val chrome: RunChrome,
    private val onApplied: suspend (RerunInputs) -> Unit,
    private val checkReady: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null

    /**
     * Starts a new analysis from the one Home lists as [sessionId]: from its
     * kept inputs, or, given [picked], from its frames and the reference the
     * user picked again ([RerunLoader.loadWithReference]), which is deleted
     * once read.
     */
    @MainThread
    fun load(sessionId: String, picked: PickedReference? = null) {
        if (chrome.isBusy) return
        chrome.beginImport { job?.cancel() }
        chrome.overlay.processingStartTime = System.currentTimeMillis()
        chrome.overlay.show(
            title = activity.getString(R.string.analysis_importing_title),
            status = activity.getString(R.string.rerun_copying),
            showConvergence = false,
        )
        job = activity.lifecycleScope.launch {
            try {
                val loaded = withContext(io) { read(sessionId, picked) }
                if (loaded == null) {
                    Dialogs.info(activity, R.string.rerun_unavailable_title, R.string.rerun_photos_missing_body)
                } else {
                    viewModel.applyRerun(loaded)
                    onApplied(loaded.inputs)
                    if (loaded.maskDropped) Feedback.toast(activity, R.string.rerun_mask_not_saved, long = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "Could not copy the analysis's photos for a Re-run")
                Dialogs.info(activity, R.string.rerun_unavailable_title, R.string.rerun_copy_failed_body)
            } finally {
                job = null
                chrome.end()
                checkReady()
            }
        }
    }

    /** The Re-run of [sessionId], from its kept inputs or with [picked]; null when it cannot be had. */
    private suspend fun read(sessionId: String, picked: PickedReference?): LoadedRerun? = try {
        val record = SessionStore.get(activity, sessionId)
        val reference = picked?.file?.takeIf { it.isFile }?.readBytes()
        when {
            record == null -> null
            picked == null -> RerunLoader.load(File(record.sessionDir), activity.cacheDir)
            reference == null -> null
            else -> RerunLoader.loadWithReference(record, reference, picked.name, activity.cacheDir)
        }
    } finally {
        picked?.file?.delete()
    }

    /** Stops a copy in flight: the screen is going. */
    fun cancel() {
        job?.cancel()
    }
}

/**
 * [loaded] as the wizard's inputs, a new analysis: the reference, the ROI as
 * drawn and its mask, the frames in run order with their names, dates, sizes
 * and clip times, and for a sweep its mode, axes, overlap, line-cut axis and
 * frame. Nothing names the saved analysis it came from, so the next run is
 * saved as a new one. The sliders are views: `WizardParamFields.applyRerun`
 * sets them. Lands on the parameters page, or the sweep's.
 */
@MainThread
internal fun AnalysisViewModel.applyRerun(loaded: LoadedRerun) {
    val inputs = loaded.inputs
    val size = ImageSize(inputs.reference.width, inputs.reference.height)
    applyNewReference(loaded.reference, inputs.reference.name, size)
    hasCustomRoi = inputs.hasCustomRoi
    roi = if (inputs.hasCustomRoi) inputs.drawnRoi() else Roi.full(size)
    roiMaskBytes = loaded.mask
    deformedFrames = loaded.frames
    frameSizeError = null
    defFromVideo = inputs.fromVideo
    defVideoName = inputs.videoName
    defOrderMode = FrameOrderMode.entries.find { it.name == inputs.orderMode } ?: FrameOrderMode.NAME
    defOrderDirection = FrameOrderDirection.entries.find { it.name == inputs.orderDirection }
        ?: FrameOrderDirection.ASCENDING
    // The saved run's subset stands: the recommendation must not move it.
    subsetUserModified = true
    val sweep = inputs.sweep
    sweepMode = sweep != null
    if (sweep != null) {
        inputs.sweepRanges()?.let { sweepRanges = it }
        subsetOverlap = sweep.overlap
        lineCutHorizontal = sweep.lineCutHorizontal
        sweepFrame = SweepFrame.At(sweep.frameIndex)
    }
    step = if (sweep != null) WizardStep.SWEEP else WizardStep.SETTINGS
}
