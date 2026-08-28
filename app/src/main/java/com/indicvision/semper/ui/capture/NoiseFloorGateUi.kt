package com.indicvision.semper.ui.capture

import android.app.Activity
import android.content.DialogInterface
import android.graphics.Rect
import android.graphics.RectF
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R
import com.indicvision.semper.data.CaptureNoiseFloor
import com.indicvision.semper.data.NoiseFloorText
import com.indicvision.semper.ui.analysis.NoiseFloorStats
import com.indicvision.semper.ui.common.FaqRedirect
import timber.log.Timber

/**
 * The screen-side half of the noise-floor gate: carries the ROI across from the
 * speckle check, runs the burst, and turns its verdict into what the user sees.
 *
 * Split out of the capture activity because it is a self-contained decision with
 * its own carried state — the ROI, the subset, the measured floor and whether
 * the user overrode it — and folding that into the activity would leave the
 * gate's logic interleaved with camera setup, preview transforms and recording.
 *
 * The activity keeps everything that needs the camera; this keeps everything
 * that needs the verdict.
 */
internal class NoiseFloorGateUi(
    private val activity: Activity,
    /** Take the test shot again; the same path the speckle check's retry uses. */
    private val onRetry: () -> Unit,
    /** The burst produced nothing, which is the existing test-shot failure. */
    private val onBurstFailed: () -> Unit,
    /** The user chose to record past a failing floor: re-enable the start button. */
    private val onProceed: () -> Unit,
    /** Measured per-frame cost, for the rate ladder. */
    private val onFrameCost: (Long) -> Unit,
) {

    /**
     * The contrast ROI as fractions of the test shot, and the subset measured in
     * it, carried from the speckle check to the burst.
     *
     * Fractions rather than pixels: the burst is captured at the run's own
     * resolution, not the vendor camera app's, so a pixel rectangle would land
     * somewhere else entirely.
     */
    private var roiNorm: RectF? = null
    private var subset = 0
    private var sourceWidth = 0

    /** Which way round the test shot was, so the burst can check it still is. */
    private var sourceLandscape = false

    /** Guards the one collapsed pipeline warning against repeating per retry. */
    private var warned = false

    /** The measured floor, kept so the run carries the number it was taken at. */
    var floor: NoiseFloorGate.Result? = null
        private set

    /** True when the user chose to record past a failing floor. */
    var overridden = false
        private set

    /**
     * The measurement in the form the session, the report and the CSV keep, or
     * null when no burst produced one.
     *
     * Deliberately not the [NoiseFloorGate.Result] itself: that carries the
     * pacing measurement and the raw pair samples, which belong to this screen
     * and to nothing downstream of it.
     */
    fun measured(): CaptureNoiseFloor? {
        val result = floor ?: return null
        return CaptureNoiseFloor(
            microstrain = result.verdict.floorMicrostrain,
            vsgPx = result.vsgPx,
            sigmaPx = result.verdict.sigmaPx,
            frames = result.verdict.frameCount,
            exceeded = result.verdict.floorExceeded,
            overridden = overridden,
            noiseVariance = result.verdict.noiseVariance,
            noiseCorrelation = result.verdict.noiseCorrelation,
        )
    }

    /** Called with the speckle check's result, which is where the ROI comes from. */
    fun onSpeckleChecked(roi: Rect, imageWidth: Int, imageHeight: Int, subsetSize: Int) {
        roiNorm = NoiseFloorGate.normalize(roi, imageWidth, imageHeight)
        subset = subsetSize
        sourceWidth = imageWidth
        sourceLandscape = imageWidth > imageHeight
        Timber.i(
            "noise floor roi: test shot %dx%d roi=%s subset=%d",
            imageWidth,
            imageHeight,
            roi.toShortString(),
            subsetSize,
        )
    }

    /**
     * Take the static burst, calibrate pacing from it, and let it stop the run
     * if the setup cannot resolve the strain about to be applied.
     *
     * This does two jobs that used to be one throwaway frame. The first burst
     * frame is the warm-up: timing the PNG encode alone misses the camera round
     * trip (request → sensor → YUV frame → write) that dominates on real
     * hardware, so pacing has to come from a real still through the exact path
     * the sequence uses. The rest of the burst is the noise-floor measurement —
     * free, because nothing is loaded yet and these are already frames of a
     * static scene.
     *
     * @return false when the caller should stop; a dialog is already up.
     */
    @Suppress("ReturnCount") // no ROI, no burst, then the pass and refuse verdicts
    suspend fun run(session: LockedCameraSession, planWidth: Int, planHeight: Int): Boolean {
        val roi = roiNorm
        if (roi == null || subset <= 0) {
            // Nothing to measure against. Not a failure worth stopping for —
            // the run is no worse off than it was before this check existed.
            Timber.w("noise floor: no contrast ROI; skipping the check")
            return true
        }
        val scaled = NoiseFloorGate.rescaleSubset(subset, sourceWidth, planWidth)
        val result = runCatching {
            NoiseFloorGate.measure(activity, session, roi, scaled, sourceLandscape)
        }.onFailure { Timber.w(it, "noise floor: burst failed") }.getOrNull()

        if (result == null) {
            onBurstFailed()
            return false
        }
        // Feeds the setup screen's frame-count offers next time, so they reflect
        // this device rather than Camera2's unrelated JPEG stall.
        CaptureCalibration.record(activity, planWidth, planHeight, result.firstFrameMs)
        onFrameCost(result.firstFrameMs)
        // Frames that could not be correlated with each other at all are not a
        // floor of "unknown" to pass through quietly — they are the burst
        // producing nothing usable, same as a burst that crashed outright.
        if (result.verdict.outcome == NoiseFloorStats.Outcome.INSUFFICIENT) {
            onBurstFailed()
            return false
        }
        floor = result
        // A high floor no longer stops the run, but it must not pass unseen
        // either: it is shown, and the same verdict is stamped on the session so
        // the report and the CSV carry it.
        if (!result.verdict.blocking && !result.verdict.floorExceeded) {
            warnAboutPipeline(session)
            announceFloor(result)
            return true
        }
        showVerdict(session, result)
        return false
    }

    /**
     * The one sentence a clean pass still owes the user: what this run can
     * resolve, stated once as a fact rather than held back until it becomes
     * a problem. [showVerdict] carries the same wording for a failing floor
     * as a dialog to act on; a passing floor only needs a toast to note.
     */
    private fun announceFloor(result: NoiseFloorGate.Result) {
        val label = NoiseFloorText.floorLabel(result.verdict.floorMicrostrain)
        val message = activity.getString(R.string.capture_noise_floor_title, label) +
            " " + activity.getString(R.string.capture_noise_floor_body)
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }

    /**
     * The one line the user sees about the pipeline, whichever way the gate
     * went.
     *
     * Two findings compete for it and only one is shown. Smoothing wins when it
     * was measured, because it is the worse news and the more surprising: a
     * refused key is a setting the phone declined, while smoothing is a setting
     * the phone *accepted* and then ignored — nothing in the read-back can
     * catch it, so if this line does not say it, nothing will.
     */
    fun warnAboutPipeline(session: LockedCameraSession) {
        if (floor?.let { measuredFloor -> denoiseWarned(measuredFloor) } == true) return
        warnAboutRefusedSettings(session)
    }

    /**
     * Shows the smoothing warning if the burst found smoothing, returning
     * whether it did. Guarded like the refusal warning so a retry does not
     * repeat it.
     */
    private fun denoiseWarned(result: NoiseFloorGate.Result): Boolean {
        val correlation = result.verdict.noiseCorrelation
        val smoothed = correlation.isFinite() && correlation > CaptureNoiseFloor.DENOISE_CORRELATION
        if (smoothed && !warned) {
            warned = true
            Timber.w("pipeline: frames are smoothed, neighbour correlation %.3f", correlation)
            FaqRedirect.snackbar(
                activity,
                activity.getString(R.string.capture_denoise_warn),
                R.string.url_faq_imaging_pipeline,
            )
        }
        return smoothed
    }

    /**
     * The one warning about settings this phone would not hold.
     *
     * Shown after the read-back rather than from the capability lists, because a
     * HAL can accept a key and ignore it — and a warning for a setting that was
     * in fact honoured is as much a defect as a missing one. Collapsed to a
     * single line naming the costliest refusal: a LEGACY device refuses half a
     * dozen at once, and six snackbars is noise, not information.
     */
    fun warnAboutRefusedSettings(session: LockedCameraSession) {
        if (warned) return
        val shortfall = session.ispShortfall
        val worst = CaptureIspWarning.headline(shortfall) ?: return
        warned = true
        val effect = activity.getString(CaptureIspWarning.effectOf(worst))
        val message = if (shortfall.size > 1) {
            activity.getString(R.string.capture_isp_warn_more, effect, shortfall.size - 1)
        } else {
            effect
        }
        Timber.w("isp shortfall: %s", shortfall.joinToString())
        FaqRedirect.snackbar(activity, message, R.string.url_faq_imaging_pipeline)
    }

    /**
     * The floor verdict: a warning the user acts on, not a door they are held
     * behind.
     *
     * Recording twenty minutes of a loaded specimen that cannot resolve the
     * strain being applied wastes the specimen, not just the time, and a
     * specimen is often not repeatable — so the warning is written to be
     * unmissable. But this is a proxy, and the user knows things it does not:
     * that this is a shakedown, that the expected strain is 50 me and a 2 me
     * floor is fine, that the fixture cannot be re-mounted. So a floor above the
     * limit always leaves **Record anyway** as the primary action, and the
     * honesty is bought by recording the number rather than by refusing.
     *
     * Two outcomes still stop to ask — a burst that would not settle, and one
     * that drifted. There the measurement failed to measure itself, so retrying
     * costs seconds and buys a number worth having; the floor beside it cannot
     * be trusted either. Those keep **Retry test shot** as the primary action,
     * with the override on the other side.
     */
    private fun showVerdict(session: LockedCameraSession, result: NoiseFloorGate.Result) {
        val verdict = result.verdict
        val refused = session.ispShortfall.size
        val label = NoiseFloorText.floorLabel(verdict.floorMicrostrain)

        val dialog = MaterialAlertDialogBuilder(activity)
        describe(dialog, verdict, refused, label)
        val proceed = { _: DialogInterface, _: Int ->
            // Recorded, so an export months later still says the run was
            // captured below the usable floor and by how much.
            Timber.w("noise floor override: recording at %s (%s)", label, verdict.outcome)
            overridden = true
            warnAboutPipeline(session)
            onProceed()
        }
        val retry = { _: DialogInterface, _: Int -> onRetry() }
        // Whichever action is the recommended one sits on the right, where the
        // eye lands; the other is one deliberate tap away on the left.
        if (verdict.blocking) {
            dialog.setPositiveButton(R.string.capture_retry_test_shot, retry)
            dialog.setNegativeButton(R.string.capture_record_anyway, proceed)
        } else {
            dialog.setPositiveButton(R.string.capture_record_anyway, proceed)
            dialog.setNegativeButton(R.string.capture_retry_test_shot, retry)
        }
        dialog.setNeutralButton(R.string.action_why) { _, _ ->
            FaqRedirect.confirm(activity, R.string.url_faq_noise_floor)
        }
        if (!activity.isFinishing && !activity.isDestroyed) dialog.show()
    }

    /**
     * Title and body for the verdict. Each outcome gets its own, because they
     * call for different actions: more light, a steadier rig, or waiting.
     */
    private fun describe(
        dialog: MaterialAlertDialogBuilder,
        verdict: NoiseFloorStats.Verdict,
        refused: Int,
        label: String,
    ) {
        when {
            verdict.outcome == NoiseFloorStats.Outcome.NOT_SETTLING ->
                dialog
                    .setTitle(R.string.capture_noise_unsettled_title)
                    .setMessage(R.string.capture_noise_unsettled_body)

            verdict.outcome == NoiseFloorStats.Outcome.DRIFTING ->
                dialog
                    .setTitle(R.string.capture_noise_drift_title)
                    .setMessage(R.string.capture_noise_drift_body)

            // Two independent signals pointing the same way — a floor over the
            // limit *and* settings the phone would not hold — earn a plainer
            // sentence than either does alone, since the refusals are part of
            // why the floor is where it is.
            verdict.floorExceeded && refused >= REFUSALS_WORTH_NAMING ->
                dialog
                    .setTitle(R.string.capture_noise_erroneous_title)
                    .setMessage(
                        activity.getString(
                            R.string.capture_noise_erroneous_refused_body,
                            label,
                            refused,
                        ),
                    )

            verdict.floorExceeded ->
                dialog
                    .setTitle(R.string.capture_noise_erroneous_title)
                    .setMessage(
                        activity.getString(R.string.capture_noise_erroneous_body, label),
                    )

            else ->
                dialog
                    .setTitle(activity.getString(R.string.capture_noise_floor_title, label))
                    .setMessage(R.string.capture_noise_floor_body)
        }
    }

    private companion object {
        /**
         * Refusals worth naming alongside a high floor.
         *
         * Below this the refusals are incidental — one fallback on a mid-range
         * phone explains nothing about the floor, and naming it would make the
         * warning longer for no gain. At three or more the pipeline is not
         * frozen in any meaningful sense and the two findings belong in one
         * sentence.
         */
        const val REFUSALS_WORTH_NAMING = 3
    }
}
