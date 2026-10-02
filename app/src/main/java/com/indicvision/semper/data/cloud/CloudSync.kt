package com.indicvision.semper.data.cloud

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.content.edit
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.workDataOf
import com.indicvision.semper.data.DicUploadWorker
import com.indicvision.semper.data.LicenseConfigWorker
import com.indicvision.semper.data.account.AuthRepository
import com.indicvision.semper.data.account.LicenseEntitlements
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.Authed
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.HttpFailure
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.net.authed
import com.indicvision.semper.data.prefs.DicSettings
import com.indicvision.semper.data.prefs.PrefFiles
import com.indicvision.semper.data.prefs.get
import com.indicvision.semper.data.prefs.privatePrefs
import com.indicvision.semper.data.prefs.put
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Keeps the local sync state honest against the cloud.
 *
 * `SessionRecord.syncState` is a *local* flag written after a successful upload.
 * If the cloud copy is later deleted (or never finished), the app would keep
 * claiming "Synced" forever — local state silently drifting from server truth.
 * [reconcile] asks the backend what actually exists and repairs the difference,
 * re-queueing uploads for anything that went missing.
 */
@Suppress("TooManyFunctions") // reconcile, erase variants, upload enqueue — one cloud facade
object CloudSync {

    private val reconcileLock = Mutex()

    /**
     * What a reconcile pass concluded.
     *
     * The distinction that matters: **[Offline] is normal, [Failed] is not.**
     * Collapsing them (as an earlier version did, by returning null for both)
     * meant a broken backend — a stale API Gateway config, a bad deploy — looked
     * exactly like "no signal", and the app silently kept showing a stale
     * "Synced" badge. Anything the server actually answered with an error must
     * reach the user.
     */
    sealed interface Outcome {
        /** Reconciled successfully. [repaired] sessions were found missing and re-queued. */
        data class Ok(
            val cloudCount: Int,
            val quotaUsed: Int,
            val quotaMax: Int,
            val repaired: Int,
        ) : Outcome

        /** Cloud sync isn't configured — nothing to check, say nothing. */
        data object Disabled : Outcome

        /** No connectivity or no usable token. Expected for an offline-first app; stay quiet. */
        data object Offline : Outcome

        /** Checked recently — throttled to protect the Firestore read budget. Stay quiet. */
        data object Skipped : Outcome

        /** The backend answered, and the answer was wrong. The user needs to know. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Compare local sessions against the cloud and repair drift. Local state is
     * only ever changed on a successful check.
     *
     * [deep] verifies the blobs still exist in Drive rather than trusting the
     * backend's index — the only way to catch artifacts deleted straight in
     * Drive. It costs a Drive call per session, so it's reserved for an explicit
     * pull-to-refresh; screen resumes use the cheap index check.
     *
     * With [reupload], a row still waiting to upload is queued again: an upload
     * deferred while the quota was unknown ([enqueueUpload]) has nothing else
     * to start it, and used to read "upload pending" for good.
     */
    suspend fun reconcile(
        context: Context,
        reupload: Boolean = true,
        deep: Boolean = false,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): Outcome {
        return withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            if (!api.enabled) {
                settleWithoutBackend(appContext)
                return@withContext Outcome.Disabled
            }

            // Home starts one reconcile per finished upload/restore job, all at once.
            // Run them one at a time so each later call sees the first one's
            // timestamp and config and skips, instead of all passing the throttle.
            reconcileLock.withLock {
                // Every screen resume lands here, and each check costs one Firestore
                // read per cloud session. A successful check stays fresh for a few
                // minutes; an explicit pull-to-refresh (deep) always goes through.
                val prefs = privatePrefs(appContext, PrefFiles.CloudSync.NAME)
                val sinceLast = System.currentTimeMillis() - prefs[PrefFiles.CloudSync.LAST_RECONCILE_AT]
                val throttled = !deep && sinceLast in 0 until RECONCILE_MIN_INTERVAL_MS

                val listed = api.authed(tokens) { token ->
                    refreshRemoteConfig(appContext, this, token, throttled)
                    // The expensive per-session reconcile below is throttled; the cheap
                    // config fetch above is not, so quota still recovers between reconciles.
                    if (throttled) null else this.listSessions(token, verify = deep)
                }
                val cloud = when (listed) {
                    is Authed.Ok -> listed.value ?: run {
                        Timber.d("Reconcile skipped — last successful check %d s ago", sinceLast / MS_PER_SECOND)
                        return@withContext Outcome.Skipped
                    }
                    // Checked above, before the lock.
                    Authed.Disabled -> return@withContext Outcome.Disabled
                    Authed.NoToken -> return@withContext Outcome.Offline
                    is Authed.Failed -> return@withContext reconcileFailure(listed.failure)
                }

                // Home offers the backups this phone has no row for from this.
                CloudBackupListing.record(appContext, cloud.sessions)

                // Only COMPLETED cloud sessions count as a real backup.
                val backedUp = cloud.sessions
                    .filter { it.status == UploadWorkOutcomes.STATUS_COMPLETED && it.localSessionId.isNotBlank() }
                    .map { it.localSessionId }
                    .toSet()

                // Waiting rows respect the save-to-cloud toggle; a repair does not,
                // because it restores a backup the user already had.
                val repaired = repairRows(appContext, backedUp, reupload, reupload && uploadsEnabled(appContext, api))
                prefs.edit { put(PrefFiles.CloudSync.LAST_RECONCILE_AT, System.currentTimeMillis()) }
                Outcome.Ok(cloud.sessions.size, cloud.quota.used, cloud.quota.max, repaired)
            }
        }
    }

