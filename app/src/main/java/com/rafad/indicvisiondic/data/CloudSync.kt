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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    suspend fun reconcile(context: Context, reupload: Boolean = true, deep: Boolean = false): Outcome {
        return withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val api = IndicApi(appContext)
            if (!api.enabled) return@withContext Outcome.Disabled

            // Every screen resume lands here, and each check costs one Firestore
            // read per cloud session. A successful check stays fresh for a few
            // minutes; an explicit pull-to-refresh (deep) always goes through.
            val prefs = appContext.getSharedPreferences("indic_cloudsync", Context.MODE_PRIVATE)
            val sinceLast = System.currentTimeMillis() - prefs.getLong(K_LAST_RECONCILE_AT, 0L)
            if (!deep && sinceLast in 0 until RECONCILE_MIN_INTERVAL_MS) {
                Timber.d("Reconcile skipped — last successful check %d s ago", sinceLast / MS_PER_SECOND)
                return@withContext Outcome.Skipped
            }

            val token = TokenProvider.usableIdToken() ?: return@withContext Outcome.Offline

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
            prefs.edit().putLong(K_LAST_RECONCILE_AT, System.currentTimeMillis()).apply()
            Outcome.Ok(cloud.sessions.size, cloud.quota.used, cloud.quota.max, repaired)
        }
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
    suspend fun eraseEverywhere(context: Context, localSessionId: String): EraseResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val record = SessionStore.get(appContext, localSessionId)
        val api = IndicApi(appContext)

        val neverSynced = record == null ||
            (record.syncState == SessionRecord.SyncState.LOCAL_ONLY && record.cloudSessionId.isBlank())
        if (!api.enabled || neverSynced) {
            SessionStore.delete(appContext, localSessionId)
            return@withContext EraseResult.ERASED_EVERYWHERE
        }

        val token = TokenProvider.usableIdToken()
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

    /** Outcome of [exportAccountData]: the file to share, and how complete it is. */
    data class ExportResult(val file: File, val cloudIncluded: Boolean)

    /**
     * GDPR data export (Art. 20 portability): everything the app holds about
     * this account, as one JSON file ready to be shared or saved.
     *
     * The device half — the local session index — is always included, so the
     * export still produces something usable when cloud sync is off or
     * unreachable; [ExportResult.cloudIncluded] says whether the backend's copy
     * made it in. Returns null only when the file itself couldn't be written.
     *
     * The file goes in `cache/share/` because that is the one directory the
     * FileProvider publishes (see `res/xml/share_paths.xml`) — anywhere else
     * and `getUriForFile` refuses to build a URI for it.
     */
    suspend fun exportAccountData(context: Context): ExportResult? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val cloud = fetchCloudExport(appContext)
        val payload = buildJsonObject {
            put("exportedAt", isoStamp())
            put("account", TokenStore.cachedEmail(appContext) ?: "")
            put("localSessions", exportJson.encodeToJsonElement(SessionStore.list(appContext)))
            put("cloud", cloud ?: JsonNull)
        }
        try {
            val dir = File(appContext.cacheDir, "share").apply { mkdirs() }
            // Previous exports are stale the moment a new one is made, and each
            // is a full copy of the account — don't leave them piling up.
            dir.listFiles { f -> f.name.startsWith(EXPORT_PREFIX) }?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val out = File(dir, "$EXPORT_PREFIX$stamp.json")
            out.writeText(exportJson.encodeToString(JsonObject.serializer(), payload))
            ExportResult(out, cloud != null)
        } catch (e: IOException) {
            Timber.e(e, "Data export failed — could not write the export file")
            null
        }
    }

    /** The backend's copy of the account, or null when the cloud can't answer. */
    @Suppress("ReturnCount") // two "no cloud to ask" guards, then the answer
    private suspend fun fetchCloudExport(appContext: Context): JsonElement? {
        val api = IndicApi(appContext)
        if (!api.enabled) return null
        val token = TokenProvider.usableIdToken() ?: return null
        return try {
            Json.parseToJsonElement(api.exportAccount(token))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Cloud half of the data export failed — exporting local data only")
            null
        }
    }

    private fun isoStamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

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
            val token = TokenProvider.usableIdToken() ?: return@withContext false
            try {
                api.deleteAccount(token)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.e(e, "Account erasure failed — local data left intact")
                return@withContext false
            }
        }

        // Cloud is gone (or was never configured): now wipe this device and the
        // Firebase identity itself (best-effort — needs recent sign-in; falls back
        // to sign-out so the app returns to the login screen regardless).
        val fbUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        runCatching { fbUser?.delete()?.await() }
            .onFailure { Timber.w(it, "Firebase user delete failed; signing out instead") }
        SessionStore.deleteAll(appContext)
        // Reuse the shared session clear (Firebase sign-out + TokenStore).
        AuthRepository(appContext).signOut()
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
     * Queue the upload for one analysis. Everything the worker needs lives in
     * [SessionStore], so only the id travels in the input Data.
     *
     * Uses [ExistingWorkPolicy.KEEP] so a reconcile pass cannot cancel an
     * in-flight upload. Defaults to [NetworkType.UNMETERED] for background
     * repair; pass [allowMetered] = true for an explicit post-analysis upload.
     */
    fun enqueueUpload(
        context: Context,
        localSessionId: String,
        allowMetered: Boolean = false,
    ) {
        val network = if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
        val work = OneTimeWorkRequestBuilder<DicUploadWorker>()
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
    }

    /** Readable on purpose: the export is a document the user keeps. */
    private val exportJson = Json { prettyPrint = true; encodeDefaults = true }

    private const val EXPORT_PREFIX = "indic-data-export-"
    private const val BACKOFF_SECONDS = 30L
    private const val K_LAST_RECONCILE_AT = "last_reconcile_at"
    private const val RECONCILE_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val MS_PER_SECOND = 1000L
}
