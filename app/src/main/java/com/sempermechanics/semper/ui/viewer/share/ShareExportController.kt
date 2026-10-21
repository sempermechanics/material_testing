package com.sempermechanics.semper.ui.viewer.share

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.dialog.DeterminateProgressDialog
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.transfer.TransferBannerController
import kotlinx.coroutines.launch
import java.io.File

/**
 * The viewer's side of [ShareExportJobs]: a progress dialog for each job the
 * user is watching, a banner entry for each job sent to the background, and
 * the result when one finishes.
 *
 * It lives and dies with the viewer, while the jobs live in the ViewModel: a
 * viewer recreated by a rotation puts the still-running jobs back on screen
 * (dialog or banner, as the user left them) and delivers their results.
 * Main thread only. Its dialogs, sheets and toasts go on [activity]; a job sent
 * to the background is a row in [banner].
 */
internal class ShareExportController(
    private val activity: AppCompatActivity,
    private val jobs: ShareExportJobs,
    private val banner: TransferBannerController,
) {

    private val dialogs = HashMap<String, DeterminateProgressDialog>()
    private val inBanner = HashSet<String>()

    /** Starts showing progress and results; once, from onCreate. */
    fun attach() {
        activity.lifecycleScope.launch {
            // Results wait for a started screen: a share sheet cannot open from
            // a stopped or half-recreated viewer.
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { jobs.running.collect(::render) }
                jobs.outcomes.collect(::deliver)
            }
        }
    }

    /** Closes this viewer's dialogs before its window goes; the jobs keep running. */
    fun detach() {
        dialogs.values.forEach { it.dismiss() }
        dialogs.clear()
    }

    /** Starts an export of [kind]; see [ShareExportJobs.start]. */
    fun start(
        kind: ShareKind,
        title: String,
        destUri: Uri?,
        direct: Boolean,
        produce: suspend (report: ExportReport) -> Pair<File, String>,
    ) {
        jobs.start(jobs.newId(kind.wire), title, destUri, direct, produce)
    }

    private fun render(running: Map<String, ShareExportJobs.Running>) {
        for (job in running.values) {
            if (job.background) showInBanner(job) else showInDialog(job)
        }
        dialogs.keys.filter { it !in running }.forEach { forget(it) }
        inBanner.filter { it !in running }.forEach { forget(it) }
    }

    private fun showInDialog(job: ShareExportJobs.Running) {
        val dialog = dialogs.getOrPut(job.id) {
            DeterminateProgressDialog(
                activity,
                job.title,
                onCancel = { jobs.cancel(job.id) },
                onBackground = { jobs.sendToBackground(job.id) },
            ).also { it.show() }
        }
        job.status?.let { dialog.update(job.percent, it) }
    }

    private fun showInBanner(job: ShareExportJobs.Running) {
        dialogs.remove(job.id)?.dismiss()
        if (inBanner.add(job.id)) {
            banner.upsert(
                TransferBannerController.Transfer(
                    id = job.id,
                    title = job.title,
                    percent = job.percent,
                    status = job.status.orEmpty(),
                    onCancel = { jobs.cancel(job.id) },
                ),
            )
        } else {
            banner.updateProgress(job.id, job.percent, job.status)
        }
    }

    /** Takes job [id] off the screen, dialog and banner both. */
    private fun forget(id: String) {
        dialogs.remove(id)?.dismiss()
        if (inBanner.remove(id)) banner.remove(id)
    }

    private fun deliver(outcome: ShareExportJobs.Outcome) {
        forget(outcome.id)
        when (outcome) {
            is ShareExportJobs.Outcome.Ready ->
                if (outcome.direct) {
                    activity.startActivity(SendToSheet.shareChooser(activity, outcome.file, outcome.mime))
                } else {
                    // Our own "Send to" sheet: Save to Files (folder icon) + Share. Owning
                    // the rows is the only reliable way to show a folder icon — the system
                    // share sheet ignores custom icons on EXTRA_INITIAL_INTENTS on Android 12+.
                    SendToSheet.show(activity, outcome.file, outcome.mime)
                }
            is ShareExportJobs.Outcome.Saved ->
                Feedback.toast(activity, if (outcome.ok) R.string.save_success else R.string.save_failed, long = true)
            is ShareExportJobs.Outcome.Failed ->
                CrispToast.show(activity, activity.getString(R.string.share_failed), long = true)
            is ShareExportJobs.Outcome.Cancelled ->
                CrispToast.show(activity, activity.getString(R.string.share_cancelled))
        }
    }
}
