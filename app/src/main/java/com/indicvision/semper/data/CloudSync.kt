package com.indicvision.semper.data

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.indicvision.semper.DicKeys
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

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
            if (!api.enabled) return@withContext Outcome.Disabled

            // Home starts one reconcile per finished upload/restore job, all at once.
            // Run them one at a time so each later call sees the first one's
            // timestamp and config and skips, instead of all passing the throttle.
            reconcileLock.withLock {
                // Every screen resume lands here, and each check costs one Firestore
                // read per cloud session. A successful check stays fresh for a few
                // minutes; an explicit pull-to-refresh (deep) always goes through.
                val prefs = appContext.getSharedPreferences("indic_cloudsync", Context.MODE_PRIVATE)
                val sinceLast = System.currentTimeMillis() - prefs.getLong(K_LAST_RECONCILE_AT, 0L)
                val throttled = !deep && sinceLast in 0 until RECONCILE_MIN_INTERVAL_MS

                val token = tokens.usableIdToken() ?: return@withContext Outcome.Offline

                refreshRemoteConfig(appContext, api, token, throttled)

                // The expensive per-session reconcile below is throttled; the cheap
                // config fetch above is not, so quota still recovers between reconciles.
                if (throttled) {
                    Timber.d("Reconcile skipped — last successful check %d s ago", sinceLast / MS_PER_SECOND)
                    return@withContext Outcome.Skipped
                }

                val cloud = try {
                    api.listSessions(token, verify = deep)
                } catch (e: IndicApi.NotApprovedException) {
                    Timber.w(e, "Cloud reconcile refused — account not approved")
                    return@withContext Outcome.Failed("your account isn't approved for cloud backup")
                } catch (e: IndicApi.ApiException) {
                    // The server responded — so this is a real fault (404 = route not
                    // published on the gateway, 5xx = backend broken), not bad signal.
                    Timber.e(e, "Cloud reconcile FAILED with HTTP %d", e.code)
                    return@withContext Outcome.Failed("server returned HTTP ${e.code}")
                } catch (e: IOException) {
                    Timber.w(e, "Cloud reconcile skipped — offline")
                    return@withContext Outcome.Offline
                }

                // Home offers the backups this phone has no row for from this.
                CloudBackupListing.record(appContext, cloud.sessions)

                // Only COMPLETED cloud sessions count as a real backup.
                val backedUp = cloud.sessions
                    .filter { it.status == "COMPLETED" && it.localSessionId.isNotBlank() }
                    .map { it.localSessionId }
                    .toSet()

                var repaired = 0
                SessionStore.list(appContext).forEach { record ->
                    val claimsSynced = record.syncState == SessionRecord.SyncState.SYNCED
                    if (claimsSynced && record.id !in backedUp) {
                        // The cloud copy is gone (deleted) or never completed.
                        Timber.i("Session %s claims SYNCED but is not in the cloud — repairing", record.id)
                        SessionStore.setSyncState(appContext, record.id, SessionRecord.SyncState.PENDING)
                        repaired++
                        if (reupload) enqueueUpload(appContext, record.id)
                    }
                }
                prefs.edit { putLong(K_LAST_RECONCILE_AT, System.currentTimeMillis()) }
                Outcome.Ok(cloud.sessions.size, cloud.quota.used, cloud.quota.max, repaired)
            }
        }
    }

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
        suspendRunCatching { api.getConfig(token) }
            .onSuccess {
                AppRemoteConfig.apply(appContext, it)
                LicenseConfigWorker.enqueue(appContext)
            }
            .onFailure {
                AppRemoteConfig.recordFetchFailure(appContext)
                Timber.d(it, "App remote config fetch failed during reconcile")
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

        val token = tokens.usableIdToken()
            ?: return@withContext EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
        try {
            val cloudId = resolveCloudId(api, token, record)
            if (cloudId != null) {
                api.deleteSession(token, cloudId)
                CloudBackupListing.forget(appContext, cloudId)
            }
            SessionStore.delete(appContext, localSessionId)
            Timber.i("Erased analysis %s locally and in the cloud", localSessionId)
            EraseResult.ERASED_EVERYWHERE
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Cloud erase failed for %s — leaving local copy intact", localSessionId)
            failureOf(e)
        }
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
     */
    suspend fun deleteAccount(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): AccountDeletion = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val auth = AuthRepository(appContext, api, tokens)
        deleteAccount(
            eraseCloud = { eraseAccountInCloud(api, tokens) },
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
     */
    internal suspend fun deleteAccount(
        eraseCloud: suspend () -> Boolean,
        deleteIdentity: suspend () -> Boolean,
        wipeLocal: () -> Unit,
        signOut: suspend () -> Unit,
    ): AccountDeletion {
        if (!eraseCloud()) return AccountDeletion.CLOUD_UNREACHABLE
        val identityGone = deleteIdentity()
        wipeLocal()
        signOut()
        Timber.i("Account erased and local data wiped")
        return if (identityGone) AccountDeletion.DELETED else AccountDeletion.IDENTITY_KEPT
    }

    /** True when the backend copy is gone, or there was never a backend at all. */
    private suspend fun eraseAccountInCloud(api: CloudApi, tokens: TokenSource): Boolean {
        if (!api.enabled) return true
        val token = tokens.usableIdToken()
        return token != null &&
            suspendRunCatching { api.deleteAccount(token) }
                .onFailure { Timber.e(it, "Account erasure failed — local data left intact") }
                .isSuccess
    }

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
        if (!api.enabled) return@withContext EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
        val token = tokens.usableIdToken() ?: return@withContext EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
        try {
            api.deleteSession(token, cloudSessionId)
            CloudBackupListing.forget(appContext, cloudSessionId)
            forgetCloudCopy(appContext, localSessionId)
            Timber.i("Deleted cloud backup %s", cloudSessionId)
            EraseResult.ERASED_EVERYWHERE
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Cloud backup delete failed for %s", cloudSessionId)
            failureOf(e)
        }
    }

    /** The local row no longer has a cloud copy: LOCAL_ONLY, and no link to follow. */
    internal fun forgetCloudCopy(appContext: Context, localSessionId: String) {
        if (SessionStore.get(appContext, localSessionId) == null) return
        SessionStore.setSyncState(appContext, localSessionId, SessionRecord.SyncState.LOCAL_ONLY)
        SessionStore.setCloudSessionId(appContext, localSessionId, "")
    }

    private fun failureOf(e: Exception): EraseResult =
        if (e is IndicApi.ApiException && e.code == HTTP_TOO_MANY_REQUESTS) {
            EraseResult.RATE_LIMITED
        } else {
            EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
        }

    /** Backend session id for a local analysis, or null if none is known. */
    suspend fun resolveCloudIdFor(
        context: Context,
        record: SessionRecord,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): String? =
        withContext(Dispatchers.IO) {
            if (record.cloudSessionId.isNotBlank()) return@withContext record.cloudSessionId
            if (!api.enabled) return@withContext null
            val token = tokens.usableIdToken() ?: return@withContext null
            resolveCloudId(api, token, record)
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
     */
    fun uploadsEnabled(context: Context): Boolean =
        !LicenseEntitlements.cloudBackupEnabled(context) || DicSettings.saveToCloud(context)

    /**
     * Queue the upload for one analysis. Everything the worker needs lives in
     * [SessionStore], so only the id travels in the input Data.
     *
     * Uses [ExistingWorkPolicy.KEEP] so a reconcile pass cannot cancel an
     * in-flight upload. Network constraint follows [DicSettings.uploadWifiOnly].
     *
     * No-op until the server quota is known ([TokenStore.isQuotaKnown]): the
     * analysis is already saved locally and its [SessionRecord] stays PENDING, so
     * the next reconcile (which fetches config, then repairs unsynced sessions)
     * enqueues it once the ceiling arrives. This is the single point that gates
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
        val work = OneTimeWorkRequestBuilder<DicUploadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(network).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(DicKeys.SESSION_LOCAL_ID, localSessionId).build())
            .addTag("upload")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "upload-$localSessionId",
            ExistingWorkPolicy.KEEP,
            work,
        )
        SemperAnalytics.event(context, SemperAnalytics.CLOUD_UPLOAD_ENQUEUED)
    }

    private const val BACKOFF_SECONDS = 30L
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val K_LAST_RECONCILE_AT = "last_reconcile_at"
    private const val RECONCILE_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val MS_PER_SECOND = 1000L
}
