package com.rafad.indicvisiondic.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * One completed (or re-run) analysis as shown on the Home list. Metadata only —
 * the heavy artifacts (.dat frames, reference copy) live in [SessionStore.dirFor],
 * and full result files live in the cloud once synced.
 */
@Serializable
data class SessionRecord(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val frameCount: Int,
    // Engine parameters of the latest run
    val subset: Int,
    val step: Int,
    val strainWindow: Int,
    val use6x6: Boolean = false,
    // Geometry
    val imgW: Int,
    val imgH: Int,
    val roiX: Int,
    val roiY: Int,
    val roiW: Int,
    val roiH: Int,
    // Files
    val refPath: String,
    val refName: String,
    val sessionDir: String,
    val defNames: List<String> = emptyList(),
    // Headline shown on the row, e.g. "97.5% converged"
    val headline: String = "",
    val engineStats: List<Float> = emptyList(),
    val syncState: SyncState = SyncState.LOCAL_ONLY,
) {
    @Serializable
    enum class SyncState { LOCAL_ONLY, PENDING, SYNCED }

    /** True when the frame data is still on this phone (Results can reopen). */
    fun hasLocalData(): Boolean {
        val dir = File(sessionDir)
        return dir.isDirectory && (dir.listFiles { f -> f.extension == "dat" }?.isNotEmpty() == true)
    }
}

/**
 * The local session index behind the Home list: one JSON file in app-private
 * storage plus one directory per session for its frames and reference copy.
 * All methods are synchronous and cheap (the index is small); call off the
 * main thread when convenient but correctness does not require it.
 */
object SessionStore {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }
    private val lock = Any()

    private fun root(context: Context): File = File(context.filesDir, "sessions").apply { mkdirs() }

    private fun indexFile(context: Context): File = File(root(context), "index.json")

    /** Directory holding a session's .dat frames and reference copy. */
    fun dirFor(context: Context, id: String): File = File(root(context), id).apply { mkdirs() }

    fun list(context: Context): List<SessionRecord> = synchronized(lock) {
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        try {
            @Suppress("TooGenericExceptionCaught") // corrupt index must never crash Home
            json.decodeFromString<List<SessionRecord>>(f.readText())
                .sortedByDescending { it.updatedAt }
        } catch (e: Exception) {
            Timber.e(e, "Session index unreadable; starting fresh")
            emptyList()
        }
    }

    fun get(context: Context, id: String): SessionRecord? = list(context).firstOrNull { it.id == id }

    fun upsert(context: Context, record: SessionRecord) = synchronized(lock) {
        val rest = list(context).filterNot { it.id == record.id }
        write(context, rest + record)
    }

    fun rename(context: Context, id: String, newName: String) = synchronized(lock) {
        write(
            context,
            list(context).map {
                if (it.id == id) it.copy(name = newName, updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    fun updateHeadline(context: Context, id: String, headline: String) = synchronized(lock) {
        write(
            context,
            list(context).map {
                if (it.id == id) it.copy(headline = headline, updatedAt = System.currentTimeMillis()) else it
            },
        )
    }

    fun markSynced(context: Context, id: String) = synchronized(lock) {
        write(
            context,
            list(context).map {
                if (it.id == id) it.copy(syncState = SessionRecord.SyncState.SYNCED) else it
            },
        )
    }

    /** Removes the index row AND the local files. Cloud copies are untouched. */
    fun delete(context: Context, id: String) = synchronized(lock) {
        write(context, list(context).filterNot { it.id == id })
        dirFor(context, id).deleteRecursively()
    }

    private fun write(context: Context, records: List<SessionRecord>) {
        indexFile(context).writeText(json.encodeToString(records))
    }
}
