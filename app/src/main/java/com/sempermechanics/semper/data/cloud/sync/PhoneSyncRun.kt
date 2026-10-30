package com.sempermechanics.semper.data.cloud.sync

import android.content.Context
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.work.WorkManager
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.data.cloud.restore.CloudRestore
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.prefs.PrefFiles.CloudListing
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * One run of the sync from the account's other phones ([PhoneSyncWorker]):
 * list this app's backups, and queue a restore of each one [PhoneSync] picks,
 * through [RestoreStart.startAll], the path Home's card's Restore takes.
 *
 * Nothing on the phone is deleted or changed: the only writes are the rows
 * [RestoreStart] adds for the restores it queues, the saved listing Home's
 * card reads ([CloudBackupListing.record]), and the tried set.
 */
object PhoneSyncRun {

    /** What a run came to. */
    sealed interface Outcome {
        data class Skipped(val reason: PhoneSync.Skip) : Outcome

        /** The listing failed (offline, signed out, no backend): the next run asks again. */
        data object ListFailed : Outcome

        /** [total] restores asked for; [counts] says how many were queued now and how many were already running. */
        data class Queued(val total: Int, val counts: RestoreStart.Counts) : Outcome
    }

    /**
     * [api] and [tokens] are the cloud seam (ADR-002).
     *
     * @param restoring cloud ids with a restore queued or running; a test seam.
     * @param start queues the restores; a test seam.
     */
    suspend fun run(
        context: Context,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
        restoring: (Context) -> Set<String> = ::restoringIds,
        start: (Context, List<RestoreStart.Target>) -> RestoreStart.Counts = RestoreStart::startAll,
    ): Outcome = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        // Read before the listing: a row deleted everywhere while the listing is
        // on its way is still counted, so its backup is not brought back.
        val before = SessionStore.list(app)
        skipFor(app)?.let { return@withContext Outcome.Skipped(it) }
        val listed = when (val outcome = CloudRestore.listCompleted(app, api, tokens)) {
            is CloudRestore.ListOutcome.Ready -> outcome.sessions
            CloudRestore.ListOutcome.Empty -> emptyList()
            else -> return@withContext Outcome.ListFailed
        }
        // The same listing Home's card offers from, saved as a reconcile saves it,
        // and read back as the backups it holds.
        CloudBackupListing.record(app, listed)
        val backups = CloudBackupListing.read(app)
        val after = SessionStore.list(app)
        if (SessionStore.isIndexCorrupt()) return@withContext Outcome.Skipped(PhoneSync.Skip.INDEX_UNREADABLE)
        val records = (after + before).distinctBy { it.id }
        val tried = triedIds(app)
        val picked = PhoneSync.toRestore(backups, records, CloudBackupListing.hiddenIds(app), tried, restoring(app))
        val targets = targets(app, picked, after)
        val counts = if (targets.isEmpty()) RestoreStart.Counts(0, 0) else start(app, targets)
        setTriedIds(app, PhoneSync.triedAfter(tried, backups, picked.map { it.cloudId }))
        if (targets.isNotEmpty()) {
            Timber.i("Sync from other phones: %d queued, %d already running", counts.started, counts.alreadyRunning)
        }
        Outcome.Queued(targets.size, counts)
    }

    /** [PhoneSync.skip] for this phone now: the switch, the licence and policy, the index. */
    @WorkerThread
    private fun skipFor(context: Context): PhoneSync.Skip? = PhoneSync.skip(
        switchOn = AppSettings.syncFromOtherPhonesEnabled(context),
        restoreEnabled = LicenseEntitlements.restoreEnabled(context),
        indexReadable = !SessionStore.isIndexCorrupt(),
    )

    /** [picked] as restores, each named as Home's card would name it, against the rows on the phone ([records]). */
    private fun targets(
        context: Context,
        picked: List<CloudBackupListing.Backup>,
        records: List<SessionRecord>,
    ): List<RestoreStart.Target> = CloudBackupListing.labelled(picked, records).map { backup ->
        val name = backup.name.ifBlank { context.getString(R.string.cloud_backups_unnamed) }
        RestoreStart.targetFor(backup.cloudId, backup.localId, name)
    }

    /** Cloud ids with a restore queued or running, from the `restore` tag every restore carries. */
    @WorkerThread
    fun restoringIds(context: Context): Set<String> =
        runCatching { WorkManager.getInstance(context.applicationContext).getWorkInfosByTag(WorkTags.RESTORE).get() }
            .onFailure { Timber.w(it, "Could not read queued restores") }
            .getOrDefault(emptyList())
            .filterNot { it.state.isFinished }
            .mapNotNullTo(HashSet()) { WorkTags.idFromTags(it.tags, WorkTags.RESTORE) }

    /** Backups this sync has started a restore of; cleared with the listing at sign-out. */
    fun triedIds(context: Context): Set<String> = prefs(context)[CloudListing.SYNC_TRIED]

    private fun setTriedIds(context: Context, ids: Set<String>) {
        prefs(context).edit { put(CloudListing.SYNC_TRIED, ids) }
    }

    private fun prefs(context: Context) = privatePrefs(context, CloudListing.NAME)
}
