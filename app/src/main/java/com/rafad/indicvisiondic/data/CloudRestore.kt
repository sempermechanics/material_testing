// Restore parsing: literal buffer sizes and manifest field offsets read clearest
// inline, so MagicNumber is suppressed for this whole file.
@file:Suppress("MagicNumber")

package com.rafad.indicvisiondic.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.rafad.indicvisiondic.data.net.CloudFileDto
import com.rafad.indicvisiondic.data.net.CloudSessionDto
import com.rafad.indicvisiondic.data.net.IndicApi
import com.rafad.indicvisiondic.data.net.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Rebuilds an analysis on this device from its cloud backup.
 *
 * Because the engine's `.dat` results are uploaded alongside the raw images,
 * a restored session is **fully re-openable** — the results viewer works without
 * re-running the analysis. The session's `metadata/metadata.json` is the
 * blueprint: it carries the engine parameters, ROI, metrics and the ordered
 * frame list (image ↔ dat ↔ csv), so we can reconstruct the [SessionRecord]
 * and the on-disk layout exactly as a local run would have produced it.
 *
 * Files land back in the same shape [DicUploadWorker] uploaded them from:
 * ```
 * <sessionDir>/reference.png
 * <sessionDir>/frame_%04d.dat
 * <sessionDir>/raw_deformed/<original image name>
 * ```
 */
@Suppress("TooManyFunctions") // one cohesive restore pipeline: fetch, parse, write, index
object CloudRestore {

    /** Input key for [DicRestoreWorker]: which cloud session to pull down. */
    const val KEY_CLOUD_SESSION_ID = "CLOUD_SESSION_ID"

