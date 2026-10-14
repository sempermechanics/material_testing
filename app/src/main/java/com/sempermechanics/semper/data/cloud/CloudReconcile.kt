package com.sempermechanics.semper.data.cloud

import android.content.Context
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.cloud.CloudSync.Outcome
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.util.suspendRunCatching
import timber.log.Timber

/**
 * The parts of [CloudSync.reconcile] around the listing: the config refresh
 * before it, the listing itself, what a failed listing means, and the repair
 * of rows after it.
 */
internal object CloudReconcile {

    /**
     * What a listing that failed means for the reconcile. Anything the server
     * answered with a status, or a refusal of the account, is a fault the user
     * must see ([Outcome.Failed]); no answer at all is [Outcome.Offline]. A
     * failure that is not I/O at all is logged as an error and reported, not
     * retried.
     */
    fun reconcileFailure(failure: HttpFailure): Outcome {
        val code = failure.code
        return when {
            failure.kind == HttpFailure.Kind.NOT_APPROVED -> {
                Timber.w(failure.cause, "Cloud reconcile refused — account not approved")
                Outcome.Failed("your account isn't approved for cloud backup")
            }
            // The server responded — so this is a real fault (404 = route not
            // published on the gateway, 5xx = backend broken), not bad signal.
            code != null -> {
                Timber.e(failure.cause, "Cloud reconcile FAILED with HTTP %d", code)
                Outcome.Failed("server returned HTTP $code")
            }
            failure.kind == HttpFailure.Kind.UNEXPECTED -> {
                // The class name is for the log only: Home shows the reason to the user.
                Timber.e(failure.cause, "Cloud reconcile failed unexpectedly (%s)", failure.cause.javaClass.simpleName)
                Outcome.Failed("unexpected error")
            }
            else -> {
                Timber.w(failure.cause, "Cloud reconcile skipped — offline")
                Outcome.Offline
            }
        }
    }

    /** This app's [cloud] sessions, the local ids they back up, and the rows another app backs up. */
    class Listing(val cloud: SessionsResponse, val backedUp: Set<String>, val keep: Set<String>)

    /**
     * This app's listing, plus the rows [repairRows] must [Listing.keep] SYNCED
     * although this app's list lacks them: a row restored from the other app's
     * backup (ADR-014, TD-153) is backed up there, and repairing it would
     * upload it again as a second session. Only when some SYNCED row is missing
     * is the whole account listed, once, to tell the two apart.
     */
    suspend fun list(appContext: Context, api: CloudApi, token: String, verify: Boolean): Listing {
        val cloud = api.listSessions(token, verify = verify)
        // Only COMPLETED cloud sessions count as a real backup.
        val backedUp = cloud.sessions.completed().map { it.localSessionId }.filter { it.isNotBlank() }.toSet()
        val missing = SessionStore.list(appContext)
            .filter { it.syncState == SessionRecord.SyncState.SYNCED && it.id !in backedUp }
        val keep = if (missing.isEmpty()) emptySet() else otherAppBackups(api, token, missing)
        return Listing(cloud, backedUp, keep)
    }

    /**
     * The [missing] rows another app's COMPLETED backup holds, by its local id
     * or the cloud id the restore stored. When the account cannot be listed,
     * all of them: a pass that cannot tell a lost backup from the other app's
     * repairs nothing, and the next pass asks again.
     */
    private suspend fun otherAppBackups(api: CloudApi, token: String, missing: List<SessionRecord>): Set<String> {
        val account = suspendRunCatching { api.listSessions(token, app = CloudApi.ALL_APPS) }.getOrElse {
            Timber.w(it, "Could not list every app's backups — repairing no row this pass")
            return missing.mapTo(HashSet()) { record -> record.id }
        }
        val completed = account.sessions.completed()
        val localIds = completed.map { it.localSessionId }.filter { it.isNotBlank() }.toSet()
        val cloudIds = completed.map { it.sessionId }.toSet()
        return missing
            .filter { it.id in localIds || (it.cloudSessionId.isNotBlank() && it.cloudSessionId in cloudIds) }
            .onEach { Timber.i("Session %s is backed up by another app — left SYNCED", it.id) }
            .mapTo(HashSet()) { it.id }
    }

    private fun List<CloudSessionDto>.completed() = filter { it.status == UploadWorkOutcomes.STATUS_COMPLETED }

    /**
     * Mark SYNCED rows whose backup is not in [backedUp] PENDING, and queue them
     * when [reupload]; a row in [keep] stays SYNCED ([list]). With [requeue],
     * queue the rows already PENDING too. Returns how many rows were repaired.
     */
    fun repairRows(
        appContext: Context,
        backedUp: Set<String>,
        keep: Set<String>,
        reupload: Boolean,
        requeue: Boolean,
    ): Int {
        var repaired = 0
        SessionStore.list(appContext).forEach { record ->
            val claimsSynced = record.syncState == SessionRecord.SyncState.SYNCED
            if (claimsSynced && record.id !in backedUp && record.id !in keep) {
                // The cloud copy is gone (deleted) or never completed.
                Timber.i("Session %s claims SYNCED but is not in the cloud — repairing", record.id)
                SessionStore.setSyncState(appContext, record.id, SessionRecord.SyncState.PENDING)
                repaired++
                if (reupload) CloudSync.queueUpload(appContext, record.id)
            } else if (requeue && record.syncState == SessionRecord.SyncState.PENDING) {
                // KEEP leaves an upload already queued or running alone.
                CloudSync.queueUpload(appContext, record.id)
            } else if (claimsSynced && record.metadataStale) {
                // Backed up, but changed since: a send that gave up (ADR-013).
                CloudSync.queueMetadata(appContext, record.id)
            }
        }
        return repaired
    }

    /**
     * Fetch and cache product limits (quota ceiling, frame cap) from cloud config.
     * Runs on every full reconcile, and additionally whenever config is still
     * unknown — even on a throttled resume. Analysis runs on-device with its
     * upload gated until config lands, so the reconcile throttle must never be the
     * reason quota stays unknown. Best-effort: a failure just leaves it unknown.
     */
    suspend fun refreshRemoteConfig(
        appContext: Context,
        api: CloudApi,
        token: String,
        throttled: Boolean,
    ) {
        if (throttled && AppRemoteConfig.isKnown(appContext)) return
        if (AppRemoteConfig.record(appContext, suspendRunCatching { api.getConfig(token) })) {
            LicenseConfigWorker.enqueue(appContext)
        }
    }
}
