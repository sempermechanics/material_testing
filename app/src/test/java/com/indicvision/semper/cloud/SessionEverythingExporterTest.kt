package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.DicResult
import com.indicvision.semper.data.SessionEverythingExporter
import com.indicvision.semper.data.SessionPaths
import com.indicvision.semper.data.SessionRecord
import com.indicvision.semper.data.SessionStore
import com.indicvision.semper.data.net.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The local "export everything" archive. Entry names come from user-supplied
 * analysis names, so they are the part that can break an archive.
 */
// Pinned like UploadResumableTest: Robolectric 4.14 tops out below our targetSdk.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionEverythingExporterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        TokenStore.clear(context)
        SessionStore.deleteAll(context)
    }

    @After
    fun tearDown() {
        SessionStore.deleteAll(context)
        TokenStore.clear(context)
    }

    /** A local session with one readable frame; image size 0 skips report rendering. */
    private fun seedSession(id: String) {
        val dir = SessionStore.dirFor(context, id)
        val bytes = java.nio.ByteBuffer.allocate(DicResult.BYTES_PER_POINT).order(java.nio.ByteOrder.nativeOrder())
        repeat(DicResult.STRIDE) { bytes.putFloat(0.01f) }
        SessionPaths.frameDat(dir, 0).writeBytes(bytes.array())
        SessionStore.upsert(
            context,
            SessionRecord(
                id = id, name = id, createdAt = 1L, updatedAt = 1L, frameCount = 1,
                subset = 21, step = 5, strainWindow = 15,
                imgW = 0, imgH = 0, roiX = 0, roiY = 0, roiW = 0, roiH = 0,
                refPath = "", refName = "ref.png", sessionDir = dir.absolutePath,
                defNames = listOf("a.png"),
            ),
        )
    }

    @Test
    fun `progress counts sessions finished, reaching the total only once the last is in`() = runBlocking {
        seedSession("first")
        seedSession("second")
        val ticks = mutableListOf<Pair<Int, Int>>()

        val result = SessionEverythingExporter.exportMasterZip(context) { done, total ->
            ticks += done to total
        }

        assertNotNull(result)
        // It used to tick (index + 1) before building each session, so the
        // banner read 100% while the last session was still being packed.
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), ticks)
        assertEquals(2, result!!.sessionCount)
    }

    @Test
    fun `nothing to export yields no file rather than an empty zip`() = runBlocking {
        // A fresh install: no sessions on disk.
        assertNull(SessionEverythingExporter.exportMasterZip(context))
    }

    @Test
    fun `entry names keep readable characters and carry an id suffix`() {
        val name = SessionEverythingExporter.sanitizeZipName("Steel plate A-1_test.v2", "abcdef123456")

        assertEquals("Steel_plate_A-1_test.v2_abcdef12", name)
    }

    @Test
    fun `path separators and spaces cannot escape the entry name`() {
        val name = SessionEverythingExporter.sanitizeZipName("../../etc/passwd", "id123456")

        assertTrue("unexpected separators in '$name'", !name.contains('/') && !name.contains('\\'))
        assertEquals(".._.._etc_passwd_id123456", name)
    }

    @Test
    fun `a blank name still produces a usable entry`() {
        assertEquals("session_id123456", SessionEverythingExporter.sanitizeZipName("   ", "id123456"))
    }

    @Test
    fun `long names are truncated, keeping the archive entry bounded`() {
        val name = SessionEverythingExporter.sanitizeZipName("x".repeat(200), "abcdefghij")

        // 40 name chars + '_' + 8 id chars.
        assertEquals(49, name.length)
        assertTrue(name.startsWith("x".repeat(40) + "_"))
    }
}
