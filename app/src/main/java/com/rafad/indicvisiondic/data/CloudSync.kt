package com.rafad.indicvisiondic.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.data.net.IndicApi
import com.rafad.indicvisiondic.data.net.TokenProvider
import com.rafad.indicvisiondic.data.net.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
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
object CloudSync {

    /** Result of a reconcile pass, for surfacing in the UI. */
    data class Report(val cloudCount: Int, val quotaMax: Int, val repaired: Int)

    /**
     * Compare local sessions against the cloud and fix drift.
     * Returns null when the cloud isn't reachable/configured (state left alone).
     */
    suspend fun reconcile(context: Context, reupload: Boolean = true): Report? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val api = IndicApi(appContext)
            if (!api.enabled) return@withContext null
            val token = TokenProvider.usableIdToken(appContext) ?: return@withContext null

            val cloud = try {
                api.listSessions(token)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "Cloud reconcile skipped — backend unreachable")
                return@withContext null
            }

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
            Report(cloud.sessions.size, cloud.quota.max, repaired)
        }

    /** Outcome of an erase request, so the UI can tell the user what happened. */
    enum class EraseResult {
        /** Local files gone AND the cloud copy erased (or there wasn't one). */
        ERASED_EVERYWHERE,

        /** Local files gone, but the cloud copy could not be reached — it still exists. */
        LOCAL_ONLY_CLOUD_UNREACHABLE,
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
    suspend fun eraseEverywhere(context: Context, localSessionId: String): EraseResult =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val record = SessionStore.get(appContext, localSessionId)
            val api = IndicApi(appContext)

            val neverSynced = record == null ||
                (record.syncState == SessionRecord.SyncState.LOCAL_ONLY && record.cloudSessionId.isBlank())
            if (!api.enabled || neverSynced) {
                SessionStore.delete(appContext, localSessionId)
                return@withContext EraseResult.ERASED_EVERYWHERE
            }

            val token = TokenProvider.usableIdToken(appContext)
                ?: return@withContext EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
            try {
                val cloudId = resolveCloudId(api, token, record!!)
                if (cloudId != null) api.deleteSession(token, cloudId)
                SessionStore.delete(appContext, localSessionId)
                Timber.i("Erased analysis %s locally and in the cloud", localSessionId)
                EraseResult.ERASED_EVERYWHERE
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.e(e, "Cloud erase failed for %s — leaving local copy intact", localSessionId)
                EraseResult.LOCAL_ONLY_CLOUD_UNREACHABLE
            }
        }

    /**
     * GDPR data export (Art. 20 portability): fetch everything the backend
     * holds about this account and write it to a JSON file in the cache, ready
     * to be shared/saved. Returns null if the cloud isn't reachable.
     */
    suspend fun exportAccountData(context: Context): java.io.File? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val api = IndicApi(appContext)
        if (!api.enabled) return@withContext null
        val token = TokenProvider.usableIdToken(appContext) ?: return@withContext null
        try {
            val json = api.exportAccount(token)
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                .format(java.util.Date())
            val out = java.io.File(appContext.cacheDir, "indic-data-export-$stamp.json")
            out.writeText(json)
            out
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Data export failed")
            null
        }
    }

    /**
     * GDPR account deletion: erase the account and every analysis from the
     * cloud, then wipe all local data and the session token.
     *
     * The cloud is erased first for the same reason as [eraseEverywhere] — and
     * on failure nothing local is touched, so the user is never told their data
     * is gone while it still exists. Returns false if the cloud couldn't be
     * reached (caller should keep the user signed in and show an error).
     */
    suspend fun deleteAccount(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val api = IndicApi(appContext)

        if (api.enabled) {
            val token = TokenProvider.usableIdToken(appContext) ?: return@withContext false
            try {
                api.deleteAccount(token)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.e(e, "Account erasure failed — local data left intact")
                return@withContext false
            }
        }

        // Cloud is gone (or was never configured): now wipe this device.
        SessionStore.deleteAll(appContext)
        TokenStore.clear(appContext)
        Timber.i("Account erased and local data wiped")
        true
    }

    /** Delete only this device's copy; the cloud backup is deliberately kept. */
    suspend fun eraseLocalOnly(context: Context, localSessionId: String) = withContext(Dispatchers.IO) {
        SessionStore.delete(context.applicationContext, localSessionId)
    }

    /**
     * The backend session id for a local analysis. Uses the stored link when we
     * have it, else falls back to matching on localSessionId (records uploaded
     * before the link existed). Null = nothing in the cloud to erase.
     */
    private suspend fun resolveCloudId(api: IndicApi, token: String, record: SessionRecord): String? {
        if (record.cloudSessionId.isNotBlank()) return record.cloudSessionId
        return api.listSessions(token).sessions
            .firstOrNull { it.localSessionId == record.id }
            ?.sessionId
    }

    /**
     * Queue (or re-queue) the upload for one analysis. Everything the worker
     * needs lives in [SessionStore], so only the id travels in the input Data.
     */
    fun enqueueUpload(context: Context, localSessionId: String) {
        val work = OneTimeWorkRequestBuilder<DicUploadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(DicKeys.SESSION_LOCAL_ID, localSessionId).build())
            .addTag("upload")
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "upload-$localSessionId",
            ExistingWorkPolicy.REPLACE,
            work,
        )
    }

    private const val BACKOFF_SECONDS = 30L
}
