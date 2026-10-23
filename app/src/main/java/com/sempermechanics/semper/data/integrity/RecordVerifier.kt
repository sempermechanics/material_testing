package com.sempermechanics.semper.data.integrity

import android.content.Context
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

    /**
     * Every listed file is as the run wrote it, and the signature holds by
     * this phone's own device key: the only key a phone can vouch for.
     */
    data class Verified(override val fingerprint: String) : RecordIntegrity

    /**
     * Every listed file matches and the signature holds, but by a key that is
     * not this phone's ([keyThumbprint]): another phone, this phone before a
     * reinstall, or anyone who signed with a key of their own. Never a pass:
     * the thumbprint has to be checked against the account's registered
     * devices.
     */
    data class SignedElsewhere(val keyThumbprint: String, override val fingerprint: String) : RecordIntegrity {
        /** The first [SHORT_THUMBPRINT] characters of [keyThumbprint] and an ellipsis, for a screen or a page. */
        val shortThumbprint: String get() = keyThumbprint.take(SHORT_THUMBPRINT) + "…"

        private companion object {
            const val SHORT_THUMBPRINT = 8
        }
    }

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

    /** [verify] against this phone's device key ([LocalRecordKey]). */
    @WorkerThread
    fun verify(context: Context, dir: File): RecordIntegrity = verify(dir, LocalRecordKey.thumbprint(context))

    /**
     * [dir]'s results against its manifest. Only a signature by the key
     * [localThumbprint] names (this phone's) reads [RecordIntegrity.Verified]:
     * the manifest carries its own public key, so a signature that merely
     * holds proves nothing on its own; anyone can re-sign with a key of their
     * own. Any other valid signature, or every one when [localThumbprint] is
     * null, reads [RecordIntegrity.SignedElsewhere].
     */
    @Suppress("ReturnCount") // one guard per way a manifest can fail to vouch for anything
    @WorkerThread
    fun verify(dir: File, localThumbprint: String?): RecordIntegrity {
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
        val thumbprint = manifest.keyThumbprint
        return when {
            changed.isNotEmpty() -> RecordIntegrity.Changed(changed, fingerprint)
            signature == null || thumbprint == null -> RecordIntegrity.Unsigned(fingerprint)
            localThumbprint != null && thumbprint == localThumbprint -> RecordIntegrity.Verified(fingerprint)
            else -> RecordIntegrity.SignedElsewhere(thumbprint, fingerprint)
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
