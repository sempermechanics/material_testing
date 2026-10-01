package com.indicvision.semper.data.cloud.restore

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.indicvision.semper.data.DicBundleDownloadWorker
import com.indicvision.semper.data.DicRestoreWorker
import com.indicvision.semper.data.DicUploadWorker
import com.indicvision.semper.data.cloud.CorruptTransferException
import com.indicvision.semper.data.cloud.SessionMetadataDoc
import com.indicvision.semper.data.cloud.SessionUploadMetadata
import com.indicvision.semper.data.cloud.TransferLog
import com.indicvision.semper.data.cloud.UploadWorkOutcomes
import com.indicvision.semper.data.cloud.WorkTags
import com.indicvision.semper.data.cloud.enqueueUnique
import com.indicvision.semper.data.cloud.oneTimeWork
import com.indicvision.semper.data.net.ArtifactRoles
import com.indicvision.semper.data.net.Authed
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.CloudFileDto
import com.indicvision.semper.data.net.CloudSessionDto
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.authed
import com.indicvision.semper.data.session.CacheJanitor
import com.indicvision.semper.data.session.SessionLayout
import com.indicvision.semper.data.session.SessionNaming
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.data.session.SessionZip
import com.indicvision.semper.data.session.ZipDirectory
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.util.AtomicFiles
import com.indicvision.semper.util.Digests
import com.indicvision.semper.util.forEachChunk
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Rebuilds an analysis on this device from its cloud backup.
 *
 * Because the engine's `.dat` results are uploaded alongside the raw images,
 * a restored session is **fully re-openable** — the results viewer works without
 * re-running the analysis. The session's `metadata/metadata.json` is the
 * blueprint: it carries the engine parameters, ROI, metrics and the ordered
 * frame list (image ↔ dat ↔ csv), so we can reconstruct the [SessionRecord]
 * and the on-disk layout exactly as a local run would have produced it.
 *
 * A restore fetches `Session.zip` (see [SessionZip.isRestoreEssential]) and rebuilds
 * the full local layout — nothing a local run would have produced is missing:
 * ```
 * <sessionDir>/reference.png              the heatmap backdrop
 * <sessionDir>/frame_%04d.dat             the engine results
 * <sessionDir>/raw_deformed/<name>        every deformed original
 * ```
 * The derived deliverables (`csv`, `reports`, `processed`) are the only thing left in
 * the cloud (`Extras.zip`) — they are regenerated on export, so a restore never reads
 * them back.
 */
@Suppress("TooManyFunctions", "LargeClass") // one cohesive restore pipeline: fetch, parse, write, index
object CloudRestore {

    /** Input key for [DicRestoreWorker]: which cloud session to pull down. */
    const val KEY_CLOUD_SESSION_ID = "CLOUD_SESSION_ID"
    const val KEY_TARGET_LOCAL_ID = "TARGET_LOCAL_ID"

    /**
     * Queue a restore. It runs in [DicRestoreWorker] rather than a UI scope so
     * it survives leaving the screen — a restore can be hundreds of megabytes
     * and must not die because the user navigated away.
     */
    fun enqueueRestore(context: Context, cloudSessionId: String, targetLocalId: String): String {
        val name = workName(cloudSessionId)
        val work = oneTimeWork<DicRestoreWorker>(
            tags = listOf(WorkTags.RESTORE, WorkTags.restoreTag(cloudSessionId)),
            input = workDataOf(
                KEY_CLOUD_SESSION_ID to cloudSessionId,
                KEY_TARGET_LOCAL_ID to targetLocalId,
            ),
            expedited = true,
        )
        enqueueUnique(context, name, ExistingWorkPolicy.KEEP, work)
        SemperAnalytics.event(context, SemperAnalytics.CLOUD_RESTORE_ENQUEUED)
        return name
    }

