package com.indicvision.semper.cloud

import com.indicvision.semper.DicResult
import com.indicvision.semper.data.DatCodec
import com.indicvision.semper.data.SessionZip
import com.indicvision.semper.util.Digests
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
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

    @Test
    fun `verifyRoundTrip catches a STORED entry's CRC32 mismatch instead of trusting it silently`() {
        // Pins that the CRC32 fast path (STORED entries) actually detects corruption,
        // not just that it's faster — a regression here would silently promote a
        // corrupt archive.
        val dir = createTempDirectory(prefix = "session-zip-crc-").toFile()
        try {
            val tiff = File(dir, "oht_cfrp_01.tiff").also {
                it.writeBytes(ByteArray(4096) { i -> i.toByte() })
            }
            val out = File(dir, "Session.zip")
            val member = SessionZip.Member("raw", tiff.name, tiff)
            SessionZip.build(listOf(member), out)

            // Mutate the source after the archive was written — the archive's
            // central-directory CRC32 now reflects stale bytes.
            tiff.writeBytes(ByteArray(4096) { i -> (i + 1).toByte() })

            val failure = assertThrows(IllegalStateException::class.java) {
                SessionZip.verifyRoundTrip(out, listOf(member))
            }
            assertTrue(
                "expected a CRC-mismatch message, got: ${failure.message}",
                failure.message.orEmpty().contains("CRC mismatch"),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A real (small) DIC point buffer — .dat entries need this shape, not arbitrary bytes. */
    private fun sampleDatBytes(): ByteArray {
        val points = listOf(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 0.01f),
            floatArrayOf(4f, 0f, 1f, 1f, 0f, 0f, 0f, 0.01f),
            floatArrayOf(0f, 4f, 1f, 1f, 0f, 0f, 0f, 0.01f),
            floatArrayOf(4f, 4f, 1f, 1f, 0f, 0f, 0f, 0.01f),
        )
        val out = ByteBuffer.allocate(points.size * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
        for (p in points) for (v in p) out.putFloat(v)
        return out.array()
    }

    @Test
    fun `dat entries are DatCodec-encoded on build and decoded back on read`() {
        val dir = createTempDirectory(prefix = "session-zip-dat-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val dat = File(dir, "frame_0000.dat").also { it.writeBytes(datBytes) }
            val out = File(dir, "Session.zip")
            SessionZip.build(listOf(SessionZip.Member("dat", dat.name, dat)), out)

            ZipFile(out).use { zf ->
                val entry = zf.getEntry("dat/frame_0000.dat")!!
                assertEquals(
                    "dat entries stay STORED — the codec, not the zip, compresses them",
                    ZipEntry.STORED,
                    entry.method,
                )
                val archiveBytes = zf.getInputStream(entry).use { it.readBytes() }
                assertNotEquals(
                    "the archive must hold the codec's encoded form, not the raw .dat bytes",
                    datBytes.toList(),
                    archiveBytes.toList(),
                )
            }

            var decoded: ByteArray? = null
            SessionZip.forEachEntry(out) { role, name, input ->
                if (role == "dat" && name == "frame_0000.dat") decoded = input.readBytes()
            }
            assertArrayEquals(
                "forEachEntry must hand callers the real .dat bytes, transparently decoded",
                datBytes,
                decoded,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `merge decodes a dat entry back to its real layout, not the codec form`() {
        val dir = createTempDirectory(prefix = "session-zip-merge-dat-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val dat = File(dir, "frame_0000.dat").also { it.writeBytes(datBytes) }
            val bundle = File(dir, "Session.zip")
            SessionZip.build(listOf(SessionZip.Member("dat", dat.name, dat)), bundle)

            val merged = File(dir, "merged.zip")
            SessionZip.merge(listOf(bundle), merged)

            ZipFile(merged).use { zf ->
                val entry = zf.getEntry("dat/frame_0000.dat")!!
                val mergedBytes = zf.getInputStream(entry).use { it.readBytes() }
                assertArrayEquals(
                    "Save to Files must hand over a directly-usable .dat, not the internal codec form",
                    datBytes,
                    mergedBytes,
                )
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A hand-built zip with one plain STORED entry — no SessionZip/DatCodec involvement. */
    private fun writeRawStoredEntry(zip: File, entryName: String, bytes: ByteArray) {
        val entry = ZipEntry(entryName).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = java.util.zip.CRC32().apply { update(bytes) }.value
        }
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(entry)
            zos.write(bytes)
            zos.closeEntry()
        }
    }

    @Test
    fun `a legacy pre-codec dat entry (raw, unencoded) still reads back unchanged`() {
        // Simulates a Session.zip uploaded before DatCodec existed: a plain STORED
        // .dat entry with no codec header. decodeIfEncoded must pass it through.
        val dir = createTempDirectory(prefix = "session-zip-legacy-dat-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val legacyZip = File(dir, "Legacy.zip")
            writeRawStoredEntry(legacyZip, "dat/frame_0000.dat", datBytes)

            var readBack: ByteArray? = null
            SessionZip.forEachEntry(legacyZip) { role, name, input ->
                if (role == "dat" && name == "frame_0000.dat") readBack = input.readBytes()
            }
            assertArrayEquals(
                "a pre-codec archive's raw .dat bytes must pass through unchanged",
                datBytes,
                readBack,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `DatCodec round-trip is exercised by SessionZip build's own verifyRoundTrip`() {
        // build() calls verifyRoundTrip internally — this pins that a corrupted dat
        // payload would be caught there (checkMember's decode-then-compare path),
        // by confirming a clean build succeeds and the archive is exactly decodable.
        val dir = createTempDirectory(prefix = "session-zip-verify-dat-").toFile()
        try {
            val datBytes = sampleDatBytes()
            val dat = File(dir, "frame_0000.dat").also { it.writeBytes(datBytes) }
            val out = File(dir, "Session.zip")
            // Throws (via checkMember) if the round-trip doesn't match — build()
            // succeeding at all is the assertion.
            SessionZip.build(listOf(SessionZip.Member("dat", dat.name, dat)), out)

            ZipFile(out).use { zf ->
                val entry = zf.getEntry("dat/frame_0000.dat")!!
                val decoded = DatCodec.decode(zf.getInputStream(entry).use { it.readBytes() })
                assertArrayEquals(datBytes, decoded)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
