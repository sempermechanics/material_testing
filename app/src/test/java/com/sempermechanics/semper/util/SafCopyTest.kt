package com.sempermechanics.semper.util

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The one Save-to-Files write: saved only when bytes land, a failure reads false. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SafCopyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver

    @Test
    fun `copies the file into the picked document`() {
        val src = temp.newFile().apply { writeText("a,b\n1,2\n") }
        val dest = File(temp.root, "picked.csv")

        assertTrue(SafCopy.copy(resolver, Uri.fromFile(dest), src))
        assertEquals("a,b\n1,2\n", dest.readText())
    }

    @Test
    fun `an empty copy is not a save`() {
        val src = temp.newFile()

        assertFalse(SafCopy.copy(resolver, Uri.fromFile(File(temp.root, "empty.csv")), src))
    }

    @Test
    fun `a document that cannot be opened reads false, not a throw`() {
        val src = temp.newFile().apply { writeText("x") }

        assertFalse(SafCopy.copy(resolver, Uri.fromFile(File(temp.root, "no/such/dir/x.csv")), src))
    }
}