    /**
     * Queue a Save-to-Files Session.zip download. Same WorkManager rationale as
     * [enqueueRestore]: Analyses data management must not cancel the transfer
     * when the user leaves Settings.
     *
     * [destUri] is a document URI from [android.content.Intent.ACTION_CREATE_DOCUMENT]
     * (persistable write grant taken by the caller before enqueue).
     */
    fun enqueueBundleDownload(
        context: Context,
        cloudSessionId: String,
        displayName: String,
        destUri: String,
        localSessionId: String = "",
    ): String {
        val name = bundleDownloadWorkName(cloudSessionId)
        val work = oneTimeWork<DicBundleDownloadWorker>(
            tags = listOf(WorkTags.BUNDLE_DOWNLOAD, WorkTags.bundleDownloadTag(cloudSessionId)),
            input = workDataOf(
                KEY_CLOUD_SESSION_ID to cloudSessionId,
                DicBundleDownloadWorker.KEY_DISPLAY_NAME to displayName,
                DicBundleDownloadWorker.KEY_LOCAL_SESSION_ID to localSessionId,
                DicBundleDownloadWorker.KEY_DEST_URI to destUri,
            ),
            expedited = true,
        )
        enqueueUnique(context, name, ExistingWorkPolicy.KEEP, work)
        return name
    }

    fun cancelBundleDownload(context: Context, cloudSessionId: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(bundleDownloadWorkName(cloudSessionId))
    }

    /** Unique work name for a restore, so the UI can observe its progress. */
    fun workName(cloudSessionId: String): String = WorkTags.restoreName(cloudSessionId)

    /** Unique work name for a Save-to-Files download. */
    fun bundleDownloadWorkName(cloudSessionId: String): String = WorkTags.bundleDownloadName(cloudSessionId)

    /** Suggested SAF filename for an analysis Session.zip. */
    fun suggestedBundleFileName(displayName: String): String = SessionNaming.bundleFileName(displayName)

    const val TAG_BUNDLE_DOWNLOAD = WorkTags.BUNDLE_DOWNLOAD

    /** How much of the cloud id names a row restored from a backup that carries no local id. */
    private const val RESTORED_ID_CHARS = 12

    fun targetLocalId(cloud: CloudSessionDto): String =
        cloud.localSessionId.ifBlank { "restored-" + cloud.sessionId.take(RESTORED_ID_CHARS) }

    /**
     * Why a restorable-list query failed or is empty — never collapse auth/config
     * failures into a blank "no backups" list.
     */
    sealed class ListResult {
        data class Ready(val sessions: List<CloudSessionDto>) : ListResult()
        data object Empty : ListResult()
        data object NeedSignIn : ListResult()
        data object ApiOff : ListResult()
        data class Failed(val reason: String) : ListResult()
    }

    /**
     * Every COMPLETED cloud backup for this account (no local-presence filter).
     * Settings management uses this so rows that still have a phone stub without
     * `.dat`s can still offer Download when a cloud copy exists. Auth/config
     * failures stay distinct from an empty list.
     */
    suspend fun listCompleted(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): ListResult = withContext(Dispatchers.IO) {
        when (val listed = api.authed(tokens) { listSessions(it).sessions }) {
            Authed.Disabled -> ListResult.ApiOff
            Authed.NoToken -> ListResult.NeedSignIn
            is Authed.Failed -> {
                val cause = listed.failure.cause
                Timber.e(cause, "listCompleted sessions failed")
                ListResult.Failed(cause.message ?: cause.toString())
            }
            is Authed.Ok -> {
                val sessions = listed.value.filter { it.status == UploadWorkOutcomes.STATUS_COMPLETED }
                if (sessions.isEmpty()) ListResult.Empty else ListResult.Ready(sessions)
            }
        }
    }

