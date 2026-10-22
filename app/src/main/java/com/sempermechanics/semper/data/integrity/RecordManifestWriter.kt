package com.sempermechanics.semper.data.integrity

import android.content.Context
import androidx.annotation.WorkerThread
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.account.entitlement.DeviceKeyThumbprint
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionRecordSettings
import com.sempermechanics.semper.util.AtomicFiles
import com.sempermechanics.semper.util.Digests
import com.sempermechanics.semper.util.writeVia
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Writes a run's `record_manifest.json` ([SessionLayout.MANIFEST]) beside the
 * results it describes (ADR-028). The run calls it on its staging folder just
 * before the commit moves the results into the session, so the manifest and
 * its frames arrive together or not at all; it never touches a `.dat` byte.
 */
object RecordManifestWriter {

    /** The build a manifest names: the app's version and the engine's commit. */
    data class Build(val versionCode: Int, val versionName: String, val engine: String) {
        companion object {
            val CURRENT = Build(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.ENGINE_PIN)
        }
    }

    /**
     * [write] with this phone's device key, never failing the run: a manifest
     * that cannot be written leaves the analysis "Not recorded", as one from
     * before manifests. True when one was written.
     */
    @WorkerThread
    fun writeBestEffort(context: Context, dir: File, settings: SessionRecordSettings): Boolean = try {
        write(dir, settings, DeviceRecordSigner.orNull(context)) != null
    } catch (e: IOException) {
        Timber.w(e, "Could not write the record manifest")
        false
    }

    /**
     * Hashes every `.dat` and the summary's `field_ranges.bin` in [dir] and
     * writes the manifest there, signed by [signer] when there is one and it
     * signs. Null, and nothing written, when [dir] holds no `.dat`.
     */
    @WorkerThread
    fun write(
        dir: File,
        settings: SessionRecordSettings,
        signer: RecordSigner?,
        build: Build = Build.CURRENT,
        nowMs: Long = System.currentTimeMillis(),
    ): RecordManifest? {
        val files = recordedFiles(dir)
        if (files.none { it.extension == DAT }) return null
        val unsigned = RecordManifest(
            createdAt = RecordManifest.CreatedAt(utc = Instant.ofEpochMilli(nowMs).toString()),
            app = RecordManifest.AppStamp(build.versionCode, build.versionName),
            engine = build.engine,
            runSpec = RecordManifest.RunSpecRecord.of(settings),
            files = files.map { RecordManifest.FileEntry(it.name, it.length(), Digests.sha256Hex(it)) },
        )
        val manifest = signer?.let { sign(unsigned, it) } ?: unsigned
        AtomicFiles.writeVia(File(dir, SessionLayout.MANIFEST)) { it.writeText(ManifestJson.encode(manifest)) }
        return manifest
    }

    /**
     * The files a manifest lists, by name: every `.dat` and the summary's
     * field-range sidecar. Not the manifest, and not a `.part` sidecar.
     */
    fun recordedFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && (f.extension == DAT || f.name == SessionLayout.FIELD_RANGES) }
            ?.sortedBy { it.name }
            .orEmpty()

    /** [manifest] with [signer]'s key and signature, or null when the key will not sign. */
    private fun sign(manifest: RecordManifest, signer: RecordSigner): RecordManifest? = runCatching {
        val der = signer.publicKeyDer()
        val keyed = manifest.copy(
            keyThumbprint = DeviceKeyThumbprint.of(der),
            publicKey = ManifestSignature.encodeKey(der),
        )
        keyed.copy(signature = signer.sign(ManifestJson.canonicalBytes(keyed)))
    }.onFailure { Timber.w(it, "The device key did not sign the record manifest; writing it unsigned") }
        .getOrNull()

    private const val DAT = "dat"
}
