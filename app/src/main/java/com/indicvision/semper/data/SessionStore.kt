// Session index store: one accessor per query/mutation of the on-disk index,
// with broad catches around JSON/file IO so a corrupt entry never crashes the
// list; hence TooManyFunctions / TooGenericExceptionCaught are suppressed here.
@file:Suppress("TooManyFunctions", "TooGenericExceptionCaught")

package com.indicvision.semper.data

import android.content.Context
import com.indicvision.semper.data.net.TokenStore
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
    // Run metrics — kept here (not just in the worker's input Data) so a
    // re-upload triggered by cloud reconciliation is still complete.
    val strainMethod: String = "",
    val pointsConverged: Int = 0,
    val avgIterations: Float = 0f,
    val executionTimeMs: Int = 0,
    /** Backend session id of the cloud copy — needed to erase it. Blank if never synced. */
    val cloudSessionId: String = "",
    val syncState: SyncState = SyncState.LOCAL_ONLY,

    // ── Parameter sweep (VsgStudy)
    // A sweep varies the settings instead of the image, so [subset], [step] and
    // [strainWindow] above only describe its first frame. These carry the rest,
    // and their emptiness is what marks an ordinary analysis.

    /** Per-frame subset sizes; empty unless this session is a sweep. */
    val sweepSubsets: List<Int> = emptyList(),

    /** Per-frame step sizes. Rendering a frame depends on its own pitch. */
    val sweepSteps: List<Int> = emptyList(),

    /** Per-frame strain windows. */
    val sweepStrainWindows: List<Int> = emptyList(),

    /** Labels naming each combination, shown in the viewer and the report. */
    val sweepLabels: List<String> = emptyList(),

    /** True when the sweep's line cut runs along x. */
    val lineCutHorizontal: Boolean = true,

    // Combinations the engine could not solve — kept so the lattice still
    // shows hollow nodes after a Home reopen (and after a cloud restore).
    val sweepSkipSubsets: List<Int> = emptyList(),
    val sweepSkipSteps: List<Int> = emptyList(),
    val sweepSkipStrainWindows: List<Int> = emptyList(),

    /**
     * Engine code per skipped combination, index-aligned with the lists above.
     * Stored rather than resolved so the lattice can still say *why* each node
     * is hollow after a reopen — without it the reasons only survive until the
     * screen is left.
     */
    val sweepSkipCodes: List<Int> = emptyList(),

    /**
     * Why a run ended before it finished, as an engine/run code, or 0 when it
     * ran to completion. Kept with the analysis because a short run otherwise
     * looks exactly like a shorter test that ran cleanly.
     */
    val stopCode: Int = 0,

    /** Frames the run set out to solve; 0 for records predating this field. */
    val plannedFrameCount: Int = 0,
) {

    /** True when the run stopped itself before working through every frame. */
    val stoppedEarly: Boolean get() = stopCode != 0

    /** True when the frames are parameter combinations rather than images. */
    val isSweep: Boolean get() = sweepSteps.isNotEmpty()

    /** Planned combinations that never produced a frame. */
    val sweepSkipCount: Int get() = minOf(
        sweepSkipSubsets.size,
        sweepSkipSteps.size,
        sweepSkipStrainWindows.size,
    )

    @Serializable
    enum class SyncState {
        LOCAL_ONLY,
        PENDING,
        SYNCED,

        /**
         * Backup was refused for a reason retrying can't fix — the cloud
         * analysis quota is full, the session is too large, or the device
         * isn't authorised. Surfaced on the Home row so it isn't silent.
         */
        FAILED,
    }

    /** True when the frame data is still on this phone (Results can reopen). */
    fun hasLocalData(): Boolean {
        val dir = File(sessionDir)
        return dir.isDirectory && (dir.listFiles { f -> f.extension == "dat" }?.isNotEmpty() == true)
    }
}

/**
 * The local session index behind the Home list: one JSON file in app-private
 * storage plus one directory per session for its frames and reference copy.
 * All methods are synchronous and lock-guarded (the index is small). Prefer
 * calling mutations and [list] from a background dispatcher when on the UI
 * thread — see Home / ResultViewer / SessionListAdapter.
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

    /** Subfolder under [sessionDir] for persisted raw deformed originals. */
    fun rawDeformedDir(sessionDir: File): File =
        File(sessionDir, SessionPaths.RAW_DEFORMED_SUBDIR)

    fun list(context: Context): List<SessionRecord> = synchronized(lock) {
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        try {
            @Suppress("TooGenericExceptionCaught") // corrupt index must never crash Home
            json.decodeFromString<List<SessionRecord>>(f.readText())
                .sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            Timber.e(e, "Session index unreadable; starting fresh")
            emptyList()
        }
    }

    fun get(context: Context, id: String): SessionRecord? = list(context).firstOrNull { it.id == id }

    /**
     * Insert or update a session row. New sessions are hard-stopped when the
     * account is at its analysis quota — re-runs of an existing id still upsert.
     * @param allowOverLimit true for cloud restore (session already counts against quota).
     * @return false if a new session was refused because the limit is reached.
     */
    fun upsert(
        context: Context,
        record: SessionRecord,
        allowOverLimit: Boolean = false,
    ): Boolean = synchronized(lock) {
        val existing = list(context)
        val isNew = existing.none { it.id == record.id }
        if (isNew && !allowOverLimit) {
            val max = TokenStore.effectiveQuotaMax(context)
            val used = maxOf(TokenStore.quotaUsed(context), existing.size)
            if (used >= max) {
                TokenStore.setSessionLimitReached(context, true)
                TokenStore.setQuota(context, used, max, existing.size)
                Timber.w("Hard stop: refusing new session (at %d/%d)", used, max)
                return false
            }
        }
        val next = existing.filterNot { it.id == record.id } + record
        write(context, next)
        TokenStore.refreshSessionLimit(context, next.size)
        true
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

    fun markSynced(context: Context, id: String) = setSyncState(context, id, SessionRecord.SyncState.SYNCED)

    /** Remember which cloud session backs this analysis (so it can be erased). */
    fun setCloudSessionId(context: Context, id: String, cloudSessionId: String) = synchronized(lock) {
        write(
            context,
            list(context).map { if (it.id == id) it.copy(cloudSessionId = cloudSessionId) else it },
        )
    }

    /** Set a session's sync state — used by cloud reconciliation as well as uploads. */
    fun setSyncState(context: Context, id: String, state: SessionRecord.SyncState) = synchronized(lock) {
        write(
            context,
            list(context).map { if (it.id == id) it.copy(syncState = state) else it },
        )
    }

    /** Removes the index row AND the local files. Cloud copies are untouched. */
    fun delete(context: Context, id: String) = synchronized(lock) {
        write(context, list(context).filterNot { it.id == id })
        dirFor(context, id).deleteRecursively()
        TokenStore.refreshSessionLimit(context, list(context).size)
    }

    /**
     * Wipes every local analysis — the index and all per-session directories.
     * Used by account deletion (GDPR); cloud erasure is handled separately.
     */
    fun deleteAll(context: Context) = synchronized(lock) {
        root(context).deleteRecursively()
        root(context).mkdirs()
        TokenStore.refreshSessionLimit(context, 0)
    }

    private fun write(context: Context, records: List<SessionRecord>) {
        indexFile(context).writeText(json.encodeToString(records))
    }
}
