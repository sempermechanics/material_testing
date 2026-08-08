@file:Suppress("TooManyFunctions")

package com.indicvision.semper.data

import com.indicvision.semper.util.Digests
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Session.zip build / verify helpers shared by upload.
 *
 * Large binaries (TIFF / DAT / PNG / GIF / …) are [ZipEntry.STORED].
 *
 * Do **not** toggle [ZipOutputStream.setLevel] between [Deflater.NO_COMPRESSION]
 * and [Deflater.DEFAULT_COMPRESSION] on Android: a Device Session.zip from Drive
 * had a valid central directory / sha256, but the first entry after each
 * level-0 to level-9 switch (raw TIFF, then Exx animation GIF) carried a
 * 7685-byte junk prefix before a good deflate stream — inflate then fails
 * with "invalid stored block lengths" or "invalid code lengths set".
 * Desktop OpenJDK does not reproduce; Pixel-class Android Deflater does.
 * Textable payloads (csv/json) stay DEFLATED at a single level.
 */
@Suppress("TooManyFunctions") // build / verify / STORED+DEFLATED entry writers stay together
internal object SessionZip {

    data class Member(val role: String, val name: String, val file: File)

    /** Already-compressed or large binary payloads — store, do not deflate. */
    val STORE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "pdf", "webp", "zip", "gif", "bmp",
        "tif", "tiff", "dat",
    )

    fun entryName(role: String, name: String): String = "$role/$name"

    fun shouldStore(fileName: String): Boolean =
        fileName.substringAfterLast('.').lowercase(Locale.US) in STORE_EXTENSIONS

    /**
     * Write [members] to [out] (via `*.tmp` + rename). Returns lowercase sha256
     * of the finished archive. Verifies every entry round-trips before promote.
     *
     * @param onBytes invoked with source bytes written into the archive (not
     * the CRC pre-pass for STORED entries). Used for Home "preparing %" on
     * large PLC bundles where zip dominates prepare time.
     */
    fun build(
        members: List<Member>,
        out: File,
        onBytes: (Long) -> Unit = {},
    ): String {
        require(members.isNotEmpty()) { "Session.zip payload is empty" }
        val tmp = File(out.parentFile, "${out.name}.tmp")
        tmp.delete()
        val digest = Digests.sha256()
        var promoted = false
        try {
            writeArchive(tmp, members, digest, onBytes)
            verifyRoundTrip(tmp, members)
            val hex = Digests.toHex(digest.digest())
            promote(tmp, out)
            promoted = true
            Timber.i("Bundled %d artifacts into %s (%d bytes)", members.size, out.name, out.length())
            return hex
        } finally {
            if (!promoted) tmp.delete()
        }
    }

    /**
     * Stream every entry via [ZipFile] (not [java.util.zip.ZipInputStream]).
     * [onEntry] must fully consume [input] before returning.
     */
    fun forEachEntry(
        zip: File,
        onEntry: (role: String, name: String, input: InputStream) -> Unit,
    ) {
        ZipFile(zip).use { zf ->
            zf.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                readEntry(zf, entry, onEntry)
            }
        }
    }

    private fun writeArchive(
        tmp: File,
        members: List<Member>,
        digest: java.security.MessageDigest,
        onBytes: (Long) -> Unit,
    ) {
        DigestOutputStream(BufferedOutputStream(tmp.outputStream()), digest).use { digOut ->
            ZipOutputStream(digOut).use { zip ->
                members.forEach { putMember(zip, it, onBytes) }
            }
        }
    }

    private fun promote(tmp: File, out: File) {
        out.delete()
        if (!tmp.renameTo(out)) {
            tmp.copyTo(out, overwrite = true)
            tmp.delete()
        }
    }

    private fun readEntry(
        zf: ZipFile,
        entry: ZipEntry,
        onEntry: (role: String, name: String, input: InputStream) -> Unit,
    ) {
        val role = entry.name.substringBefore('/', missingDelimiterValue = "")
        val name = entry.name.substringAfter('/', missingDelimiterValue = entry.name)
        try {
            zf.getInputStream(entry).use { input -> onEntry(role, name, input) }
        } catch (e: ZipException) {
            throw IllegalArgumentException(
                "Session.zip entry ${entry.name} inflate failed — corrupt transfer",
                e,
            )
        } catch (e: IOException) {
            throw IllegalArgumentException(
                "Session.zip entry ${entry.name} read failed — corrupt transfer",
                e,
            )
        }
    }

    private fun putMember(zip: ZipOutputStream, member: Member, onBytes: (Long) -> Unit) {
        val entryName = entryName(member.role, member.name)
        if (shouldStore(member.name)) {
            putStored(zip, entryName, member.file, onBytes)
        } else {
            putDeflated(zip, entryName, member.file, onBytes)
        }
    }

    private fun putStored(
        zip: ZipOutputStream,
        entryName: String,
        file: File,
        onBytes: (Long) -> Unit,
    ) {
        val crc = CRC32()
        val buf = ByteArray(COPY_BUFFER)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        val entry = ZipEntry(entryName).apply {
            method = ZipEntry.STORED
            size = file.length()
            compressedSize = file.length()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        copyReporting(file, zip, buf, onBytes)
        zip.closeEntry()
    }

    private fun putDeflated(
        zip: ZipOutputStream,
        entryName: String,
        file: File,
        onBytes: (Long) -> Unit,
    ) {
        zip.setLevel(Deflater.DEFAULT_COMPRESSION)
        val entry = ZipEntry(entryName).apply { method = ZipEntry.DEFLATED }
        zip.putNextEntry(entry)
        copyReporting(file, zip, ByteArray(COPY_BUFFER), onBytes)
        zip.closeEntry()
    }

    private fun copyReporting(
        file: File,
        zip: ZipOutputStream,
        buf: ByteArray,
        onBytes: (Long) -> Unit,
    ) {
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                zip.write(buf, 0, n)
                onBytes(n.toLong())
            }
        }
    }

    /** Ensure every member extracts byte-identical to its source before upload. */
    fun verifyRoundTrip(zip: File, members: List<Member>) {
        ZipFile(zip).use { zf ->
            check(zf.size() == members.size) {
                "Session.zip entry count ${zf.size()} != payload ${members.size}"
            }
            members.forEach { member -> checkMember(zf, member) }
        }
    }

    private fun checkMember(zf: ZipFile, member: Member) {
        val name = entryName(member.role, member.name)
        val entry = zf.getEntry(name)
            ?: error("Session.zip missing entry $name after bundling")
        val got = Digests.sha256HexStream(zf.getInputStream(entry))
        val expect = Digests.sha256Hex(member.file)
        check(got == expect) {
            "Session.zip entry $name round-trip hash mismatch after bundling"
        }
    }

    private const val COPY_BUFFER = 1 shl 16
}
