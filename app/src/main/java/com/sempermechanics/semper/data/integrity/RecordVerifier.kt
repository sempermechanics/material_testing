package com.sempermechanics.semper.data.integrity

import androidx.annotation.WorkerThread
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.util.Digests
import java.io.File
import java.io.IOException

/**
 * What a session's results say against their `record_manifest.json` (ADR-028).
 * Every answer but [NotRecorded] carries the manifest's [fingerprint]: the
 * first [RecordVerifier.FINGERPRINT_HEX] hex digits of the manifest file's
 * SHA-256, which the PDF cover prints.
 */
sealed interface RecordIntegrity {
    val fingerprint: String?

    /** Every listed file is as the run wrote it, and the device key's signature holds. */
    data class Verified(override val fingerprint: String) : RecordIntegrity

    /**
     * [files] (names in the session directory, sorted) differ from what the
     * run wrote: changed, missing, or a `.dat` the run did not write. The
     * manifest's own name means it does not parse or its signature fails.
     */
    data class Changed(val files: List<String>, override val fingerprint: String?) : RecordIntegrity

    /** Every listed file matches, but nothing signed the manifest, so a rewrite would read the same. */
    data class Unsigned(override val fingerprint: String) : RecordIntegrity

    /** No manifest: an analysis from before them, or one whose manifest could not be written. */
    data object NotRecorded : RecordIntegrity {
        override val fingerprint: String? = null
    }
}

/** Checks a session directory's results against its manifest. Disk reads: off the main thread. */
object RecordVerifier {

    /** Hex digits of the manifest's SHA-256 a fingerprint keeps. */
    const val FINGERPRINT_HEX = 16

    @Suppress("ReturnCount") // one guard per way a manifest can fail to vouch for anything
    @WorkerThread
    fun verify(dir: File): RecordIntegrity {
        val file = File(dir, SessionLayout.MANIFEST)
        if (!file.isFile) return RecordIntegrity.NotRecorded
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            return RecordIntegrity.Changed(listOf(SessionLayout.MANIFEST), fingerprint = null)
        }
        val fingerprint = Digests.toHex(Digests.sha256(bytes)).take(FINGERPRINT_HEX)
        val manifestChanged = RecordIntegrity.Changed(listOf(SessionLayout.MANIFEST), fingerprint)
        val parsed = ManifestJson.parse(bytes.toString(Charsets.UTF_8)) ?: return manifestChanged
        val manifest = parsed.manifest
        val signature = manifest.signature
        if (signature != null) {
            val key = manifest.publicKey ?: return manifestChanged
            val signed = ManifestJson.canonicalBytes(parsed.obj)
            if (!ManifestSignature.holds(key, manifest.keyThumbprint, signed, signature)) return manifestChanged
        }
        val changed = changedFiles(dir, manifest.files)
        return when {
            changed.isNotEmpty() -> RecordIntegrity.Changed(changed, fingerprint)
            signature == null -> RecordIntegrity.Unsigned(fingerprint)
            else -> RecordIntegrity.Verified(fingerprint)
        }
    }

    /**
     * The names of [listed] files that are missing or differ in size or
     * SHA-256, and of any `.dat` in [dir] the manifest does not list, sorted.
     * `field_ranges.bin` is a cache the viewer rebuilds and a restore does
     * not bring back: checked when there, never missed.
     */
    private fun changedFiles(dir: File, listed: List<RecordManifest.FileEntry>): List<String> {
        val changed = sortedSetOf<String>()
        for (entry in listed) {
            // A name is one file in dir; anything that reaches out of it is not the run's.
            if (File(entry.name).name != entry.name) {
                changed += entry.name
                continue
            }
            val file = File(dir, entry.name)
            when {
                !file.isFile -> if (entry.name != SessionLayout.FIELD_RANGES) changed += entry.name
                file.length() != entry.size || !sameHash(file, entry.sha256) -> changed += entry.name
            }
        }
        val names = listed.mapTo(HashSet()) { it.name }
        dir.listFiles { f -> f.isFile && f.extension == "dat" && f.name !in names }?.forEach { changed += it.name }
        return changed.toList()
    }

    private fun sameHash(file: File, sha256: String): Boolean = try {
        Digests.sha256Hex(file).equals(sha256, ignoreCase = true)
    } catch (_: IOException) {
        false
    }
}
