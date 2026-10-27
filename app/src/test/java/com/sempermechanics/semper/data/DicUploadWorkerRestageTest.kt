package com.sempermechanics.semper.data

import android.app.Application
import androidx.work.ListenableWorker
import com.sempermechanics.semper.data.net.SessionCreateResponse
import com.sempermechanics.semper.data.net.SessionUploadsResponse
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.util.Digests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [DicUploadWorker] over a staging that is not the one its cloud session
 * declared: built again under a live pointer, or built on one run and reused on
 * the next. The staged bytes and the declared sha256 must agree, or every
 * restore of the backup fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DicUploadWorkerRestageTest : UploadWorkerHarness() {

    // ── a staging that is not the one the cloud session declared ────────────

    @Test
    fun `a restage under a live session deletes that session first and never completes into it`() {
        seed(cloudSessionId = "cs1")
        dropArchive("Session.zip")
        dropArchive("Extras.zip")
        api.onSessionUploads = { uploading(it) }
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs2", status = "UPLOADING") }
        val uploaded = CopyOnWriteArrayList<String>()
        api.onUpload = { file ->
            if (file.name == "Session.zip") uploaded += Digests.sha256Hex(file)
            file.name to Digests.md5Hex(file)
        }

        assertEquals(ListenableWorker.Result.success(), run())

        // The rebuilt zips have new timestamps: the same size as before, other
        // bytes. Resumed, cs1 would keep the old sha256 and every restore fail.
        assertEquals(listOf("cs1"), api.deleted)
        assertEquals(setOf("cs2"), api.completed.map { it.sessionId }.toSet())
        assertEquals(api.created!!.files.single { it.name == "Session.zip" }.sha256, uploaded.single())
        assertEquals("cs2", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.SYNCED, row().syncState)
    }

    @Test
    fun `a restage whose session delete fails builds nothing and keeps the pointer`() {
        seed(cloudSessionId = "cs1")
        dropArchive("Session.zip")
        dropArchive("Extras.zip")
        api.onSessionUploads = { uploading(it) }
        api.base.onDeleteSession = { _, _ -> throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertEquals("cs1", row().cloudSessionId)
        // Built now, the next run would find the staging whole and resume cs1 with it.
        assertFalse(File(staging, "Session.zip").exists())
        assertTrue(api.calls.none { it == "createSession" || it == "uploadResumable" || it == "completeFile" })
    }

    @Test
    fun `a restage the backend cannot answer for keeps the session and builds nothing`() {
        seed(cloudSessionId = "cs1")
        dropArchive("Session.zip")
        dropArchive("Extras.zip")
        api.onSessionUploads = { throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        assertTrue(api.deleted.isEmpty())
        assertEquals("cs1", row().cloudSessionId)
        assertFalse(File(staging, "Session.zip").exists())
    }

    @Test
    fun `a restage while backup is paused keeps the session, builds nothing and says paused`() {
        seed(cloudSessionId = "cs1")
        dropArchive("Session.zip")
        dropArchive("Extras.zip")
        api.onSessionUploads = { throw apiError(503, "feature_paused") }

        LogCapture().use { log ->
            assertEquals(ListenableWorker.Result.retry(), run())
            assertTrue(log.warnings.toString(), log.warnings.any { "feature_paused" in it })
        }

        assertTrue(api.deleted.isEmpty())
        assertEquals("cs1", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.PENDING, row().syncState)
        assertFalse(File(staging, "Session.zip").exists())
    }

    @Test
    fun `a lost staging under a session already complete records the sync and keeps the backup`() {
        seed(cloudSessionId = "cs1")
        dropArchive("Session.zip")
        dropArchive("Extras.zip")
        api.onSessionUploads = { SessionUploadsResponse(sessionId = it, status = "COMPLETED") }

        assertEquals(ListenableWorker.Result.success(), run())

        assertTrue("a finished backup is never deleted to restage it", api.deleted.isEmpty())
        assertEquals("cs1", row().cloudSessionId)
        assertEquals(SessionRecord.SyncState.SYNCED, row().syncState)
    }

    // ── the staging's binding to its session (TD-282) ──────────────────────

    @Test
    fun `a whole staging bound to another session releases this one and is declared afresh`() {
        seed(cloudSessionId = "cs1")
        bind("cs0")
        api.onSessionUploads = { uploading(it) }
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs2", status = "UPLOADING") }

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(listOf("cs1"), api.deleted)
        assertEquals(setOf("cs2"), api.completed.map { it.sessionId }.toSet())
        assertEquals("cs2", row().cloudSessionId)
    }

    @Test
    fun `a whole staging with no binding under a pointer is not resumed blind`() {
        // An older build's staging, or a run that died between storing the
        // pointer and the binding: nothing says cs1 declared these bytes.
        seed(cloudSessionId = "cs1")
        File(staging, ".declared").delete()
        api.onSessionUploads = { uploading(it) }
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs2", status = "UPLOADING") }

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(listOf("cs1"), api.deleted)
        assertEquals(setOf("cs2"), api.completed.map { it.sessionId }.toSet())
    }

    @Test
    fun `a binding whose declared hash is not the staged archive's is not resumed`() {
        seed(cloudSessionId = "cs1")
        bind("cs1", stagedHashes() + ("Session.zip" to "0".repeat(64)))
        api.onSessionUploads = { uploading(it) }
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs2", status = "UPLOADING") }

        assertEquals(ListenableWorker.Result.success(), run())

        assertEquals(listOf("cs1"), api.deleted)
        assertEquals("cs2", row().cloudSessionId)
    }

    @Test
    fun `a created session binds the staging to it with the hashes it declared`() {
        seed()
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs9", status = "UPLOADING") }
        api.onSessionUploads = { uploading(it) }
        api.onComplete = { _, _ -> throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())

        val bound = binding()!!
        assertEquals("cs9", bound.cloudSessionId)
        assertEquals(api.created!!.files.associate { it.name to it.sha256 }, bound.sha256ByName)
        // The next run resumes cs9 with the same staging: bound, nothing released.
        api.onComplete = { _, req -> api.completed += req }
        assertEquals(ListenableWorker.Result.success(), run())
        assertTrue(api.deleted.isEmpty())
        assertEquals(setOf("cs9"), api.completed.map { it.sessionId }.toSet())
    }

    // ── archives built on one run, reused on the next ───────────────────────

    @Test
    fun `archives built on one run are reused byte for byte and declared once`() {
        seed(cloudSessionId = "gone")
        dropArchive("Session.zip")
        dropArchive("Extras.zip")
        api.onSessionUploads = { sid ->
            if (sid == "gone") throw apiError(404, "session_not_found")
            uploading(sid)
        }
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs9", status = "UPLOADING") }
        api.onComplete = { _, _ -> throw apiError(503, "firestore_unreachable") }

        // Run 1 finds its old session gone and drops the pointer, builds both
        // archives from the staged files, and declares them in a new session.
        assertEquals(ListenableWorker.Result.retry(), run())
        assertEquals(listOf("gone"), api.deleted)
        val names = listOf("Session.zip", "Extras.zip")
        val built = names.associateWith { staged(it) }
        for (name in names) {
            assertEquals(name, Digests.sha256Hex(File(staging, name)), File(staging, "$name.sha256").readText())
        }
        val restore = entries(File(staging, "Session.zip"))
        assertTrue(restore.toString(), restore.any { it.startsWith("raw/") } && restore.any { it.startsWith("dat/") })
        val extras = entries(File(staging, "Extras.zip"))
        for (role in listOf("csv/", "reports/", "processed/")) {
            assertTrue("$role in $extras", extras.any { it.startsWith(role) })
        }

        // Runs 2 and 3 resume that session with the archives run 1 built.
        assertEquals(ListenableWorker.Result.retry(), run())
        val declared = api.created!!.files.associateBy { it.name }
        for (name in names) {
            assertTrue("$name reused as built", built.getValue(name).contentEquals(staged(name)))
            assertEquals(name, Digests.sha256Hex(File(staging, name)), declared.getValue(name).sha256)
            assertEquals(name, File(staging, name).length(), declared.getValue(name).bytes)
        }
        assertEquals(ListenableWorker.Result.retry(), run())

        for (name in names) assertTrue("$name unchanged", built.getValue(name).contentEquals(staged(name)))
        assertEquals(listOf("createSession"), api.calls.filter { it == "createSession" })
    }

    @Test
    fun `a staging with only Extras zip rebuilds both archives from the staged files`() {
        seed(cloudSessionId = "cs1")
        dropArchive("Session.zip")
        val seeded = staged("Extras.zip")
        api.onSessionUploads = { uploading(it) }
        api.onCreateSession = { SessionCreateResponse(sessionId = "cs2", status = "UPLOADING") }
        api.onComplete = { _, _ -> throw apiError(503, "firestore_unreachable") }

        assertEquals(ListenableWorker.Result.retry(), run())
        // cs1 declared the archives that are gone; the rebuilt ones go into a new session.
        assertEquals(listOf("cs1"), api.deleted)
        assertEquals("cs2", row().cloudSessionId)

        // Not reusable without Session.zip, so Extras.zip is packed again from
        // what is staged, with a sidecar that matches it.
        val extras = File(staging, "Extras.zip")
        assertFalse(seeded.contentEquals(extras.readBytes()))
        assertTrue(entries(extras).any { it.startsWith("reports/") })
        assertEquals(Digests.sha256Hex(extras), File(staging, "Extras.zip.sha256").readText())
        val built = listOf("Session.zip", "Extras.zip").associateWith { staged(it) }

        assertEquals(ListenableWorker.Result.retry(), run())

        for ((name, bytes) in built) assertTrue("$name reused as built", bytes.contentEquals(staged(name)))
    }
}
