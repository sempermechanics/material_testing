package com.sempermechanics.semper.data.cloud.sync

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.cloud.CloudBackupListing
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudSessionDto
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

/**
 * One run of the sync from other phones against [FakeCloudApi]: it asks the
 * backend nothing unless the switch is on and restore is open, lists this
 * app's backups only, and asks for a restore of each backup no row on this
 * phone claims, once.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneSyncRunTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()

    /** What each run asked [RestoreStart] for. */
    private val asked = mutableListOf<List<RestoreStart.Target>>()

    private fun run(restoring: Set<String> = emptySet()): PhoneSyncRun.Outcome = runBlocking {
        PhoneSyncRun.run(
            context,
            api = api,
            tokens = tokens,
            restoring = { restoring },
            start = { _, targets ->
                asked += targets
                RestoreStart.Counts(started = targets.size, alreadyRunning = 0)
            },
        )
    }

    private fun licensed(killSwitches: List<String>? = null) {
        AppRemoteConfig.apply(
            context,
            AppConfigDto(mode = "licensed", cloudBackupEnabled = true, killSwitches = killSwitches),
        )
    }

    private fun listing(vararg sessions: CloudSessionDto) {
        api.onListSessions = { _, _ -> SessionsResponse(sessions.toList()) }
    }

    // ── When it asks nothing ────────────────────────────────────────────────

    @Test
    fun `with the switch off nothing is listed or restored`() {
        licensed()
        AppSettings.setSyncFromOtherPhonesEnabled(context, false)

        assertEquals(PhoneSyncRun.Outcome.Skipped(PhoneSync.Skip.SWITCHED_OFF), run())
        assertTrue(api.calls.isEmpty())
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a demo account asks nothing`() {
        assertEquals(PhoneSyncRun.Outcome.Skipped(PhoneSync.Skip.RESTORE_OFF), run())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `the restore kill switch stops it`() {
        licensed(killSwitches = listOf("restore"))

        assertEquals(PhoneSyncRun.Outcome.Skipped(PhoneSync.Skip.RESTORE_OFF), run())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `an unreadable analysis list stops it, since every backup would look missing`() {
        licensed()
        val root = File(context.filesDir, SessionPaths.SESSIONS_ROOT).apply { mkdirs() }
        File(root, SessionPaths.INDEX_JSON).writeText("{ not json")
        File(root, SessionPaths.INDEX_JSON + ".bak").writeText("{ not json either")

        assertEquals(PhoneSyncRun.Outcome.Skipped(PhoneSync.Skip.INDEX_UNREADABLE), run())
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun `a failed listing restores nothing and is asked again next run`() {
        licensed()
        api.onListSessions = { _, _ -> throw IOException("offline") }

        assertEquals(PhoneSyncRun.Outcome.ListFailed, run())
        assertTrue(asked.isEmpty())
        assertTrue(PhoneSyncRun.triedIds(context).isEmpty())
    }

    // ── What it restores ────────────────────────────────────────────────────

    @Test
    fun `only this app's backups are listed`() {
        licensed()
        listing()

        run()

        // No `app`: the backend scopes the listing to this app's X-App-Id.
        assertEquals(listOf("listSessions"), api.calls)
    }

    @Test
    fun `a backup from another phone is restored into its own row, named as Home names it`() {
        licensed()
        listing(dto("c1", "s1", name = "Beam test"))

        val outcome = run()

        assertEquals(PhoneSyncRun.Outcome.Queued(1, RestoreStart.Counts(1, 0)), outcome)
        assertEquals(listOf(RestoreStart.Target("c1", "s1", "Beam test")), asked.single())
    }

    @Test
    fun `a backup with no local id or name gets the row and name Home's card gives it`() {
        licensed()
        listing(dto("cloud-0123456789abcdef", localId = "", name = null))

        run()

        val target = asked.single().single()
        assertEquals("restored-cloud-012345", target.targetLocalId)
        assertEquals("Analysis from your cloud backup", target.name)
    }

    @Test
    fun `rows on this phone, removed ones included, are left exactly as they are`() {
        licensed()
        val mine = row("s1", cloudId = "c1")
        val freed = row("s2", cloudId = "c2", frames = 0)
        store(mine)
        store(freed)
        listing(dto("c1", "s1"), dto("c2", "s2"), dto("c3", "other"))

        run()

        assertEquals(listOf("c3"), asked.single().map { it.cloudSessionId })
        assertEquals(mine, SessionStore.get(context, "s1"))
        assertEquals(freed, SessionStore.get(context, "s2"))
    }

    @Test
    fun `backups hidden from Home or already restoring are skipped`() {
        licensed()
        listing(dto("c1", "a"), dto("c2", "b"), dto("c3", "c"))
        CloudBackupListing.record(context, listOf(dto("c1", "a")))
        CloudBackupListing.hide(context, listOf("c1"))

        run(restoring = setOf("c2"))

        assertEquals(listOf("c3"), asked.single().map { it.cloudSessionId })
    }

    @Test
    fun `each backup is asked for once, so a failed or cancelled restore is not retried every run`() {
        licensed()
        listing(dto("c1", "a"))

        run()
        // The restore failed and took its row with it: the phone has no row again.
        val second = run()

        assertEquals(1, asked.size)
        assertEquals(PhoneSyncRun.Outcome.Queued(0, RestoreStart.Counts(0, 0)), second)
        assertEquals(setOf("c1"), PhoneSyncRun.triedIds(context))
    }

    @Test
    fun `the listing is saved for Home's card`() {
        licensed()
        listing(dto("c1", "a"), dto("c2", "b", status = "UPLOADING"))

        run()

        assertEquals(listOf("c1"), CloudBackupListing.read(context).map { it.cloudId })
    }

    @Test
    fun `sign-out forgets what was tried, with the listing`() {
        licensed()
        listing(dto("c1", "a"))
        run()

        CloudBackupListing.clear(context)

        assertTrue(PhoneSyncRun.triedIds(context).isEmpty())
    }

    // ── Through RestoreStart, as Home's card ────────────────────────────────

    @SuppressLint("RestrictedApi")
    @Test
    fun `the restore is queued through RestoreStart, its row written first`() {
        // A worker executor that never runs anything: the restore stays queued.
        val config = Configuration.Builder().setExecutor { }.build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        try {
            licensed()
            listing(dto("c1", "s1", name = "Beam test"))

            val outcome = runBlocking { PhoneSyncRun.run(context, api = api, tokens = tokens) }

            assertEquals(PhoneSyncRun.Outcome.Queued(1, RestoreStart.Counts(1, 0)), outcome)
            val row = SessionStore.get(context, "s1")!!
            assertEquals("c1", row.cloudSessionId)
            assertEquals("Beam test", row.name)
            val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("restore-c1").get().single()
            assertTrue(!work.state.isFinished)
            assertEquals(setOf("c1"), PhoneSyncRun.restoringIds(context))
        } finally {
            WorkManagerTestInitHelper.closeWorkDatabase()
            WorkManagerImpl.setDelegate(null)
        }
    }

    private fun dto(
        cloudId: String,
        localId: String,
        name: String? = null,
        status: String? = "COMPLETED",
    ) = CloudSessionDto(sessionId = cloudId, localSessionId = localId, name = name, status = status)

    private fun row(id: String, cloudId: String, frames: Int = 1) = sessionRecord(
        id = id,
        frameCount = frames,
        sessionDir = SessionStore.dirFor(context, id).absolutePath,
        syncState = SessionRecord.SyncState.SYNCED,
        cloudSessionId = cloudId,
    )

    private fun store(record: SessionRecord) = assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
}
