package com.rafad.indicvisiondic.ui.settings

import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.data.net.CloudSessionDto

/** Where one analysis lives, which decides its row's wording and actions. */
enum class AnalysisLocation {
    PHONE_AND_CLOUD,
    CLOUD_ONLY,
    PHONE_ONLY,

    /**
     * On this phone, carrying a backup state the cloud could not confirm on
     * this pass — offline, signed out, or the backend errored. Distinct from
     * [PHONE_ONLY] on purpose: telling a user with a healthy backup that their
     * analysis is "on phone only" reads as "your backup is gone".
     */
    PHONE_SYNC_STATE,
}

/** One analysis, wherever it lives. A null half means it is not there. */
data class AnalysisEntry(
    val name: String,
    val record: SessionRecord?,
    val cloud: CloudSessionDto?,
) {
    val location: AnalysisLocation
        get() = when {
            cloud != null && record == null -> AnalysisLocation.CLOUD_ONLY
            cloud != null -> AnalysisLocation.PHONE_AND_CLOUD
            record?.syncState == SessionRecord.SyncState.LOCAL_ONLY -> AnalysisLocation.PHONE_ONLY
            else -> AnalysisLocation.PHONE_SYNC_STATE
        }
}

/**
 * Joins what is on this phone with what the backend reports, for the settings
 * page's single per-analysis list.
 */
object AnalysisEntries {

    /**
     * Local records first (they are the working set), then backups with no copy
     * on this phone. Cloud rows link by [CloudSessionDto.localSessionId],
     * falling back to the stored cloud id for sessions uploaded before that
     * link existed — the same fallback the erase path uses.
     *
     * Pass an empty [cloud] list when the backend could not be reached: local
     * records then keep their own sync state rather than being demoted to
     * "phone only".
     *
     * A backup is claimed by at most one record — first match wins. Otherwise
     * two records carrying the same stale cloud id would each show the same
     * backup, with two bins deleting the one thing.
     */
    fun merge(records: List<SessionRecord>, cloud: List<CloudSessionDto>): List<AnalysisEntry> {
        val byLocalId = cloud.filter { it.localSessionId.isNotBlank() }.associateBy { it.localSessionId }
        val matched = mutableSetOf<String>()
        val onPhone = records.map { record ->
            val match = byLocalId[record.id]?.takeIf { it.sessionId !in matched }
                ?: cloud.firstOrNull {
                    record.cloudSessionId.isNotBlank() &&
                        it.sessionId == record.cloudSessionId &&
                        it.sessionId !in matched
                }
            match?.let { matched += it.sessionId }
            AnalysisEntry(record.name, record, match)
        }
        val cloudOnly = cloud.filterNot { it.sessionId in matched }
            .map { AnalysisEntry(it.specimen ?: it.sessionId, null, it) }
        return onPhone + cloudOnly
    }
}