    /**
     * What a listing that failed means for the reconcile. Anything the server
     * answered with a status, or a refusal of the account, is a fault the user
     * must see ([Outcome.Failed]); no answer at all is [Outcome.Offline]. A
     * failure that is not I/O at all is logged as an error and reported, not
     * retried.
     */
    private fun reconcileFailure(failure: HttpFailure): Outcome {
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
                Timber.e(failure.cause, "Cloud reconcile failed unexpectedly")
                Outcome.Failed("unexpected ${failure.cause.javaClass.simpleName}")
            }
            else -> {
                Timber.w(failure.cause, "Cloud reconcile skipped — offline")
                Outcome.Offline
            }
        }
    }

    /**
     * Mark SYNCED rows whose backup is not in [backedUp] PENDING, and queue them
     * when [reupload]. With [requeue], queue the rows already PENDING too.
     * Returns how many rows were repaired.
     */
    private fun repairRows(appContext: Context, backedUp: Set<String>, reupload: Boolean, requeue: Boolean): Int {
        var repaired = 0
        SessionStore.list(appContext).forEach { record ->
            val claimsSynced = record.syncState == SessionRecord.SyncState.SYNCED
            if (claimsSynced && record.id !in backedUp) {
                // The cloud copy is gone (deleted) or never completed.
                Timber.i("Session %s claims SYNCED but is not in the cloud — repairing", record.id)
                SessionStore.setSyncState(appContext, record.id, SessionRecord.SyncState.PENDING)
                repaired++
                if (reupload) queueUpload(appContext, record.id)
            } else if (requeue && record.syncState == SessionRecord.SyncState.PENDING) {
                // KEEP leaves an upload already queued or running alone.
                queueUpload(appContext, record.id)
            } else if (claimsSynced && record.metadataStale) {
                // Backed up, but changed since: a send that gave up (ADR-013).
                queueMetadata(appContext, record.id)
            }
        }
        return repaired
    }

    /** How a reconcile queues an upload; tests swap it to see what was queued. */
    @VisibleForTesting
    internal var queueUpload: (Context, String) -> Unit = ::enqueueUpload

    /** How a reconcile queues a metadata send ([SessionMetadataSync]); tests swap it. */
    @VisibleForTesting
    internal var queueMetadata: (Context, String) -> Unit = SessionMetadataSync::enqueue

    /**
     * Fetch and cache product limits (quota ceiling, frame cap) from cloud config.
     * Runs on every full reconcile, and additionally whenever config is still
     * unknown — even on a throttled resume. Analysis runs on-device with its
     * upload gated until config lands, so the reconcile throttle must never be the
     * reason quota stays unknown. Best-effort: a failure just leaves it unknown.
     */
    private suspend fun refreshRemoteConfig(
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

    /** Outcome of an erase request, so the UI can tell the user what happened. */
    enum class EraseResult {
        /** Local files gone AND the cloud copy erased (or there wasn't one). */
        ERASED_EVERYWHERE,

        /** Local files gone, but the cloud copy could not be reached — it still exists. */
        LOCAL_ONLY_CLOUD_UNREACHABLE,

        /**
         * The server asked us to slow down (429 after the interceptor's own
         * retries). Nothing was deleted; the same request can be sent again
         * once a token is back, which [SessionDeletes] does rather than
         * reporting the analysis as still in the cloud.
         */
        RATE_LIMITED,
    }

    /**
     * GDPR erasure for one analysis: permanently delete the cloud copy (Drive
     * artifacts + Firestore metadata) and then the local files.
     *
     * The cloud is erased **first** — deleting locally first would lose the
     * pointer to the cloud copy and orphan the user's data. If the backend
     * can't be reached we do NOT delete locally either, so the user is never
     * told "erased everywhere" when it isn't.
     */
    suspend fun eraseEverywhere(
        context: Context,
        localSessionId: String,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): EraseResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val record = SessionStore.get(appContext, localSessionId)

        val neverSynced = record == null ||
            (record.syncState == SessionRecord.SyncState.LOCAL_ONLY && record.cloudSessionId.isBlank())
        if (!api.enabled || neverSynced) {
            SessionStore.delete(appContext, localSessionId)
            return@withContext EraseResult.ERASED_EVERYWHERE
        }

        val erased = api.authed(tokens) { token ->
            val cloudId = resolveCloudId(this, token, record)
            if (cloudId != null) {
                this.deleteSession(token, cloudId)
                CloudBackupListing.forget(appContext, cloudId)
            }
            SessionStore.delete(appContext, localSessionId)
        }
        if (erased is Authed.Ok) {
            Timber.i("Erased analysis %s locally and in the cloud", localSessionId)
            return@withContext EraseResult.ERASED_EVERYWHERE
        }
        if (erased is Authed.Failed) {
            Timber.e(erased.failure.cause, "Cloud erase failed for %s — leaving local copy intact", localSessionId)
        }
        eraseFailure(erased)
    }

    /** What actually happened, so the caller can tell the user the truth. */
    enum class AccountDeletion {
        /** Backend, device and Firebase identity are all gone. */
        DELETED,

        /** Nothing was touched — the backend could not be reached. */
        CLOUD_UNREACHABLE,

        /** Data is gone, but the sign-in identity outlived it. */
        IDENTITY_KEPT,
    }

    /**
     * GDPR account deletion: erase the account and every analysis from the
     * cloud, then wipe all local data, the identity and the session token.
     *
     * The caller re-authenticates first, which is what lets the identity delete
     * succeed instead of being refused as too stale.
     *
     * Runs to the end once started, even if the caller's scope is cancelled (a
     * screen rotating away mid-delete): see the internal overload. A caller whose
     * scope died never sees the result, so the next screen must judge by state
     * (signed out, no local sessions), not by a callback.
     */
    suspend fun deleteAccount(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): AccountDeletion = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val auth = AuthRepository(appContext, api, tokens)
        deleteAccount(
            eraseCloud = { eraseAccountInCloud(api, tokens).accountGone },
            deleteIdentity = { auth.deleteIdentity().isSuccess },
            wipeLocal = { SessionStore.deleteAll(appContext) },
            signOut = { auth.signOut() },
        )
    }

    /**
     * The order-sensitive half of [deleteAccount], with its side effects passed
     * in so the sequence can be tested without Firebase or a backend.
     *
     * The cloud goes first, for the same reason as [eraseEverywhere]: if it
     * fails nothing local is touched, so the user is never told their data is
     * gone while it still exists. Once the data *is* gone the session must not
     * continue, so the wipe and sign-out run whether or not the identity itself
     * could be deleted.
     *
     * The whole sequence is [NonCancellable]. Callers run it from a screen's scope,
     * which a rotation cancels; cancelled after the erase, the phone kept its local
     * data and a signed-in session for an account the server no longer has. The
     * erase is covered too: the server may finish it after the caller has gone, and
     * a client that stopped waiting would wipe nothing. Each step is local work or
     * a network call that ends on its own timeouts.
     */
    internal suspend fun deleteAccount(
        eraseCloud: suspend () -> Boolean,
        deleteIdentity: suspend () -> Boolean,
        wipeLocal: () -> Unit,
        signOut: suspend () -> Unit,
    ): AccountDeletion = withContext(NonCancellable) {
        if (!eraseCloud()) return@withContext AccountDeletion.CLOUD_UNREACHABLE
        // The data is gone, so nothing may stop the wipe. Under NonCancellable a
        // CancellationException here is never this sequence's own (a cancelled
        // Firebase Task, say): it means the identity survived, nothing more.
        val identityGone = try {
            deleteIdentity()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Identity delete failed after the cloud erase; wiping anyway")
            false
        }
        wipeLocal()
        signOut()
        Timber.i("Account erased and local data wiped")
        if (identityGone) AccountDeletion.DELETED else AccountDeletion.IDENTITY_KEPT
    }

    /**
     * Erase the account in the cloud, saying how it went: [Authed.Ok] (erased),
     * [Authed.Disabled] (no backend, so nothing to erase), [Authed.NoToken], or
     * [Authed.Failed] with the failure's [HttpFailure.Kind] (a refused token, a
     * server error, no answer). [deleteAccount] reads only [accountGone]; the
     * kind is there for a screen that wants to say which it was.
     */
    internal suspend fun eraseAccountInCloud(api: CloudApi, tokens: TokenSource): Authed<Unit> {
        val erased = api.authed(tokens) { token -> this.deleteAccount(token) }
        when (erased) {
            is Authed.Failed -> Timber.e(
                erased.failure.cause,
                "Account erasure failed (%s) — local data left intact",
                erased.failure.kind,
            )
            Authed.NoToken -> Timber.w("Account erasure not sent: no usable token — local data left intact")
            else -> Unit
        }
        return erased
    }

    /** The account's cloud data is gone: erased, or there was never a backend. */
    internal val Authed<Unit>.accountGone: Boolean get() = this is Authed.Ok || this == Authed.Disabled

    /** Delete only this device's heavy artifacts; the cloud backup and index row stay. */
    suspend fun eraseLocalOnly(context: Context, localSessionId: String) = withContext(Dispatchers.IO) {
        SessionStore.dropLocalArtifacts(context.applicationContext, localSessionId)
    }

    /**
     * Erase one cloud backup addressed by its **backend** id. The settings
     * list is built from cloud rows, which may have no local copy at all, so
     * the backend id is the only key always available. Permanent: the Drive
     * artifacts and Firestore metadata both go.
     *
     * When a local record does point at this backup, it drops back to
     * LOCAL_ONLY so the Home badge stops claiming a backup that no longer
     * exists, and forgets the link so that a later delete of the phone copy
     * does not ask the backend to erase this backup a second time. Anything
     * but [EraseResult.ERASED_EVERYWHERE] means nothing was deleted.
     */
    suspend fun eraseCloudBackup(
        context: Context,
        cloudSessionId: String,
        localSessionId: String,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): EraseResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val erased = api.authed(tokens) { token ->
            this.deleteSession(token, cloudSessionId)
            CloudBackupListing.forget(appContext, cloudSessionId)
            forgetCloudCopy(appContext, localSessionId)
        }
        if (erased is Authed.Ok) {
            Timber.i("Deleted cloud backup %s", cloudSessionId)
            return@withContext EraseResult.ERASED_EVERYWHERE
        }
        if (erased is Authed.Failed) Timber.e(erased.failure.cause, "Cloud backup delete failed for %s", cloudSessionId)
        eraseFailure(erased)
    }

    /** The local row no longer has a cloud copy: LOCAL_ONLY, and no link to follow. */
    internal fun forgetCloudCopy(appContext: Context, localSessionId: String) {
        if (SessionStore.get(appContext, localSessionId) == null) return
        SessionStore.update(appContext, localSessionId) {
            it.copy(syncState = SessionRecord.SyncState.LOCAL_ONLY, cloudSessionId = "")
        }
    }

    /** An erase that did not go through: a 429 is worth waiting out; anything else leaves the cloud copy. */
    private fun eraseFailure(erased: Authed<*>): EraseResult =
        if ((erased as? Authed.Failed)?.failure?.kind == HttpFailure.Kind.RATE_LIMITED) {
            EraseResult.RATE_LIMITED
        } else {
            EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
        }

    /**
     * Backend session id for a local analysis, or null if none is known. A
     * lookup that fails (offline, a server error) is null too: Home calls this
     * from a screen scope, where a thrown failure ended the app.
     */
    suspend fun resolveCloudIdFor(
        context: Context,
        record: SessionRecord,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): String? =
        withContext(Dispatchers.IO) {
            if (record.cloudSessionId.isNotBlank()) return@withContext record.cloudSessionId
            val found = api.authed(tokens) { token -> resolveCloudId(this, token, record) }
            if (found is Authed.Failed) {
                Timber.w(found.failure.cause, "Could not look up the backup of %s", record.id)
            }
            found.getOrNull()
        }

    /**
     * The backend session id for a local analysis. Uses the stored link when we
     * have it, else falls back to matching on localSessionId (records uploaded
     * before the link existed). Null = nothing in the cloud to erase.
     */
    private suspend fun resolveCloudId(api: CloudApi, token: String, record: SessionRecord): String? {
        if (record.cloudSessionId.isNotBlank()) return record.cloudSessionId
        return api.listSessions(token).sessions
            .firstOrNull { it.localSessionId == record.id }
            ?.sessionId
    }

    /**
     * Whether a finished analysis is uploaded.
     *
     * A licensed account chooses through the Settings "Save to cloud" toggle.
     * A demo account has no such toggle — demo analyses are always recorded
     * (images and results), which is the one cloud feature demo has; what it
     * lacks is the licensed retrieval half (restore, bundle download). The
     * pref is ignored rather than read so a toggle turned off under an earlier
     * licence cannot silently stop demo recording.
     *
     * A build with no backend (a lab build, or the emulator sign-in bypass)
     * records nothing: its analyses are saved as not backed up, rather than as
     * waiting for an upload that can never run.
     */
    fun uploadsEnabled(context: Context, api: CloudApi = IndicApi.get(context)): Boolean =
        api.enabled && (!LicenseEntitlements.cloudBackupEnabled(context) || DicSettings.saveToCloud(context))

    /**
     * This build has no backend, so a row waiting to upload never will. It goes
     * back to LOCAL_ONLY, and Home says "Not backed up" instead of "upload
     * pending" for good. Such rows come from a build that had a backend, one
     * installed over the other under the same app id; a build with a backend
     * backs them up again from Settings or the row.
     */
    @WorkerThread
    fun settleWithoutBackend(context: Context) {
        SessionStore.list(context)
            .filter { it.syncState == SessionRecord.SyncState.PENDING }
            .forEach {
                Timber.i("No cloud backend in this build — %s is not backed up", it.id)
                SessionStore.setSyncState(context, it.id, SessionRecord.SyncState.LOCAL_ONLY)
            }
    }

    /**
     * Queue the upload for one analysis. Everything the worker needs lives in
     * [SessionStore], so only the id travels in the input Data.
     *
     * Uses [ExistingWorkPolicy.KEEP] so a reconcile pass cannot cancel an
     * in-flight upload. Network constraint follows [DicSettings.uploadWifiOnly].
     *
     * No-op until the server quota is known ([TokenStore.isQuotaKnown]): the
     * analysis is already saved locally and its [SessionRecord] stays PENDING, so
     * the next reconcile, which fetches config first, queues it again once the
     * ceiling arrives. This is the single point that gates
     * upload on an unknown quota — analysis itself never blocks.
     */
    fun enqueueUpload(
        context: Context,
        localSessionId: String,
    ) {
        // Deliberately not gated on the licence: recording an analysis is open
        // to every account (see [uploadsEnabled]); only restore is licensed.
        if (!TokenStore.isQuotaKnown(context)) {
            Timber.i("Upload deferred for %s — cloud quota not yet known", localSessionId)
            return
        }
        // One policy for post-analysis and repair: Wi‑Fi-only when opted in;
        // otherwise any connected network.
        val network = if (DicSettings.uploadWifiOnly(context)) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }
        val work = oneTimeWork<DicUploadWorker>(
            tags = listOf(WorkTags.UPLOAD),
            input = workDataOf(DicKeys.SESSION_LOCAL_ID to localSessionId),
            network = network,
            expedited = true,
        )
        enqueueUnique(context, WorkTags.uploadName(localSessionId), ExistingWorkPolicy.KEEP, work)
        SemperAnalytics.event(context, SemperAnalytics.CLOUD_UPLOAD_ENQUEUED)
    }

    private const val RECONCILE_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val MS_PER_SECOND = 1000L
}