    /**
     * Download the cloud [Session.zip] into app cache for the user to save or
     * share. Does **not** unpack into a session directory or touch existing
     * local analysis files.
     *
     * Throws [UnrestorableBackupException] when the backup has no bundle role
     * (legacy per-file backups) and [CorruptTransferException] when the transfer
     * fails attestation.
     */
    @Suppress("LongParameterList") // api and tokens are test seams (ADR-002)
    suspend fun downloadBundleZip(
        context: Context,
        sessionId: String,
        displayName: String,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val token = tokens.usableIdToken() ?: error("Not signed in")
        val files = completedFiles(api, token, sessionId)
        val bundleEntry = files.firstOrNull { it.role == ArtifactRoles.BUNDLE }
            ?: throw UnrestorableBackupException("backup_no_bundle")

        val outDir = CacheJanitor.shareDir(context.applicationContext.cacheDir)
        val dest = File(outDir, SessionNaming.bundleFileName(displayName))
        discard(dest)
        val whole = api.downloadReporting(token, bundleEntry, dest, onProgress)
        verifySessionZip(dest, bundleEntry.declaredSize, bundleEntry.sha256)
        // Since the payload was split, Session.zip alone is no longer the whole
        // analysis. "Save to Files" is the deliverables use case, so pull Extras.zip
        // too and hand over one merged archive — the same single file as before.
        files.firstOrNull { it.role == ArtifactRoles.EXTRAS }?.let { mergeExtrasInto(api, token, it, dest) }
        onProgress(whole, whole)
        dest
    }

    /** Fold Extras.zip into [dest] so Save-to-Files still yields one complete archive. */
    private suspend fun mergeExtrasInto(api: CloudApi, token: String, extrasEntry: CloudFileDto, dest: File) {
        val extrasTmp = File(dest.parentFile, "${dest.name}.extras")
        try {
            api.downloadFile(token, extrasEntry.fileId, extrasTmp, expectedBytes = extrasEntry.declaredSize)
            verifyExtrasZip(extrasTmp, extrasEntry.sha256)
            SessionZip.merge(listOf(dest, extrasTmp), dest)
        } finally {
            discard(extrasTmp)
        }
    }

    /**
     * Download an analysis and rebuild it locally. Returns the restored local
     * session id, or throws on failure.
     *
     * @param onProgress cumulative units completed vs total (bytes for bundled
     * Session.zip restores; file counts for legacy per-file backups).
     */
    @Suppress("LongParameterList") // api and tokens are test seams (ADR-002)
    suspend fun restore(
        context: Context,
        sessionId: String,
        targetLocalId: String,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> },
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val token = tokens.usableIdToken() ?: error("Not signed in")
        val files = completedFiles(api, token, sessionId)

        // 1. metadata.json first — it's the blueprint for everything else.
        val metaEntry = files.firstOrNull { it.role == ArtifactRoles.METADATA }
            ?: throw UnrestorableBackupException("backup_no_metadata")
        val metadataTmp = scratchFile(appContext.cacheDir, sessionId, "metadata.json")
        val metadata = fetchMetadata(api, token, metaEntry, metadataTmp)

        // The enqueueing UI already created a row under targetLocalId. Never let
        // metadata select a second id and leave an orphan stub behind.
        val existing = SessionStore.get(appContext, targetLocalId)
        val layout = SessionLayout(SessionStore.dirFor(appContext, targetLocalId))
        layout.rawDeformedDir.mkdirs()
        layout.metadataJson.writeBytes(metadata.bytes)

        // 2. Everything else, into the layout a local run would have produced.
        val fetch = PayloadFetch(api, token, sessionId, appContext.cacheDir, layout)
        val outcome = fetchPayload(fetch, files, metadata.doc.isSplitLayout(), onProgress)

