@file:Suppress("MagicNumber")

package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.SessionMetadataSync
import com.indicvision.semper.data.SessionMetadataSync.Outcome
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionRecord.SyncState
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.SpecimenGeometry
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.ListSessionsResponse
import com.indicvision.semper.data.net.QuotaDto
import com.indicvision.semper.report.BeamDeflection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * TD-150 / ADR-013: a deflection correction set after the backup reaches the
 * cloud copy's metadata.json, so a restore brings it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionMetadataSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private val queued = mutableListOf<String>()

    private val corrected = BeamDeflection.Correction(1.05f, -0.12f)

    @Before
    fun setUp() {
        File(context.filesDir, "sessions").deleteRecursively()
        api.onReplaceSessionMetadata = { _, sid, json -> sent += sid to JSONObject(json) }
        CloudSync.queueMetadata = { _, id -> queued += id }
        CloudSync.queueUpload = { _, _ -> }
        AppRemoteConfig.clear(context)
    }

    @After
    fun tearDown() {
        CloudSync.queueMetadata = SessionMetadataSync::enqueue
        CloudSync.queueUpload = CloudSync::enqueueUpload
        AppRemoteConfig.clear(context)
    }

    // ── Marking ─────────────────────────────────────────────────────────────

    @Test
    fun `a correction on a backed-up session marks its metadata stale`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")

        SessionStore.setDeflectionCorrection(context, "s1", corrected)

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `a correction on a session never backed up marks nothing`() {
        store("s1", SyncState.LOCAL_ONLY)

        SessionStore.setDeflectionCorrection(context, "s1", corrected)

        assertFalse(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `applying the same correction again marks nothing`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", correction = corrected)

        SessionStore.setDeflectionCorrection(context, "s1", corrected)

        assertFalse(SessionStore.get(context, "s1")!!.metadataStale)
    }

    // ── Sending ─────────────────────────────────────────────────────────────

    @Test
    fun `a stale backed-up session sends metadata carrying the correction, then clears`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")
        SessionStore.setDeflectionCorrection(context, "s1", corrected)

        assertEquals(Outcome.DONE, send("s1"))

        val (sid, json) = sent.single()
        assertEquals("c1", sid)
        assertEquals("s1", json.getString("localSessionId"))
        val geometry = json.getJSONObject("test").getJSONObject("geometry")
        assertEquals(1.05, geometry.getDouble("deflectionScale"), 1e-6)
        assertEquals(-0.12, geometry.getDouble("deflectionBiasMm"), 1e-6)
        assertFalse(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `nothing is sent for a row that is not stale`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")

        assertEquals(Outcome.DONE, send("s1"))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `an upload still in flight is waited for`() {
        store("s1", SyncState.PENDING, stale = true)

        assertEquals(Outcome.WAIT, send("s1"))

        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a correction made while the send was in flight is sent again`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")
        SessionStore.setDeflectionCorrection(context, "s1", corrected)
        api.onReplaceSessionMetadata = { _, sid, json ->
            sent += sid to JSONObject(json)
            SessionStore.setDeflectionCorrection(context, "s1", BeamDeflection.Correction(1.1f, 0f))
        }

        assertEquals(Outcome.RETRY, send("s1"))

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `offline, a busy backend and an unfinished cloud upload are retried, the row still marked`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", stale = true)

        assertEquals(Outcome.RETRY, sendFailing(IOException("offline")))
        assertEquals(Outcome.RETRY, sendFailing(IndicApi.ApiException(503, "{}")))
        assertEquals(
            Outcome.WAIT,
            sendFailing(IndicApi.ApiException(409, """{"detail":"session_not_complete"}""")),
        )

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `a backend without the route leaves the row for the next reconcile`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", stale = true)
        api.onReplaceSessionMetadata = { _, _, _ -> throw IndicApi.ApiException(404, "") }

        assertEquals(Outcome.LATER, send("s1"))

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    // ── Reconcile ───────────────────────────────────────────────────────────

    @Test
    fun `a reconcile queues a send for a backed-up row still marked`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", stale = true)
        store("s2", SyncState.SYNCED, cloudId = "c2")
        api.onGetConfig = { throw IOException("config down") }
        api.onListSessions = { _, _ ->
            ListSessionsResponse(
                sessions = listOf(
                    CloudSessionDto(sessionId = "c1", localSessionId = "s1", status = "COMPLETED"),
                    CloudSessionDto(sessionId = "c2", localSessionId = "s2", status = "COMPLETED"),
                ),
                quota = QuotaDto(used = 2, max = 25),
            )
        }

        runBlocking { CloudSync.reconcile(context, deep = true, api = api, tokens = tokens) }

        assertEquals(listOf("s1"), queued)
    }

    private fun send(id: String) = runBlocking { SessionMetadataSync.send(context, id, api, tokens) }

    private fun sendFailing(error: Exception): Outcome {
        api.onReplaceSessionMetadata = { _, _, _ -> throw error }
        return send("s1")
    }

    private fun store(
        id: String,
        state: SyncState,
        cloudId: String = "",
        stale: Boolean = false,
        correction: BeamDeflection.Correction = BeamDeflection.Correction.NONE,
    ) = assertTrue(
        SessionStore.upsert(
            context,
            SessionRecord(
                id = id,
                name = id,
                createdAt = 1L,
                updatedAt = 1L,
                frameCount = 2,
                subset = 41,
                step = 5,
                strainWindow = 9,
                imgW = 640,
                imgH = 480,
                roiX = 0,
                roiY = 0,
                roiW = 640,
                roiH = 480,
                refPath = "/x/ref.png",
                refName = "ref.png",
                sessionDir = "/x",
                defNames = listOf("d1.png", "d2.png"),
                testType = "bending",
                geometry = SpecimenGeometry(spanMm = 935f, widthMm = 150f, thicknessMm = 6.38f)
                    .withCorrection(correction),
                loadsN = listOf(0f, 42f),
                cloudSessionId = cloudId,
                syncState = state,
                metadataStale = stale,
            ),
        ),
    )
}
