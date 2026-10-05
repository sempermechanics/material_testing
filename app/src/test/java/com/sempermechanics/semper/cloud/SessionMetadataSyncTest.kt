package com.sempermechanics.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.cloud.SessionMetadataSync
import com.sempermechanics.semper.data.cloud.SessionMetadataSync.Outcome
import com.sempermechanics.semper.data.mechanical.CurveCorrection
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.QuotaDto
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.session.SessionRecord.SyncState
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.report.BeamDeflection
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
import java.io.File
import java.io.IOException

/**
 * TD-150, TD-152 / ADR-013: a rename, a deflection correction (bending) or a
 * curve correction (tensile) made after the backup reaches the cloud copy's
 * metadata.json, so a restore brings it back.
 */
@RunWith(RobolectricTestRunner::class)
class SessionMetadataSyncTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private val queued = mutableListOf<String>()

    private val corrected = BeamDeflection.Correction(1.05f, -0.12f)
    private val matched = CurveCorrection(1.25f, 57.5f, 1f, 0f)

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

    @Test
    fun `a curve correction on a backed-up tensile session marks its metadata stale`() {
        storeTensile("t1", SyncState.SYNCED, cloudId = "c1")

        SessionStore.setCurveCorrection(context, "t1", matched)

        assertTrue(SessionStore.get(context, "t1")!!.metadataStale)
    }

    @Test
    fun `a curve correction on a session never backed up, or the same one again, marks nothing`() {
        storeTensile("t1", SyncState.LOCAL_ONLY)
        storeTensile("t2", SyncState.SYNCED, cloudId = "c2", curve = matched)

        SessionStore.setCurveCorrection(context, "t1", matched)
        SessionStore.setCurveCorrection(context, "t2", matched)

        assertFalse(SessionStore.get(context, "t1")!!.metadataStale)
        assertFalse(SessionStore.get(context, "t2")!!.metadataStale)
    }

    @Test
    fun `renaming a backed-up session, or one whose upload is on its way, marks it`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")
        store("s2", SyncState.PENDING)

        SessionStore.rename(context, "s1", "Beam B")
        SessionStore.rename(context, "s2", "Beam C")

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
        assertTrue(SessionStore.get(context, "s2")!!.metadataStale)
    }

    @Test
    fun `renaming a session never backed up, or to the same name, marks nothing`() {
        store("s1", SyncState.LOCAL_ONLY)
        store("s2", SyncState.SYNCED, cloudId = "c2")

        SessionStore.rename(context, "s1", "Beam B")
        SessionStore.rename(context, "s2", "s2")

        assertFalse(SessionStore.get(context, "s1")!!.metadataStale)
        assertFalse(SessionStore.get(context, "s2")!!.metadataStale)
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
    fun `a stale tensile session sends metadata carrying the curve correction, then clears`() {
        storeTensile("t1", SyncState.SYNCED, cloudId = "c1")
        SessionStore.setCurveCorrection(context, "t1", matched)

        assertEquals(Outcome.DONE, send("t1"))

        val (sid, json) = sent.single()
        assertEquals("c1", sid)
        val curve = json.getJSONObject("test").getJSONObject("curveCorrection")
        assertEquals(1.25, curve.getDouble("strainScale"), 1e-6)
        assertEquals(57.5, curve.getDouble("strainBiasMilli"), 1e-6)
        assertFalse(SessionStore.get(context, "t1")!!.metadataStale)
    }

    @Test
    fun `a curve correction made while the send was in flight is sent again`() {
        storeTensile("t1", SyncState.SYNCED, cloudId = "c1")
        SessionStore.setCurveCorrection(context, "t1", matched)
        api.onReplaceSessionMetadata = { _, sid, json ->
            sent += sid to JSONObject(json)
            SessionStore.setCurveCorrection(context, "t1", CurveCorrection(1.3f, 0f, 1f, 0f))
        }

        assertEquals(Outcome.RETRY, send("t1"))

        assertTrue(SessionStore.get(context, "t1")!!.metadataStale)
    }

    @Test
    fun `a renamed session sends metadata carrying the new name, then clears`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")
        SessionStore.rename(context, "s1", "Beam B")

        assertEquals(Outcome.DONE, send("s1"))

        assertEquals("Beam B", sent.single().second.getString("name"))
        assertFalse(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `a rename made while the send was in flight is sent again`() {
        store("s1", SyncState.SYNCED, cloudId = "c1")
        SessionStore.setDeflectionCorrection(context, "s1", corrected)
        api.onReplaceSessionMetadata = { _, sid, json ->
            sent += sid to JSONObject(json)
            SessionStore.rename(context, "s1", "Beam C")
        }

        assertEquals(Outcome.RETRY, send("s1"))

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
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
        assertEquals(Outcome.RETRY, sendFailing(ApiException(503, "{}")))
        assertEquals(
            Outcome.WAIT,
            sendFailing(ApiException(409, """{"detail":"session_not_complete"}""")),
        )

        assertTrue(SessionStore.get(context, "s1")!!.metadataStale)
    }

    @Test
    fun `a backend without the route leaves the row for the next reconcile`() {
        store("s1", SyncState.SYNCED, cloudId = "c1", stale = true)
        api.onReplaceSessionMetadata = { _, _, _ -> throw ApiException(404, "") }

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
            SessionsResponse(
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
            sessionRecord(
                id = id,
                frameCount = 2,
                strainWindow = 9,
                imgW = 640,
                imgH = 480,
                refPath = "/x/ref.png",
                sessionDir = "/x",
                defNames = listOf("d1.png", "d2.png"),
                cloudSessionId = cloudId,
                syncState = state,
            ).copy(
                metadataStale = stale,
                testType = "bending",
                geometry = SpecimenGeometry(spanMm = 935f, widthMm = 150f, thicknessMm = 6.38f)
                    .withCorrection(correction),
                loadsN = listOf(0f, 42f),
            ),
        ),
    )

    /** A tensile row: [store]'s record, typed tensile and carrying [curve]. */
    private fun storeTensile(
        id: String,
        state: SyncState,
        cloudId: String = "",
        curve: CurveCorrection = CurveCorrection.NONE,
    ) {
        store(id, state, cloudId)
        val record = SessionStore.get(context, id)!!
        assertTrue(SessionStore.upsert(context, record.copy(testType = "tensile", curveCorrection = curve)))
    }
}