        // 3. Rebuild the index row from the blueprint.
        val record = metadata.doc.toRecord(targetLocalId, sessionId, layout.dir, outcome.refPath, existing)
        // allowOverLimit: the analysis already counts against the cloud quota.
        val saved = SessionStore.save(appContext, record, allowOverLimit = true)
        check(saved == SessionStore.UpsertResult.SAVED) { "Could not update the restored session index ($saved)" }
        logRestoreSaving(outcome, metaEntry.sizeBytes, files)
        targetLocalId
    }

    /** The backup's COMPLETED files; none at all is a backup no retry will fix. */
    private suspend fun completedFiles(api: CloudApi, token: String, sessionId: String): List<CloudFileDto> {
        val files = api.listSessionFiles(token, sessionId).files.filter { it.status == "COMPLETED" }
        if (files.isEmpty()) throw UnrestorableBackupException("backup_no_completed_files")
        return files
    }

    /** `metadata.json` as downloaded (written back verbatim) and parsed. */
    private class FetchedMetadata(val bytes: ByteArray, val doc: SessionMetadataDoc)

    /**
     * Download and check the backup's `metadata.json`.
     *
     * Every attempt starts from nothing: [tmp] and its `.part` / `.full` sidecars are
     * cleared first, since the download resumes from a `.part` beside its destination
     * and one an earlier attempt left could hold an older body of this file (the
     * backend's metadata replace route rewrites it when an analysis is edited after
     * its backup, e.g. a deflection correction). The declared sha256 is checked like
     * the bundle's, when the file list carries one; a mismatch, or a body that is not
     * a JSON object (or holds a frame that is not one), is corrupt (terminal).
     */
    private suspend fun fetchMetadata(api: CloudApi, token: String, entry: CloudFileDto, tmp: File): FetchedMetadata {
        discard(tmp)
        try {
            api.downloadFile(token, entry.fileId, tmp, expectedBytes = entry.declaredSize)
            val bytes = tmp.readBytes()
            verifyMetadata(bytes, entry.sha256)
            val doc = try {
                SessionMetadataDoc.decode(String(bytes, Charsets.UTF_8))
            } catch (e: IllegalArgumentException) {
                // SerializationException is one: what org.json's parse used to throw as JSONException.
                throw CorruptTransferException("metadata_json_invalid", e)
            }
            return FetchedMetadata(bytes, doc)
        } finally {
            discard(tmp)
        }
    }

    /** Log restore completion; structured line is PII-free, Timber line is coarse totals only. */
    private fun logRestoreSaving(outcome: PayloadOutcome, metaBytes: Long, files: List<CloudFileDto>) {
        val downloaded = metaBytes.coerceAtLeast(0L) + outcome.bytesDownloaded
        val backupTotal = files.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        TransferLog.phase(
            TransferLog.PhaseFields(
                phase = "restore",
                outcome = "complete",
                bytes = downloaded,
                count = files.size,
                stage = outcome.mode,
            ),
        )
        Timber.i(
            "Restore complete (%s): %d of %d backup bytes (%d files)",
            outcome.mode,
            downloaded,
            backupTotal,
            files.size,
        )
    }

    /** Remove an interrupted restore's files while retaining its cloud-only index row. */
    fun clearPartialArtifacts(context: Context, localId: String) {
        val dir = SessionStore.dirFor(context.applicationContext, localId)
        dir.deleteRecursively()
        check(dir.mkdirs()) { "Could not reset partial restore directory" }
    }

    /**
     * Whether this backup's `Session.zip` holds only the restore payload.
     *
     * `schema` has been written since the first cloud backups but never read until
     * now, so the parse must be forgiving: anything unrecognised or missing is an
     * older, everything-in-one-zip backup. Being wrong in that direction costs
     * bandwidth; being wrong the other way would skip frames.
     */
    internal fun isSplitLayout(schema: String): Boolean {
        val version = schema.substringAfterLast('/', "").toIntOrNull() ?: return false
        return version >= SessionUploadMetadata.SCHEMA_SPLIT_BUNDLE
    }

    // ── Fetching the payload ───────────────────────────────────────────────

    /** Concurrent GETs for legacy per-file restores (matches upload concurrency). */
    private const val LEGACY_DOWNLOAD_CONCURRENCY = 4

    /**
     * Tail fetched to read a legacy bundle's central directory. Large enough for a
     * few thousand entries; if the directory does not fit, the parse returns null and
     * the whole archive is downloaded as before.
     */
    private const val CENTRAL_DIRECTORY_TAIL_BYTES = 512L * 1024L

    /** Skip the extra round trip unless the prefix saves at least this fraction. */
    private const val PREFIX_MIN_SAVING_DIVISOR = 20L // 5%

    private const val PERCENT = 100L

    /** One restore's source and destination: everything a payload fetch needs, travelling together. */
    private class PayloadFetch(
        val api: CloudApi,
        val token: String,
        val sessionId: String,
        val cacheDir: File,
        val layout: SessionLayout,
    ) {
        fun scratch(suffix: String): File = scratchFile(cacheDir, sessionId, suffix)
    }

    /**
     * Result of restoring the file payload: the reference image path, the bytes
     * actually pulled off the network, and which strategy did it (for telemetry).
     */
    private data class PayloadOutcome(
        val refPath: String,
        val mode: String,
        val bytesDownloaded: Long = 0L,
    )

    /** Where the restore payload ends, plus the CRC of every entry inside it. */
    internal data class PrefixPlan(val cut: Long, val crcByName: Map<String, Long>)

    /**
     * Everything but `metadata.json`, into [PayloadFetch.layout]. Three eras, one
     * destination layout (see [destFor]):
     *  - schema/3+ ([split]): `Session.zip` holds only raw/ + dat/. Fetch it whole;
     *    the `Extras.zip` beside it is never downloaded.
     *  - schema < 3: one `Session.zip` holds everything. Fetch just the raw/ + dat/
     *    prefix, falling back to the whole archive if that is not safely possible.
     *  - pre-bundle: every artifact listed as its own file.
     */
    private suspend fun fetchPayload(
        fetch: PayloadFetch,
        files: List<CloudFileDto>,
        split: Boolean,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): PayloadOutcome {
        val bundle = files.firstOrNull { it.role == ArtifactRoles.BUNDLE }
        return when {
            bundle == null -> PayloadOutcome(restoreLegacyFiles(fetch, files, onProgress), "legacy-per-file")
            split -> downloadAndUnpackBundle(fetch, bundle, onProgress)
            else -> restoreLegacyBundle(fetch, bundle, onProgress)
        }
    }

    /**
     * Download Session.zip with size checks, verify zip magic, unpack.
     * Deletes `.part` / `.full` sidecars so a corrupt transfer cannot stick.
     * [onProgress] is byte-based: (bytesOnDisk, declaredSize).
     */
    private suspend fun downloadAndUnpackBundle(
        fetch: PayloadFetch,
        bundle: CloudFileDto,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): PayloadOutcome {
        val zipTmp = fetch.scratch("bundle.zip")
        try {
            val whole = fetch.api.downloadReporting(fetch.token, bundle, zipTmp, onProgress)
            verifySessionZip(zipTmp, bundle.declaredSize, bundle.sha256, requireEntries = true)
            // Download bytes are done; hold 100% through unpack so the row
            // doesn't look stuck again during inflate.
            onProgress(whole, whole)
            return PayloadOutcome(unpackBundle(zipTmp, fetch.layout), "whole-bundle", zipTmp.length())
        } finally {
            discard(zipTmp)
        }
    }

    /**
     * Legacy backup: one `Session.zip` holding raw/, dat/, csv/, reports/ and
     * processed/. Only raw/ + dat/ are needed, and the uploader wrote them first, so
     * they are a contiguous prefix — read the central directory, then fetch just that
     * prefix instead of the whole archive.
     *
     * Whole-file sha256 cannot apply to a partial fetch, so entries are verified by
     * their central-directory CRC32 instead. Anything unexpected — an unreadable
     * directory, an interleaved layout, a CRC mismatch — falls back to downloading
     * the entire archive, which is exactly today's behaviour.
     */
    private suspend fun restoreLegacyBundle(
        fetch: PayloadFetch,
        bundle: CloudFileDto,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): PayloadOutcome {
        val size = bundle.sizeBytes
        val plan = if (size > 0L) {
            suspendRunCatching { planPrefixFetch(fetch, bundle) }
                .onFailure { Timber.w(it, "Prefix planning failed for %s; downloading whole bundle", fetch.sessionId) }
                .getOrNull()
        } else {
            null
        }
        if (plan == null) return downloadAndUnpackBundle(fetch, bundle, onProgress)

        val prefixTmp = fetch.scratch("prefix.zip")
        // The central-directory tail counts toward what the ranged restore pulled.
        val tailBytes = minOf(size, CENTRAL_DIRECTORY_TAIL_BYTES)
        return try {
            val percent = plan.cut * PERCENT / size.coerceAtLeast(1L)
            Timber.i("Legacy bundle %s: fetching %d of %d bytes (%d%%)", fetch.sessionId, plan.cut, size, percent)
            onProgress(0L, plan.cut)
            fetch.api.downloadRange(fetch.token, bundle.fileId, prefixTmp, rangeStart = 0L, length = plan.cut)
            if (prefixTmp.length() != plan.cut) throw CorruptTransferException("prefix_size_mismatch")
            onProgress(plan.cut, plan.cut)
            val ref = unpackPrefix(prefixTmp, fetch.layout, plan.crcByName)
            PayloadOutcome(ref, "legacy-ranged-prefix", plan.cut + tailBytes)
        } catch (e: IllegalArgumentException) {
            // A bad prefix is not a corrupt backup — fall back to the whole archive
            // rather than failing a restore that would otherwise succeed.
            Timber.w(e, "Prefix restore failed for %s; downloading whole bundle", fetch.sessionId)
            prefixTmp.delete()
            downloadAndUnpackBundle(fetch, bundle, onProgress)
        } finally {
            discard(prefixTmp)
        }
    }

    /**
     * Read the archive's central directory over a tail range and work out how much of
     * it is worth downloading. Returns null when a prefix fetch is not clearly safe.
     */
    private suspend fun planPrefixFetch(fetch: PayloadFetch, bundle: CloudFileDto): PrefixPlan? {
        val size = bundle.sizeBytes
        val tailLen = minOf(size, CENTRAL_DIRECTORY_TAIL_BYTES)
        val tailTmp = fetch.scratch("tail.bin")
        try {
            fetch.api.downloadRange(fetch.token, bundle.fileId, tailTmp, rangeStart = size - tailLen, length = tailLen)
            return planFromTail(tailTmp.readBytes(), size - tailLen, size)
        } finally {
            discard(tailTmp)
        }
    }

    /**
     * Pure half of [planPrefixFetch]: what the fetched tail says about the archive.
     * Null — "just download the whole archive" — unless the directory parses, the
     * payload is a clean prefix, and skipping the rest saves a meaningful amount.
     */
    internal fun planFromTail(tail: ByteArray, tailStart: Long, size: Long): PrefixPlan? {
        val prefixes = SessionZip.RESTORE_ENTRY_PREFIXES
        val directory = ZipDirectory.parse(tail, tailStart, size)
        val cut = directory?.prefixCut(prefixes)
            ?.takeIf { it < size - (size / PREFIX_MIN_SAVING_DIVISOR) }
            ?: return null
        val crcByName = directory.entries
            .filter { entry -> prefixes.any { entry.name.startsWith(it) } }
            .associate { it.name to it.crc32 }
        return PrefixPlan(cut, crcByName)
    }

    /** Legacy per-file backups: download each artifact into place. Returns refPath. */
    private suspend fun restoreLegacyFiles(
        fetch: PayloadFetch,
        files: List<CloudFileDto>,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): String {
        val rest = files.filter { it.role != ArtifactRoles.METADATA }
        val done = AtomicInteger(0)
        val refPath = AtomicReference("")
        val total = rest.size.toLong().coerceAtLeast(1L)
        onProgress(0L, total)
        // A few GETs in flight fill the link the way parallel Drive uploads do;
        // one failure cancels siblings via coroutineScope (same as before: abort).
        coroutineScope {
            val gate = Semaphore(LEGACY_DOWNLOAD_CONCURRENCY)
            rest.map { f ->
                async {
                    gate.withPermit {
                        val dest = destFor(f.role, f.name, fetch.layout)
                        fetch.api.downloadFile(fetch.token, f.fileId, dest, expectedBytes = f.declaredSize)
                        if (dest.name == SessionLayout.REFERENCE_PNG) refPath.set(dest.absolutePath)
                        onProgress(done.incrementAndGet().toLong(), total)
                    }
                }
            }.awaitAll()
        }
        return refPath.get()
    }

    // ── Verifying what came down ───────────────────────────────────────────

    /** ZIP local-file / empty-archive signature prefix (`PK`). */
    private val ZIP_MAGIC = "PK".toByteArray(Charsets.US_ASCII)

    private const val SHA256_HEX_CHARS = 64

    /** The declared sha256 in lowercase hex, or null when none (or a malformed one) was declared. */
    private fun expectedSha256(declared: String?): String? =
        declared?.lowercase()?.takeIf { it.length == SHA256_HEX_CHARS }

    /**
     * A downloaded `Session.zip`: its declared size (when known), its declared
     * sha256 (required), the zip magic, and with [requireEntries] at least one
     * readable entry. The first failure is thrown.
     */
    private fun verifySessionZip(file: File, expectedSize: Long, sha256: String?, requireEntries: Boolean = false) {
        val err = checkZipSize(file, expectedSize)
            ?: checkZipSha256(file, sha256)
            ?: checkZipMagic(file)
            ?: if (requireEntries) checkZipEntries(file) else null
        if (err != null) throw err
    }

    /** `Extras.zip`'s declared sha256 is required; a mismatch reports the bundle's code, as it always has. */
    private fun verifyExtrasZip(file: File, sha256: String?) {
        val expected = expectedSha256(sha256) ?: throw CorruptTransferException("extras_zip_sha256_missing")
        if (Digests.sha256Hex(file) != expected) throw CorruptTransferException("session_zip_sha256_mismatch")
    }

    /** `metadata.json`'s [bytes] against its sha256, checked only when one is declared. */
    private fun verifyMetadata(bytes: ByteArray, sha256: String?) {
        val expected = expectedSha256(sha256) ?: return
        if (Digests.toHex(Digests.sha256(bytes)) != expected) {
            throw CorruptTransferException("metadata_sha256_mismatch")
        }
    }

    private fun checkZipSize(file: File, expectedSize: Long): CorruptTransferException? =
        if (expectedSize > 0L && file.length() != expectedSize) {
            CorruptTransferException("session_zip_size_mismatch")
        } else {
            null
        }

    private fun checkZipSha256(file: File, sha256: String?): CorruptTransferException? {
        val expected = expectedSha256(sha256)
        return when {
            expected == null -> CorruptTransferException("session_zip_sha256_missing")
            Digests.sha256Hex(file) != expected -> CorruptTransferException("session_zip_sha256_mismatch")
            else -> null
        }
    }

    private fun checkZipMagic(file: File): CorruptTransferException? {
        val magic = ByteArray(ZIP_MAGIC.size)
        val read = file.inputStream().use { it.read(magic) }
        return when {
            read < ZIP_MAGIC.size -> CorruptTransferException("session_zip_too_small")
            !magic.contentEquals(ZIP_MAGIC) -> CorruptTransferException("session_zip_bad_magic")
            else -> null
        }
    }

    private fun checkZipEntries(file: File): CorruptTransferException? = try {
        ZipFile(file).use { zf -> if (zf.size() <= 0) CorruptTransferException("session_zip_no_entries") else null }
    } catch (e: ZipException) {
        CorruptTransferException("session_zip_unreadable", e)
    }

    // ── Unpacking into the session ─────────────────────────────────────────

    /**
     * Extract a Session.zip into the layout a local run would have produced.
     * Entries are named `role/name` by [DicUploadWorker]; the mapping must
     * mirror the legacy per-file restore. Returns the reference image's
     * restored path ("" if the bundle somehow lacks one).
     */
    private fun unpackBundle(zip: File, layout: SessionLayout): String {
        var refPath = ""
        SessionZip.forEachEntry(zip) { role, name, input ->
            val dest = destFor(role, name, layout)
            dest.outputStream().use { input.copyTo(it) }
            if (dest.name == SessionLayout.REFERENCE_PNG) refPath = dest.absolutePath
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
     * only runs for `schema < 3` archives (see [isSplitLayout]), which predate the
     * split-bundle feature entirely — and therefore predate `DatCodec` too. Every
     * `.dat` entry a legacy archive can hold is guaranteed raw. A schema this old
     * never gets `DatCodec`-encoded going forward either, since a *new* upload always
     * writes the current schema and goes through [SessionZip.build] /
     * [SessionZip.forEachEntry] instead of this path.
     */
    private fun unpackPrefix(zip: File, layout: SessionLayout, crcByName: Map<String, Long>): String {
        var refPath = ""
        var restored = 0
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            generateSequence { input.nextEntry }
                .filterNot { it.isDirectory }
                .forEach { entry ->
                    val dest = writePrefixEntry(input, entry.name, layout, crcByName[entry.name])
                    if (dest.name == SessionLayout.REFERENCE_PNG) refPath = dest.absolutePath
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
    ): File {
        val role = entryName.substringBefore('/', missingDelimiterValue = "")
        val name = entryName.substringAfter('/', missingDelimiterValue = "")
        if (role.isEmpty() || name.isEmpty()) throw CorruptTransferException("unexpected_zip_entry")
        val dest = destFor(role, name, layout)
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
     * restore paths share, with its parent directory created. Guards against
     * zip-slip: an entry must resolve to a path strictly **inside** the session
     * directory.
     *
     * The containment test compares whole path segments. A plain string-prefix
     * test let `../<id>X/…` through, since a sibling directory whose name merely
     * starts with this session's id shares its path as a prefix. An entry that
     * escapes is a hostile or broken archive, so it fails as a
     * [CorruptTransferException]: terminal, never retried.
     */
    @VisibleForTesting
    internal fun destFor(role: String, name: String, layout: SessionLayout): File {
        val dest = when {
            role == ArtifactRoles.RAW && name == SessionZip.REFERENCE_NAME -> layout.referencePng
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

// ── Helpers shared by the restore steps ────────────────────────────────────

/** A restore's scratch file in [cacheDir]; `CacheJanitor` reclaims the `restore_` prefix if one is left. */
private fun scratchFile(cacheDir: File, sessionId: String, suffix: String): File =
    File(cacheDir, "restore_${sessionId}_$suffix")

/** Deletes [file] and whatever an interrupted download into it left beside it. */
private fun discard(file: File) {
    file.delete()
    AtomicFiles.deleteSidecars(file)
}

/** The size the backup declared for this file, or -1 when it declared none (what `downloadFile` takes). */
private val CloudFileDto.declaredSize: Long get() = sizeBytes.takeIf { it > 0L } ?: -1L

/**
 * Downloads [entry] to [dest], reporting (bytes on disk, total) to [onProgress]
 * from (0, total) on. The total is the declared size, or what has arrived while
 * none was declared. Returns the total to report as done once the caller is
 * finished with the file.
 */
private suspend fun CloudApi.downloadReporting(
    token: String,
    entry: CloudFileDto,
    dest: File,
    onProgress: suspend (done: Long, total: Long) -> Unit,
): Long {
    val expected = entry.declaredSize
    val whole = expected.takeIf { it > 0L } ?: 1L
    onProgress(0L, whole)
    downloadFile(token, entry.fileId, dest, expectedBytes = expected) { have ->
        val total = if (expected > 0L) expected else have.coerceAtLeast(1L)
        onProgress(have.coerceAtMost(total), total)
    }
    return whole
}
