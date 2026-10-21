package com.sempermechanics.semper.data

import com.sempermechanics.semper.data.net.FileSpecDto
import com.sempermechanics.semper.data.session.StagingLayout
import com.sempermechanics.semper.util.AtomicFiles
import com.sempermechanics.semper.util.Digests
import com.sempermechanics.semper.util.writeVia
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Which cloud session a staging was declared to, and the sha256 it declared for
 * each staged file: `upload_staging/.declared` ([StagingLayout.declared]).
 *
 * Written when the session is created ([UploadSessionPlanner.ensureSession]),
 * and read by [UploadStaging.prepare] before a run resumes that session: a
 * staging is resumed only under the pointer it was declared to, with the bytes
 * it declared. A finished staging under any other pointer (an older build's
 * leftovers, or a pointer stored by a run that died before writing this) is not
 * resumed; its session is released and the files are declared afresh (TD-282).
 * The staged bytes themselves are never touched here (CLAUDE.md: identical
 * across attempts).
 */
@Serializable
internal data class StagingBinding(val cloudSessionId: String, val sha256ByName: Map<String, String>) {

    /**
     * Whether this binding is [cloudSessionId]'s and every file it declared is
     * still in [layout] with the declared bytes. An archive is compared by its
     * verified `.sha256` sidecar (`UploadWorkOutcomes.isStagingReusable` and
     * `stageArchive` check it against the zip), any other file by its hash.
     */
    fun declares(cloudSessionId: String, layout: StagingLayout): Boolean =
        cloudSessionId.isNotBlank() &&
            this.cloudSessionId == cloudSessionId &&
            sha256ByName.isNotEmpty() &&
            sha256ByName.all { (name, sha) -> sha.equals(stagedSha256(layout, name), ignoreCase = true) }

    /** Writes this binding into [layout] atomically; false (logged) when it could not, which only costs a restage. */
    fun write(layout: StagingLayout): Boolean = try {
        AtomicFiles.writeVia(layout.declared) { it.writeText(json.encodeToString(this)) }
        true
    } catch (e: IOException) {
        Timber.w(e, "Could not record which session the staging was declared to")
        false
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The binding of the files [specs] declared to [cloudSessionId]. */
        fun of(cloudSessionId: String, specs: List<FileSpecDto>): StagingBinding =
            StagingBinding(cloudSessionId, specs.associate { it.name to it.sha256 })

        /** The binding in [layout], or null when there is none or it does not parse (read as no binding). */
        fun read(layout: StagingLayout): StagingBinding? {
            val file = layout.declared
            if (!file.isFile) return null
            return try {
                json.decodeFromString<StagingBinding>(file.readText())
            } catch (e: IllegalArgumentException) {
                // A SerializationException is one.
                Timber.w(e, "Unreadable staging binding")
                null
            } catch (e: IOException) {
                Timber.w(e, "Unreadable staging binding")
                null
            }
        }

        /** The sha256 of staged file [name]: its archive sidecar's, else the file's own; null when it is gone. */
        private fun stagedSha256(layout: StagingLayout, name: String): String? {
            val sidecar = layout.sha256Sidecar(name)
            val file = File(layout.dir, name)
            return when {
                sidecar.isFile -> sidecar.readText().trim()
                file.isFile -> Digests.sha256Hex(file)
                else -> null
            }
        }
    }
}
