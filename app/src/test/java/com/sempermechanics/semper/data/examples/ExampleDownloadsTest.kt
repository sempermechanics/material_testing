package com.sempermechanics.semper.data.examples

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.io.InputStream

/** Save to phone against a stand-in for MediaStore (ADR-031). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExampleDownloadsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val set = requireNotNull(ExampleCatalog.find(app.assets, "dice_translation"))

    /** Files by "path/name"; pending until published. */
    private class FakeSink(
        val failOn: String? = null,
        val cancelOn: String? = null,
    ) : ExampleDownloads.Sink {
        val files = linkedMapOf<String, ByteArray>()
        val pending = mutableSetOf<String>()
        val discarded = mutableListOf<String>()

        private fun key(uri: Uri) = uri.toString().removePrefix("fake:")

        override fun exists(relativePath: String, name: String, size: Long): Boolean {
            val key = relativePath + name
            return key !in pending && files[key]?.size?.toLong() == size
        }

        override fun create(relativePath: String, name: String, mimeType: String): Uri {
            val key = relativePath + name
            pending += key
            return Uri.parse("fake:$key")
        }

        override fun write(uri: Uri, source: InputStream) {
            val key = key(uri)
            if (failOn != null && key.endsWith(failOn)) throw IOException("disk full")
            if (cancelOn != null && key.endsWith(cancelOn)) throw CancellationException("left the screen")
            files[key] = source.readBytes()
        }

        override fun publish(uri: Uri) {
            pending -= key(uri)
        }

        override fun discard(uri: Uri) {
            val key = key(uri)
            discarded += key
            pending -= key
            files -= key
        }
    }

    private val folder = "Download/semper examples/DICe translation/"

    @Test
    fun `every file of the set is saved, notes and licence first`() = runTest {
        val sink = FakeSink()
        val done = mutableListOf<Int>()
        val outcome = ExampleDownloads(app.assets, sink).save(set) { n, _ -> done += n }
        assertEquals(ExampleDownloads.Outcome.Saved(folder, set.files.size, 0), outcome)
        assertEquals(set.files.map { folder + it }, sink.files.keys.toList())
        assertEquals(listOf("README.md", "CREDIT.txt", "LICENSE.DICe"), set.files.take(3))
        assertEquals((1..set.files.size).toList(), done)
        assertTrue(sink.pending.isEmpty())
        val ref = app.assets.open("examples/dice_translation/translation_ref.tif").use { it.readBytes() }
        assertEquals(ref.toList(), sink.files.getValue(folder + "translation_ref.tif").toList())
    }

    @Test
    fun `a second save skips what is already there`() = runTest {
        val sink = FakeSink()
        ExampleDownloads(app.assets, sink).save(set)
        val outcome = ExampleDownloads(app.assets, sink).save(set)
        assertEquals(ExampleDownloads.Outcome.Saved(folder, 0, set.files.size), outcome)
    }

    @Test
    fun `a failed write is reported and its pending file removed`() = runTest {
        val sink = FakeSink(failOn = "translation_def.tif")
        val outcome = ExampleDownloads(app.assets, sink).save(set)
        assertEquals(ExampleDownloads.Outcome.Failed("disk full"), outcome)
        assertEquals(listOf(folder + "translation_def.tif"), sink.discarded)
        assertTrue(sink.pending.isEmpty())
    }

    @Test
    fun `a cancel removes the pending file and goes on`() {
        val sink = FakeSink(cancelOn = "translation_def.tif")
        assertThrows(CancellationException::class.java) {
            runBlocking { ExampleDownloads(app.assets, sink).save(set) }
        }
        assertEquals(listOf(folder + "translation_def.tif"), sink.discarded)
    }

    @Test
    fun `the folder is under Downloads, named for the set`() {
        assertEquals(
            "Download/semper examples/PMMA bending/",
            ExampleDownloads.relativePathOf(
                requireNotNull(ExampleCatalog.find(app.assets, "pmma_bending")),
            ),
        )
    }
}
