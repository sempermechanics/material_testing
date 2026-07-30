package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.SessionEverythingExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