    /**
     * Queue a restore. It runs in [DicRestoreWorker] rather than a UI scope so
     * it survives leaving the screen — a restore can be hundreds of megabytes
     * and must not die because the user navigated away.
     */
    fun enqueueRestore(context: Context, cloudSessionId: String): String {
        val name = workName(cloudSessionId)
        val work = OneTimeWorkRequestBuilder<DicRestoreWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putString(KEY_CLOUD_SESSION_ID, cloudSessionId).build())
            .addTag("restore")
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work)
        return name
    }

    /** Unique work name for a restore, so the UI can observe its progress. */
    fun workName(cloudSessionId: String): String = "restore-$cloudSessionId"

    private const val BACKOFF_SECONDS = 30L

    /** Concurrent GETs for legacy per-file restores (matches upload concurrency). */
    private const val LEGACY_DOWNLOAD_CONCURRENCY = 4

    /**
     * Why a restorable-list query failed or is empty — never collapse auth/config
     * failures into a blank "no backups" list.
     */
    sealed class ListResult {
        data class Ready(val sessions: List<CloudSessionDto>) : ListResult()
        data object Empty : ListResult()
        data object NeedSignIn : ListResult()
        data object ApiOff : ListResult()
        data class Failed(val reason: String) : ListResult()
    }

    /**
     * Cloud analyses available to restore (excludes ones already on this
     * device).
     *
     * Each call is one Firestore-backed session listing, and the settings page
     * asks on every open, so a successful answer is reused for
     * [LIST_CACHE_MS]. Anything that changes what the cloud holds must call
     * [invalidateRestorableCache]; failures are never cached, so a retry after
     * signing in or coming back online goes straight to the backend.
     */
    suspend fun listRestorable(context: Context): ListResult = withContext(Dispatchers.IO) {
        cachedList?.takeIf { System.currentTimeMillis() - cachedAt < LIST_CACHE_MS }
            ?.let { return@withContext it }

        val appContext = context.applicationContext
        val api = IndicApi(appContext)
        if (!api.enabled) return@withContext ListResult.ApiOff
        val token = TokenProvider.usableIdToken() ?: return@withContext ListResult.NeedSignIn
        try {
            val localIds = SessionStore.list(appContext).map { it.id }.toSet()
            val sessions = api.listSessions(token).sessions
                .filter { it.status == "COMPLETED" }
                .filter { it.localSessionId.isBlank() || it.localSessionId !in localIds }
            val result = if (sessions.isEmpty()) ListResult.Empty else ListResult.Ready(sessions)
            cachedList = result
            cachedAt = System.currentTimeMillis()
            result
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "listRestorable failed")
            ListResult.Failed(e.message ?: e.toString())
        }
    }

    /** Drop the cached listing after anything that changes the cloud's contents. */
    fun invalidateRestorableCache() {
        cachedList = null
    }

    @Volatile
    private var cachedList: ListResult? = null

    @Volatile
    private var cachedAt = 0L

    private const val LIST_CACHE_MS = 60_000L

    /** Convenience for callers that only need the list (empty on any non-Ready). */
    suspend fun listRestorableSessions(context: Context): List<CloudSessionDto> =
        when (val result = listRestorable(context)) {
            is ListResult.Ready -> result.sessions
            else -> emptyList()
        }

    /**
     * Download an analysis and rebuild it locally. Returns the restored local
     * session id, or throws on failure.
     */
    suspend fun restore(
        context: Context,
        sessionId: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val api = IndicApi(appContext)
        val token = TokenProvider.usableIdToken()
            ?: error("Not signed in")

        val manifest = api.listSessionFiles(token, sessionId)
        val files = manifest.files.filter { it.status == "COMPLETED" }
        require(files.isNotEmpty()) { "This backup has no completed files" }

        // 1. metadata.json first — it's the blueprint for everything else.
        val metaEntry = files.firstOrNull { it.role == "metadata" }
            ?: error("Backup is missing metadata.json")
        val metaTmp = File(appContext.cacheDir, "restore_${sessionId}_metadata.json")
        api.downloadFile(token, metaEntry.fileId, metaTmp)
        val meta = JSONObject(metaTmp.readText())

        // Restore under the original local id when we know it, so a restored
        // session lines up with its cloud copy for future reconciliation.
        val localId = meta.optString("localSessionId").ifBlank {
            manifest.localSessionId.ifBlank { "restored-" + sessionId.take(12) }
        }
        val sessionDir = SessionStore.dirFor(appContext, localId)
        val rawDeformedDir = File(sessionDir, SessionPaths.RAW_DEFORMED_SUBDIR).apply { mkdirs() }
        metaTmp.copyTo(File(sessionDir, "metadata.json"), overwrite = true)
        metaTmp.delete()

        // 2. Everything else, into the layout a local run would have produced.
        // New backups hold ONE Session.zip; older ones list every file. Both
        // rebuild the identical on-disk layout.
        val layout = Layout(sessionDir, rawDeformedDir)
        var refPath = ""
        val bundleEntry = files.firstOrNull { it.role == "bundle" }
        if (bundleEntry != null) {
            onProgress(0, 1)
            val zipTmp = File(appContext.cacheDir, "restore_${sessionId}_bundle.zip")
            try {
                api.downloadFile(token, bundleEntry.fileId, zipTmp)
                refPath = unpackBundle(zipTmp, layout)
            } finally {
                zipTmp.delete()
            }
            onProgress(1, 1)
        } else {
            refPath = restoreLegacyFiles(api, token, files, layout, onProgress)
        }

        // 3. Rebuild the index row from the blueprint.
        SessionStore.upsert(
            appContext,
            recordFrom(meta, localId, sessionDir, refPath),
            allowOverLimit = true, // already counted in the cloud quota
        )
        Timber.i("Restored analysis %s from cloud session %s (%d files)", localId, sessionId, files.size)
        // The listing excludes backups already on this device, so it changed.
        invalidateRestorableCache()
        localId
    }

    /** The on-disk shape of a restored session — where artifacts land. */
    private data class Layout(val sessionDir: File, val rawDeformedDir: File)

    /** Legacy per-file backups: download each artifact into place. Returns refPath. */
    private suspend fun restoreLegacyFiles(
        api: IndicApi,
        token: String,
        files: List<CloudFileDto>,
        layout: Layout,
        onProgress: suspend (done: Int, total: Int) -> Unit,
    ): String {
        val rest = files.filter { it.role != "metadata" }
        val done = AtomicInteger(0)
        val refPath = AtomicReference("")
        onProgress(0, rest.size)
        // A few GETs in flight fill the link the way parallel Drive uploads do;
        // one failure cancels siblings via coroutineScope (same as before: abort).
        coroutineScope {
            val gate = Semaphore(LEGACY_DOWNLOAD_CONCURRENCY)
            rest.map { f ->
                async {
                    gate.withPermit {
                        val dest = destFor(f.role, f.name, layout)
                        api.downloadFile(token, f.fileId, dest)
                        if (dest.name == "reference.png") refPath.set(dest.absolutePath)
                        onProgress(done.incrementAndGet(), rest.size)
                    }
                }
            }.awaitAll()
        }
        return refPath.get()
    }

    /**
     * Extract a Session.zip into the layout a local run would have produced.
     * Entries are named `role/name` by [DicUploadWorker]; the mapping must
     * mirror the legacy per-file restore. Returns the reference image's
     * restored path ("" if the bundle somehow lacks one).
     */
    private fun unpackBundle(zip: File, layout: Layout): String {
        var refPath = ""
        java.util.zip.ZipInputStream(zip.inputStream().buffered()).use { zin ->
            generateSequence { zin.nextEntry }
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val dest = destFor(
                        entry.name.substringBefore('/', ""),
                        entry.name.substringAfter('/'),
                        layout,
                    )
                    dest.outputStream().use { zin.copyTo(it) }
                    if (dest.name == "reference.png") refPath = dest.absolutePath
                }
        }
        return refPath
    }

    /**
     * Where one artifact lands on disk, by role — the single mapping both
     * restore paths share. Guards against zip-slip: an entry may not escape
     * the session directory.
     */
    private fun destFor(role: String, name: String, layout: Layout): File {
        val dest = when {
            role == "raw" && name == "Reference.png" -> File(layout.sessionDir, "reference.png")
            role == "raw" -> File(layout.rawDeformedDir, name)
            // Per-frame reports/heatmaps into their own subfolders — one PDF and
            // five PNGs per frame flat in the session dir would drown the .dat files.
            role == "reports" -> File(layout.sessionDir, "reports/$name")
            role == "processed" -> File(layout.sessionDir, "processed/$name")
            // dat lives flat in the session dir; csv is regenerable and kept
            // beside the session for export.
            else -> File(layout.sessionDir, name)
        }
        val canonical = dest.canonicalPath
        require(
            canonical.startsWith(layout.sessionDir.canonicalPath) ||
                canonical.startsWith(layout.rawDeformedDir.canonicalPath),
        ) { "Artifact path escapes session dir: $role/$name" }
        dest.parentFile?.mkdirs()
        return dest
    }

    private fun recordFrom(meta: JSONObject, localId: String, sessionDir: File, refPath: String): SessionRecord {
        val engine = meta.optJSONObject("engine") ?: JSONObject()
        val roi = engine.optJSONObject("roi") ?: JSONObject()
        val metrics = meta.optJSONObject("metrics") ?: JSONObject()
        val framesArr = meta.optJSONArray("frames")

        val defNames = buildList {
            if (framesArr != null) {
                for (i in 0 until framesArr.length()) {
                    add(framesArr.getJSONObject(i).optString("image"))
                }
            }
        }.filter { it.isNotBlank() }

        val statsArr = engine.optJSONArray("stats")
        val stats = buildList {
            if (statsArr != null) for (i in 0 until statsArr.length()) add(statsArr.optDouble(i, 0.0).toFloat())
        }

        val now = System.currentTimeMillis()
        val sweep = engine.optJSONObject("sweep")
        val skipped = sweep?.optJSONObject("skipped")
        return SessionRecord(
            id = localId,
            name = meta.optString("name").ifBlank { meta.optString("specimen", "Restored") },
            createdAt = now,
            updatedAt = now,
            frameCount = meta.optInt("frameCount", defNames.size),
            subset = engine.optInt("subset", 41),
            step = engine.optInt("step", 5),
            strainWindow = engine.optInt("strainWindow", 15),
            use6x6 = engine.optBoolean("use6x6", false),
            imgW = engine.optInt("imageWidth", 0),
            imgH = engine.optInt("imageHeight", 0),
            roiX = roi.optInt("x", 0),
            roiY = roi.optInt("y", 0),
            roiW = roi.optInt("w", 0),
            roiH = roi.optInt("h", 0),
            refPath = refPath,
            refName = meta.optString("specimen", "Reference"),
            sessionDir = sessionDir.absolutePath,
            defNames = defNames,
            headline = restoredHeadline(meta, engine, defNames, stats),
            engineStats = stats,
            strainMethod = engine.optString("strainMethod", "VSG"),
            pointsConverged = metrics.optInt("pointsConverged", 0),
            avgIterations = metrics.optDouble("avgIterations", 0.0).toFloat(),
            executionTimeMs = metrics.optInt("executionTimeMs", 0),
            // It came from the cloud, so it is by definition backed up.
            syncState = SessionRecord.SyncState.SYNCED,
            sweepSubsets = intList(sweep?.optJSONArray("subsets")),
            sweepSteps = intList(sweep?.optJSONArray("steps")),
            sweepStrainWindows = intList(sweep?.optJSONArray("strainWindows")),
            sweepLabels = stringList(sweep?.optJSONArray("labels")),
            lineCutHorizontal = sweep?.optBoolean("lineCutHorizontal", true) ?: true,
            sweepSkipSubsets = intList(skipped?.optJSONArray("subsets")),
            sweepSkipSteps = intList(skipped?.optJSONArray("steps")),
            sweepSkipStrainWindows = intList(skipped?.optJSONArray("strainWindows")),
            sweepSkipCodes = intList(skipped?.optJSONArray("codes")),
        )
    }

    /**
     * The Home-list headline for a restored session: for a sweep, the specimen
     * plus solved/total and subset span; otherwise the converged percentage.
     */
    private fun restoredHeadline(
        meta: JSONObject,
        engine: JSONObject,
        defNames: List<String>,
        stats: List<Float>,
    ): String {
        val sweep = engine.optJSONObject("sweep")
            ?: return String.format(java.util.Locale.US, "%.1f%% converged", stats.getOrElse(15) { 0f })
        val solved = meta.optInt("frameCount", defNames.size)
        val skipCount = sweep.optJSONObject("skipped")?.optJSONArray("subsets")?.length() ?: 0
        val image = defNames.firstOrNull().orEmpty().ifBlank { meta.optString("specimen", "frame") }
        val subsets = intList(sweep.optJSONArray("subsets"))
        val lo = subsets.minOrNull() ?: engine.optInt("subset", 0)
        val hi = subsets.maxOrNull() ?: lo
        return String.format(
            java.util.Locale.US,
            "%s · %d of %d solved · subset %d–%d",
            image,
            solved,
            solved + skipCount,
            lo,
            hi,
        )
    }

    private fun intList(arr: JSONArray?): List<Int> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) add(arr.optInt(i))
    }

    private fun stringList(arr: JSONArray?): List<String> = buildList {
        if (arr == null) return@buildList
        for (i in 0 until arr.length()) add(arr.optString(i))
    }
}
