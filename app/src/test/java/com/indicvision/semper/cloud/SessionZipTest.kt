package com.indicvision.semper.cloud

import com.indicvision.semper.data.SessionZip
import com.indicvision.semper.util.Digests
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

/** Pins STORED TIFF/DAT packing and round-trip verify before upload. */
class SessionZipTest {

    @Test
    fun `tiff and dat are stored not deflated`() {
        assertTrue(SessionZip.shouldStore("oht_cfrp_01.tiff"))
        assertTrue(SessionZip.shouldStore("frame_0000.dat"))
        assertTrue(SessionZip.shouldStore("Reference.png"))
        assertTrue(SessionZip.shouldStore("anim.gif"))
        assertFalse(SessionZip.shouldStore("analysis_data.csv"))
        assertFalse(SessionZip.shouldStore("metadata.json"))
    }

    @Test
    fun `build stores tiff and round-trips with csv`() {
        val dir = createTempDirectory(prefix = "session-zip-").toFile()
        try {
            val tiff = File(dir, "oht_cfrp_01.tiff").also { f ->
                // Semi-random payload large enough to stress deflate-if-misused.
                val bytes = ByteArray(256 * 1024) { i -> (i * 17 + 31).toByte() }
                f.writeBytes(bytes)
            }
            val csv = File(dir, "analysis_data.csv").also {
                it.writeText("image,x,y\noht_cfrp_01.tiff,1,2\n")
            }
            val gif = File(dir, "preview.gif").also {
                it.writeBytes(ByteArray(4096) { it.toByte() })
            }
            val out = File(dir, "Session.zip")
            val hex = SessionZip.build(
                listOf(
                    SessionZip.Member("raw", tiff.name, tiff),
                    SessionZip.Member("csv", csv.name, csv),
                    SessionZip.Member("processed", "Frame_1/${gif.name}", gif),
                ),
                out,
            )
            assertEquals(64, hex.length)
            assertEquals(hex, Digests.sha256Hex(out))

            ZipFile(out).use { zf ->
                val tiffEntry = zf.getEntry("raw/oht_cfrp_01.tiff")!!
                assertEquals(java.util.zip.ZipEntry.STORED, tiffEntry.method)
                val csvEntry = zf.getEntry("csv/analysis_data.csv")!!
                assertEquals(java.util.zip.ZipEntry.DEFLATED, csvEntry.method)
            }

            val extracted = mutableMapOf<String, ByteArray>()
            SessionZip.forEachEntry(out) { role, name, input ->
                extracted["$role/$name"] = input.readBytes()
            }
            assertArrayEquals(tiff.readBytes(), extracted["raw/oht_cfrp_01.tiff"])
            assertArrayEquals(csv.readBytes(), extracted["csv/analysis_data.csv"])
            assertArrayEquals(gif.readBytes(), extracted["processed/Frame_1/preview.gif"])
        } finally {
            dir.deleteRecursively()
        }
    }
}
