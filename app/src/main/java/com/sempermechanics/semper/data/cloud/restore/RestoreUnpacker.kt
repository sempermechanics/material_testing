package com.sempermechanics.semper.data.cloud.restore

import com.sempermechanics.semper.data.DicUploadWorker
import com.sempermechanics.semper.data.cloud.CorruptTransferException
import com.sempermechanics.semper.data.net.ArtifactRoles
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionZip
import com.sempermechanics.semper.util.forEachChunk
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Writes a backup's entries into a session directory, where a local run would have put them. */
internal object RestoreUnpacker {

    /**
     * Extract a Session.zip into the layout a local run would have produced.
     * Entries are named `role/name` by [DicUploadWorker]; the mapping must
     * mirror the legacy per-file restore. Returns the reference image's
     * restored path ("" if the bundle somehow lacks one).
     */
    fun unpackBundle(zip: File, layout: SessionLayout): String {
        var refPath = ""
        val legacyReference = ZipFile(zip).use { it.getEntry(SessionZip.REFERENCE_ENTRY) == null }
        SessionZip.forEachEntry(zip) { role, name, input ->
            val dest = destFor(role, name, layout, legacyReference)
            dest.outputStream().use { input.copyTo(it) }
            if (dest == layout.referencePng) refPath = dest.absolutePath
        }
        return refPath
    }

    /**
     * Stream a downloaded prefix with [ZipInputStream] — [SessionZip.forEachEntry]
     * uses random-access [ZipFile], which needs the central directory
     * this deliberately did not fetch. Each entry is checked against its
     * central-directory CRC before it counts as restored.
     *
     * Deliberately does **not** go through [SessionZip]'s `DatCodec` decode: this path
     * only runs for `schema < 3` archives (see [CloudRestore.isSplitLayout]), which predate the
     * split-bundle feature entirely — and therefore predate `DatCodec` too. Every
     * `.dat` entry a legacy archive can hold is guaranteed raw. A schema this old
     * never gets `DatCodec`-encoded going forward either, since a *new* upload always
     * writes the current schema and goes through [SessionZip.build] /
     * [SessionZip.forEachEntry] instead of this path.
     */
    fun unpackPrefix(zip: File, layout: SessionLayout, crcByName: Map<String, Long>): String {
        var refPath = ""
        var restored = 0
        val legacyReference = SessionZip.REFERENCE_ENTRY !in crcByName
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            generateSequence { input.nextEntry }
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val dest = writePrefixEntry(input, entry.name, layout, crcByName[entry.name], legacyReference)
                    if (dest == layout.referencePng) refPath = dest.absolutePath
                    restored++
                }
        }
        if (restored != crcByName.size) throw CorruptTransferException("prefix_entry_count")
        return refPath
    }

    /** Copy one prefix entry into place, verifying it against its declared CRC. */
    private fun writePrefixEntry(
        input: ZipInputStream,
        entryName: String,
        layout: SessionLayout,
        expectedCrc: Long?,
        legacyReference: Boolean,
    ): File {
        val role = entryName.substringBefore('/', missingDelimiterValue = "")
        val name = entryName.substringAfter('/', missingDelimiterValue = "")
        if (role.isEmpty() || name.isEmpty()) throw CorruptTransferException("unexpected_zip_entry")
        val dest = destFor(role, name, layout, legacyReference)
        val crc = CRC32()
        dest.outputStream().buffered().use { out ->
            input.forEachChunk { buffer, n ->
                crc.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
        }
        if (expectedCrc != null && crc.value != expectedCrc) throw CorruptTransferException("entry_crc_mismatch")
        return dest
    }

    /**
     * Where one artifact lands on disk, by role — the single mapping both
     * restore paths share, with its parent directory created.
     *
     * The reference is `ref/Reference.png` ([SessionZip.REFERENCE_ENTRY]).
     * Backups before metadata schema 4 kept it at `raw/Reference.png`, so that
     * entry is the reference while [legacyReference] holds — the caller's
     * archive has no `ref/` entry — and otherwise a frame the user named
     * `Reference.png`. Guards against
     * zip-slip: an entry must resolve to a path strictly **inside** the session
     * directory.
     *
     * The containment test compares whole path segments. A plain string-prefix
     * test let `../<id>X/…` through, since a sibling directory whose name merely
     * starts with this session's id shares its path as a prefix. An entry that
     * escapes is a hostile or broken archive, so it fails as a
     * [CorruptTransferException]: terminal, never retried.
     */
    fun destFor(role: String, name: String, layout: SessionLayout, legacyReference: Boolean = true): File {
        val dest = when {
            role == SessionZip.REFERENCE_ROLE && name == SessionZip.REFERENCE_NAME -> layout.referencePng
            legacyReference && role == ArtifactRoles.RAW && name == SessionZip.REFERENCE_NAME -> layout.referencePng
            role == ArtifactRoles.RAW -> layout.rawDeformed(name)
            // Per-frame reports/heatmaps into their own subfolders — one PDF and
            // five PNGs per frame flat in the session dir would drown the .dat files.
            role == ArtifactRoles.REPORTS -> File(layout.reportsDir, name)
            role == ArtifactRoles.PROCESSED -> File(layout.processedDir, name)
            // dat lives flat in the session dir; csv is regenerable and kept
            // beside the session for export.
            else -> File(layout.dir, name)
        }
        // rawDeformedDir sits inside the session dir, so that dir is the only bound.
        val root = layout.dir.canonicalPath
        if (!dest.canonicalPath.startsWith(root + File.separator)) {
            throw CorruptTransferException(
                "artifact_path_escapes_session",
                IllegalArgumentException("$role/$name"),
            )
        }
        dest.parentFile?.mkdirs()
        return dest
    }
}
