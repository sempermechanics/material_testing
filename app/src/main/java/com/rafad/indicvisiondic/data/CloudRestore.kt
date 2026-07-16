package com.rafad.indicvisiondic.data

import android.content.Context
import com.rafad.indicvisiondic.data.net.CloudSessionDto
import com.rafad.indicvisiondic.data.net.IndicApi
import com.rafad.indicvisiondic.data.net.TokenProvider
import com.rafad.indicvisiondic.ui.analysis.AnalysisViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File

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
object CloudRestore {

    /** Progress callback: (filesDone, filesTotal). */
    fun interface Progress {
        fun onProgress(done: Int, total: Int)
    }

    /** Cloud analyses available to restore (excludes ones already on this device). */
    suspend fun listRestorable(context: Context): List<CloudSessionDto> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val api = IndicApi(appContext)
        if (!api.enabled) return@withContext emptyList()
        val token = TokenProvider.usableIdToken(appContext) ?: return@withContext emptyList()
        val localIds = SessionStore.list(appContext).map { it.id }.toSet()
        api.listSessions(token).sessions
            .filter { it.status == "COMPLETED" }
            .filter { it.localSessionId.isBlank() || it.localSessionId !in localIds }
    }

    /**
     * Download an analysis and rebuild it locally. Returns the restored local
     * session id, or throws on failure.
     */
    suspend fun restore(
        context: Context,
        sessionId: String,
        progress: Progress? = null,
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val api = IndicApi(appContext)
        val token = TokenProvider.usableIdToken(appContext)
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
        metaTmp.delete()

        // Restore under the original local id when we know it, so a restored
        // session lines up with its cloud copy for future reconciliation.
        val localId = meta.optString("localSessionId").ifBlank {
            manifest.localSessionId.ifBlank { "restored-" + sessionId.take(12) }
        }
        val sessionDir = SessionStore.dirFor(appContext, localId)
        val rawDeformedDir = File(sessionDir, AnalysisViewModel.RAW_DEFORMED_SUBDIR).apply { mkdirs() }

        // 2. Everything else, into the layout a local run would have produced.
        var done = 0
        val total = files.size
        progress?.onProgress(0, total)
        var refPath = ""
        for (f in files) {
            val dest = when (f.role) {
                "metadata" -> File(sessionDir, "metadata.json")
                "dat" -> File(sessionDir, f.name)
                "raw" -> if (f.name == "Reference.png") {
                    File(sessionDir, "reference.png")
                } else {
                    File(rawDeformedDir, f.name)
                }
                // csv/reports are regenerable; keep them beside the session for export.
                else -> File(sessionDir, f.name)
            }
            api.downloadFile(token, f.fileId, dest)
            if (f.role == "raw" && f.name == "Reference.png") refPath = dest.absolutePath
            progress?.onProgress(++done, total)
        }

        // 3. Rebuild the index row from the blueprint.
        SessionStore.upsert(appContext, recordFrom(meta, localId, sessionDir, refPath))
        Timber.i("Restored analysis %s from cloud session %s (%d files)", localId, sessionId, total)
        localId
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
            headline = String.format(java.util.Locale.US, "%.1f%% converged", stats.getOrElse(15) { 0f }),
            engineStats = stats,
            strainMethod = engine.optString("strainMethod", "VSG"),
            pointsConverged = metrics.optInt("pointsConverged", 0),
            avgIterations = metrics.optDouble("avgIterations", 0.0).toFloat(),
            executionTimeMs = metrics.optInt("executionTimeMs", 0),
            // It came from the cloud, so it is by definition backed up.
            syncState = SessionRecord.SyncState.SYNCED,
        )
    }
}
