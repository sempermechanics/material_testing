package com.rafad.indicvisiondic.settings

import com.rafad.indicvisiondic.data.SessionRecord
import com.rafad.indicvisiondic.data.net.CloudSessionDto
import com.rafad.indicvisiondic.ui.settings.AnalysisEntries
import com.rafad.indicvisiondic.ui.settings.AnalysisLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The settings page shows one row per analysis, joining the local index with
 * the backend's list. These pin the join and — more importantly — what a row
 * claims when the backend could not be reached.
 */
class AnalysisEntriesTest {

    private fun record(
        id: String,
        name: String = id,
        syncState: SessionRecord.SyncState = SessionRecord.SyncState.SYNCED,
        cloudSessionId: String = "",
    ) = SessionRecord(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L,
        frameCount = 1,
        subset = 41,
        step = 5,
        strainWindow = 15,
        imgW = 100,
        imgH = 100,
        roiX = 0,
        roiY = 0,
        roiW = 100,
        roiH = 100,
        refPath = "/ref.png",
        refName = "ref.png",
        sessionDir = "/sessions/$id",
        cloudSessionId = cloudSessionId,
        syncState = syncState,
    )

    @Test
    fun `cloud row joins its local record by localSessionId`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", name = "Steel plate")),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-1")),
        )

        assertEquals(1, entries.size)
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, entries[0].location)
        assertEquals("Steel plate", entries[0].name)
    }

    @Test
    fun `cloud row joins by stored cloud id when localSessionId is blank`() {
        // Sessions uploaded before the localSessionId link existed.
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", cloudSessionId = "cloud-1")),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-1", localSessionId = "")),
        )

        assertEquals(1, entries.size)
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, entries[0].location)
    }

    @Test
    fun `a backup with no local copy becomes a cloud-only row, after the local ones`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1")),
            cloud = listOf(
                CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-1"),
                CloudSessionDto(sessionId = "cloud-2", localSessionId = "gone", specimen = "Old beam"),
            ),
        )

        assertEquals(2, entries.size)
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, entries[0].location)
        assertEquals(AnalysisLocation.CLOUD_ONLY, entries[1].location)
        assertEquals("Old beam", entries[1].name)
        assertNull(entries[1].record)
    }

    @Test
    fun `a cloud-only row falls back to its session id when unnamed`() {
        val entries = AnalysisEntries.merge(
            records = emptyList(),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-9", specimen = null)),
        )

        assertEquals("cloud-9", entries[0].name)
    }

    @Test
    fun `local-only analyses report phone only`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", syncState = SessionRecord.SyncState.LOCAL_ONLY)),
            cloud = emptyList(),
        )

        assertEquals(AnalysisLocation.PHONE_ONLY, entries[0].location)
    }

    /**
     * The regression that matters: an unreachable backend yields an empty cloud
     * list, and a backed-up analysis must NOT then read as "on phone only" —
     * that tells the user their backup is gone when it was merely unverified.
     */
    @Test
    fun `a synced analysis is not demoted to phone only when the cloud cannot be listed`() {
        val entries = AnalysisEntries.merge(
            records = listOf(record("local-1", syncState = SessionRecord.SyncState.SYNCED)),
            cloud = emptyList(),
        )

        assertEquals(AnalysisLocation.PHONE_SYNC_STATE, entries[0].location)
    }

    @Test
    fun `pending and failed backups keep their own state, not phone only`() {
        val entries = AnalysisEntries.merge(
            records = listOf(
                record("local-1", syncState = SessionRecord.SyncState.PENDING),
                record("local-2", syncState = SessionRecord.SyncState.FAILED),
            ),
            cloud = emptyList(),
        )

        assertEquals(AnalysisLocation.PHONE_SYNC_STATE, entries[0].location)
        assertEquals(AnalysisLocation.PHONE_SYNC_STATE, entries[1].location)
    }

    /**
     * Two records carrying the same stale cloud id must not both show that
     * backup: the row's bin deletes by cloud id, so a duplicate would offer to
     * delete the same thing twice.
     */
    @Test
    fun `one backup is claimed by a single record`() {
        val entries = AnalysisEntries.merge(
            records = listOf(
                record("local-1", cloudSessionId = "cloud-1"),
                record("local-2", cloudSessionId = "cloud-1"),
            ),
            cloud = listOf(CloudSessionDto(sessionId = "cloud-1", localSessionId = "local-1")),
        )

        assertEquals(2, entries.size)
        assertEquals(AnalysisLocation.PHONE_AND_CLOUD, entries[0].location)
        assertEquals("cloud-1", entries[0].cloud?.sessionId)
        assertNull(entries[1].cloud)
    }
}
