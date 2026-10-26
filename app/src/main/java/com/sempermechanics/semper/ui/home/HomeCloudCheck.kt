package com.sempermechanics.semper.ui.home

import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.ui.common.ConflatedRefresh
import com.sempermechanics.semper.ui.common.dialog.Feedback

/**
 * Home's cloud check, one at a time: a burst of [request]s runs it at most
 * once more, deep if any asked for deep. It is not cancelled mid-call, and it
 * starts after the list read that was in flight when it began.
 *
 * @param showsCloudState false for a demo account, which is told nothing about backups.
 * @param onBackupsListed the check saved a fresh listing of the account's backups.
 * @param stopSpinner the pull-to-refresh spinner is done.
 */
@MainThread
internal class HomeCloudCheck(
    private val activity: AppCompatActivity,
    private val viewModel: HomeViewModel,
    private val quotaCard: HomeQuotaCard,
    private val showsCloudState: () -> Boolean,
    private val onBackupsListed: () -> Unit,
    private val stopSpinner: () -> Unit,
) {
    private val queue: ConflatedRefresh<Boolean> by lazy {
        ConflatedRefresh<Boolean>(activity.lifecycleScope, merge = { a, b -> a || b }) { deep ->
            var completed = false
            try {
                viewModel.awaitListRead()
                reconcile(deep)
                completed = true
            } finally {
                // The spinner tracks the cloud check, not the local list read —
                // that's the part worth waiting for. After a check that ended
                // normally it stays while a pull-to-refresh waits its turn; a
                // check that threw or was cancelled takes the queue with it.
                if (!completed || !queue.hasPending) stopSpinner()
            }
        }
    }

    /**
     * @param deep verify blobs really exist in Drive (pull-to-refresh) rather
     *   than trusting the backend index (cheap resume check).
     */
    fun request(deep: Boolean) = queue.request(deep)

    /**
     * Ask the backend what is actually backed up and repair any drift — a
     * session whose cloud copy was deleted stops claiming "Synced" and is
     * re-queued for upload.
     *
     * Offline and "not configured" stay silent (that's normal for an
     * offline-first app), but a real backend fault is surfaced: otherwise the
     * badges quietly go stale and the user trusts a backup that isn't there.
     */
    private suspend fun reconcile(deep: Boolean) {
        when (val outcome = CloudSync.reconcile(activity, deep = deep)) {
            is CloudSync.Outcome.Ok -> {
                // Record the account's quota; newly at the cap → open the
                // persistent "email support" screen.
                quotaCard.recordReconciled(outcome.quotaUsed)
                // This check saved a fresh listing of the account's backups.
                onBackupsListed()
                if (outcome.repaired > 0) {
                    // The rows changed underneath us — show the corrected state.
                    viewModel.refreshList()
                    if (!showsCloudState()) return
                    val repaired = outcome.repaired
                    val text = activity.resources.getQuantityString(R.plurals.cloud_resync_fmt, repaired, repaired)
                    Feedback.toast(activity, text, long = true)
                }
            }
            is CloudSync.Outcome.Failed -> if (showsCloudState()) {
                val text = activity.getString(outcome.message)
                Feedback.toast(activity, text, long = true)
            }
            // Normal for an offline-first app — don't nag. Skipped = checked
            // recently (reconcile is throttled to protect the Firestore budget).
            CloudSync.Outcome.Offline, CloudSync.Outcome.Disabled, CloudSync.Outcome.Skipped -> Unit
        }
    }
}
