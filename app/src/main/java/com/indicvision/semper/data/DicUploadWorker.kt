// Upload worker: doWork orchestrates one cohesive resumable-upload flow (session
// create → per-file chunked PUT → complete), kept together with its literal step
// and retry constants; splitting it would scatter a single linear protocol.
@file:Suppress(
    "MagicNumber",
    "LongMethod",
    "CyclomaticComplexMethod",
    "NestedBlockDepth",
    "ReturnCount",
)

package com.indicvision.semper.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.net.FileCompleteRequest
import com.indicvision.semper.data.net.FileSpecDto
import com.indicvision.semper.data.net.HttpStatus
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.SessionCreateRequest
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.navigation.AppIntents
import com.indicvision.semper.util.Digests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Offline-first cloud sync against the Semper GCP backend — **one backend session
 * per analysis** (not per frame).
 *
 * Enqueued once per analysis with a network constraint. It reads the whole
 * analysis from [SessionStore] (so only the session id travels through
 * WorkManager's small Data), materialises every artifact, then:
 *  1. POSTs /v1/sessions with the full manifest (creates the Drive folder tree
 *     and one resumable upload URI per file) — device-signed,
 *  2. streams each file **directly to Google Drive** in resumable chunks —
 *     bytes never pass through the backend,
 *  3. POSTs /v1/files/{id}/complete to record each Drive pointer.
 *
 * Layout per analysis (2 files — everything except the metadata blueprint is
 * bundled into one archive to keep Firestore's per-file costs flat):
 * ```
 * session/<sid>/metadata.json   device, time, engine params, frame list
 *               Session.zip     raw/… (reference + deformed images),
 *                               dat/frame_%04d.dat  ← enables full restore,
 *                               csv/analysis_data.csv  (one combined file),
 *                               reports/Master_Report_<frame>.pdf,
 *                               processed/<frame>/<field>.png
 * ```
 */
class DicUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.uploadForeground(applicationContext)

    private data class Artifact(
        val role: String,
        val name: String,
        val file: File,
        /** Precomputed digest when available (e.g. hash-while-zip); else hashed on demand. */
        val sha256Hex: String? = null,
    )

    /** One file still to push: where to put it, and which local file it is. */
    private data class UploadJob(
        val fileId: String,
        val uploadUrl: String,
        val chunkSize: Int,
        val name: String,
        val file: File,
    )

    /** A session to upload into, and the files still to push. */
    private data class Plan(val sessionId: String, val work: List<UploadJob>)

    /** What resuming an existing session concluded. */
    private sealed interface Resume {
        /** Continue this session — [work] is the still-pending files (never empty). */
        data class Continue(val work: List<UploadJob>) : Resume

        /** Already fully uploaded in the cloud. */
        data object Done : Resume

        /** Unusable (gone, or its files don't match ours): delete it and start fresh. */
        data object Rebuild : Resume
    }

    /**
     * Decide whether an existing session can be continued.
     *
     * Every pending file must match a current artifact by role, name **and
     * size** — the session's resumable URIs were opened for exactly those sizes.
     * A single mismatch (an older build's report names, changed content) means
     * the session can't be finished, so we rebuild rather than silently upload a
     * partial set and mark it "synced". This is the guard against a false sync.
     */
    private suspend fun resumeSession(
        api: IndicApi,
        idToken: String,
        cloudSessionId: String,
        artifacts: List<Artifact>,
    ): Resume {
        val state = try {
            api.sessionUploads(idToken, cloudSessionId)
        } catch (e: IndicApi.ApiException) {
            Timber.w("Cannot query session %s (HTTP %d) — will rebuild", cloudSessionId, e.code)
            return Resume.Rebuild
        }
        if (state.status == "COMPLETED") return Resume.Done

        val byKey = artifacts.associateBy { it.role to it.name }
        val work = ArrayList<UploadJob>(state.uploads.size)
        var allMatch = true
        for (u in state.uploads) {
            val art = byKey[u.role to u.name]
            if (art == null || art.file.length() != u.sizeBytes) {
                Timber.w(
                    "Session %s incompatible: pending %s/%s (declared %d B) has no matching artifact",
                    cloudSessionId,
                    u.role,
                    u.name,
                    u.sizeBytes,
                )
                allMatch = false
                break
            }
            work.add(UploadJob(u.fileId, u.uploadUrl, u.chunkSize, u.name, art.file))
        }
        return when (
            UploadWorkOutcomes.classifyResume(
                sessionStatus = state.status.orEmpty(),
                pendingCount = if (allMatch) work.size else state.uploads.size,
                allPendingMatchArtifacts = allMatch,
            )
        ) {
            UploadWorkOutcomes.ResumeKind.DONE -> Resume.Done
            UploadWorkOutcomes.ResumeKind.REBUILD -> Resume.Rebuild
            UploadWorkOutcomes.ResumeKind.CONTINUE -> Resume.Continue(work)
        }
    }

    /** Declare the whole analysis and get one resumable target per file. */
    private suspend fun createSession(
        api: IndicApi,
        idToken: String,
        localId: String,
        record: SessionRecord,
        artifacts: List<Artifact>,
    ): Plan {
        val specs = artifacts.map {
            FileSpecDto(it.name, it.role, it.file.length(), it.sha256Hex ?: sha256(it.file))
        }
        val metrics = mapOf(
            "pointsConverged" to record.pointsConverged.toFloat(),
            "avgIterations" to record.avgIterations,
            "executionTimeMs" to record.executionTimeMs.toFloat(),
            "frameCount" to record.frameCount.toFloat(),
            "isSweep" to if (record.isSweep) 1f else 0f,
            "sweepSkipped" to record.sweepSkipCount.toFloat(),
        )
        val session = api.createSession(
            idToken,
            SessionCreateRequest(record.refName, specs, metrics, localSessionId = localId),
        )
        require(session.uploads.size == artifacts.size) {
            "Backend returned ${session.uploads.size} targets for ${artifacts.size} files"
        }
        val work = artifacts.mapIndexed { i, art ->
            val t = session.uploads[i]
            UploadJob(t.fileId, t.uploadUrl, t.chunkSize, art.name, art.file)
        }
        return Plan(session.sessionId, work)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val api = IndicApi.get(applicationContext)
        if (!api.enabled) {
            Timber.d("Cloud backend not configured — skipping upload")
            return@withContext Result.success()
        }
        val idToken = TokenProvider.usableIdToken()
        if (idToken == null) {
            Timber.w("No usable ID token yet — deferring upload")
            return@withContext Result.retry()
        }

        val localId = inputData.getString(DicKeys.SESSION_LOCAL_ID)
            ?: return@withContext Result.failure()
        val record = SessionStore.get(applicationContext, localId)
            ?: return@withContext Result.failure()

        // Terminal failure carrying a reason the UI can show. localId lets Home
        // find the row for a Retry action.
        fun failure(reason: String): Result = Result.failure(
            workDataOf(
                DicKeys.UPLOAD_FAIL_REASON to reason,
                DicKeys.SESSION_LOCAL_ID to localId,
            ),
        )

        val sessionDir = File(record.sessionDir)
        val rawDeformedDir = File(sessionDir, SessionPaths.RAW_DEFORMED_SUBDIR)

        // Generated artifacts live in a PERSISTENT staging dir, not cache. They
        // must be byte-identical across a resumed upload: createSession declared
        // each file's size/sha256, and a regenerated zip (new PDF dates, new zip
        // timestamps) would no longer match, so Drive's resumable URI and the
        // completeFile size check would never reconcile. Generating once and
        // reusing also skips the expensive report/zip work on every retry.
        val stagingDir = File(sessionDir, "upload_staging")
        if (record.cloudSessionId.isBlank()) {
            // Fresh upload (first attempt, or a re-run reset the cloud id) —
            // discard any files staged for a previous, now-superseded run.
            stagingDir.deleteRecursively()
        }
        stagingDir.mkdirs()

        // Live progress for the Home row: one throttled sampler emits phase+percent,
        // fed by the bundling frame count ("prepare") then the uploaded byte count
        // ("upload"). Decoupling the emit from the producers keeps WorkManager DB
        // writes cheap regardless of how fast frames/chunks complete.
        val progPhase = java.util.concurrent.atomic.AtomicReference("prepare")
        val progDone = java.util.concurrent.atomic.AtomicLong(0)
        val progTotal = java.util.concurrent.atomic.AtomicLong(record.defNames.size.toLong())
        val sampler = launch {
            while (isActive) {
                val total = progTotal.get()
                val pct = if (total > 0) (progDone.get() * 100 / total).toInt().coerceIn(0, 100) else 0
                setProgress(
                    workDataOf(
                        DicKeys.SESSION_LOCAL_ID to localId,
                        DicKeys.UPLOAD_PHASE to progPhase.get(),
                        DicKeys.UPLOAD_PERCENT to pct,
                    ),
                )
                delay(PROGRESS_SAMPLE_MS)
            }
        }

        try {
            val artifacts = mutableListOf<Artifact>()

            // ── session-level metadata (generated once, then reused) ────────
            val metaFile = File(stagingDir, "metadata.json")
            if (!metaFile.exists()) {
                metaFile.writeText(SessionUploadMetadata.buildMetadataJson(record, applicationContext))
            }
            artifacts += Artifact("metadata", "metadata.json", metaFile)

            // ── reference image (already stable on disk) ────────────────────
            val refFile = File(record.refPath)
            if (refFile.exists() && refFile.length() > 0) {
                artifacts += Artifact("raw", "Reference.png", refFile)
            }

            // ── per frame: original image, .dat, csv ────────────────────────
            // A sweep repeats the one image it ran on across every frame, so the
            // raw image is bundled once — a second identical `raw/<name>` entry
            // would make Session.zip throw a duplicate-entry exception.
            val addedRaw = HashSet<String>()
            record.defNames.forEachIndexed { index, defName ->
                val frameName = "Frame_${index + 1}"

                val defOriginal = File(rawDeformedDir, defName)
                if (defOriginal.exists() && defOriginal.length() > 0) {
                    if (addedRaw.add(defName)) {
                        artifacts += Artifact("raw", defName, defOriginal)
                    }
                } else {
                    Timber.w("Deformed image missing for %s: %s", frameName, defOriginal.absolutePath)
                }

                // The .dat is bundled so a restored session is fully viewable in
                // the app (the heatmap viewer reads it); it also feeds the CSV
                // and reports. The CSV is one combined file (below), not per frame.
                val datFile = File(sessionDir, String.format(Locale.US, "frame_%04d.dat", index))
                if (datFile.exists()) {
                    artifacts += Artifact("dat", datFile.name, datFile)
                } else {
                    Timber.w("No .dat for %s (%s)", frameName, datFile.name)
                }
            }

            // ── combined CSV + per-frame reports/heatmaps in ONE .dat decode
            //    pass (same writer the share/export uses for CSV; Session.zip
            //    compresses the staged plain files, so no nested archives) ────
            val analysisCsv = File(stagingDir, "analysis_data.csv")
            val reportsDir = File(stagingDir, "reports")
            val processedDir = File(stagingDir, "processed")
            // Marker written only after a COMPLETE report generation pass — a
            // dir half-filled by a killed run must not be mistaken for done.
            val bundlesDone = File(stagingDir, ".bundles_done")
            val needCsv = !analysisCsv.exists()
            val needBundles = record.defNames.isNotEmpty() && !bundlesDone.exists()
            if (needCsv || needBundles) {
                SessionUploadBundler.stageCsvAndBundles(
                    applicationContext,
                    record,
                    sessionDir,
                    refFile,
                    rawDeformedDir,
                    stagingDir,
                    csvFile = if (needCsv) analysisCsv else null,
                    writeReports = needBundles,
                    onFrame = { d, t ->
                        progDone.set(d.toLong())
                        progTotal.set(t.toLong())
                    },
                )
                if (needBundles) bundlesDone.createNewFile()
            }
            if (analysisCsv.length() > 0) artifacts += Artifact("csv", "analysis_data.csv", analysisCsv)

            if (record.defNames.isNotEmpty()) {
                val pdfs = reportsDir.listFiles()?.sortedBy { it.name }.orEmpty()
                pdfs.forEach { artifacts += Artifact("reports", it.name, it) }
                // Heatmaps now sit in per-frame subfolders; walk them and keep the
                // "<frame>/<field>.png" relative path as the artifact name, so the
                // bundle entry becomes processed/<frame>/<field>.png.
                val pngs = processedDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.toList()
                pngs.forEach {
                    artifacts += Artifact("processed", it.relativeTo(processedDir).invariantSeparatorsPath, it)
                }
                if (pdfs.isEmpty()) Timber.e("No frame reports generated for %s", localId)
                if (pngs.isEmpty()) Timber.e("No processed heatmaps generated for %s", localId)
            } else {
                Timber.e("Skipping reports for %s — no frames in the record", localId)
            }

            if (artifacts.isEmpty()) {
                Timber.w("No artifacts to upload for %s", localId)
                stagingDir.deleteRecursively()
                return@withContext Result.success()
            }

            // ── bundle: everything except metadata.json into ONE Session.zip ──
            // Firestore prices the whole flow per file (a doc, a signed complete
            // call, a challenge/nonce cycle each), so 3F+4 files per analysis was
            // burning the daily read quota in a single upload. One zip + the
            // metadata blueprint = 2 files, and Drive resumable uploads resume a
            // single large file mid-byte, so interruption recovery still works.
            val payload = artifacts.filter { it.role != "metadata" }
            val uploadSet = if (payload.isEmpty()) {
                artifacts.toList()
            } else {
                val bundleZip = File(stagingDir, "Session.zip")
                val hashSidecar = File(stagingDir, "Session.zip.sha256")
                val bundleSha = if (!bundleZip.exists() || bundleZip.length() == 0L) {
                    val hex = buildSessionBundle(payload, bundleZip)
                    hashSidecar.writeText(hex)
                    hex
                } else {
                    // Reuse the hash teed during zip write; fall back to one
                    // full read only when an older staging dir lacks the sidecar.
                    hashSidecar.takeIf { it.isFile }?.readText()?.trim()
                        ?.takeIf { it.length == SHA256_HEX_LEN }
                        ?: sha256(bundleZip).also { hashSidecar.writeText(it) }
                }
                artifacts.filter { it.role == "metadata" } +
                    Artifact("bundle", "Session.zip", bundleZip, bundleSha)
            }

            // ── resume an interrupted session, or create a new one ──────────
            Timber.i(
                "Uploading %s: %d files (%s)",
                localId,
                uploadSet.size,
                uploadSet.groupingBy { it.role }.eachCount(),
            )

            // Continue the session a prior run created, tracked by the stored
            // pointer. Deliberately NOT looked up by localSessionId: the staging
            // dir is cleared+regenerated whenever the pointer is blank, so
            // resuming a session found any other way would upload freshly-sized
            // files into a session that expects the old sizes → a size mismatch.
            val existingId = record.cloudSessionId.ifBlank { null }
            val plan: Plan = if (existingId == null) {
                Timber.i("No resumable session for %s — creating a new one", localId)
                createSession(api, idToken, localId, record, uploadSet)
            } else {
                when (val r = resumeSession(api, idToken, existingId, uploadSet)) {
                    is Resume.Continue -> {
                        Timber.i(
                            "Resuming session %s — %d of %d files still to upload",
                            existingId,
                            r.work.size,
                            uploadSet.size,
                        )
                        Plan(existingId, r.work)
                    }
                    Resume.Done -> {
                        // Everything already landed; a prior run died before it
                        // could record the sync locally.
                        Timber.i("Session %s already complete in the cloud", existingId)
                        SessionStore.markSynced(applicationContext, localId)
                        stagingDir.deleteRecursively()
                        return@withContext Result.success()
                    }
                    Resume.Rebuild -> {
                        // Unusable session — erase it (so it doesn't orphan/eat
                        // quota), drop the pointer + staged files, and rebuild
                        // fresh on the next run.
                        Timber.w("Discarding unusable session %s — rebuilding fresh", existingId)
                        runCatching { api.deleteSession(idToken, existingId) }
                            .onFailure { Timber.w(it, "Could not delete unusable session") }
                        SessionStore.setCloudSessionId(applicationContext, localId, "")
                        stagingDir.deleteRecursively()
                        return@withContext Result.retry()
                    }
                }
            }
            SessionStore.setCloudSessionId(applicationContext, localId, plan.sessionId)

            // ── upload straight to Drive, several files at a time ───────────
            // Sequential uploads left most of the link idle: every 8 MiB chunk
            // waits a full round-trip before the next starts, and a session is
            // mostly many smallish files. A few in flight keeps the pipe full.
            // Switch the Home progress to byte-based upload tracking.
            progPhase.set("upload")
            progDone.set(0)
            progTotal.set(plan.work.sumOf { it.file.length() })
            val total = plan.work.size
            coroutineScope {
                val gate = Semaphore(uploadConcurrency(applicationContext))
                plan.work.map { job ->
                    async {
                        gate.withPermit {
                            Timber.d("Uploading %s (%d bytes)…", job.name, job.file.length())
                            val (driveId, md5) = api.uploadResumable(
                                job.uploadUrl,
                                job.file,
                                job.chunkSize,
                            ) { n -> progDone.addAndGet(n) }
                            // Re-read the token: a long upload can outlive it.
                            val tk = TokenProvider.usableIdToken() ?: idToken
                            api.completeFile(
                                tk,
                                job.fileId,
                                FileCompleteRequest(plan.sessionId, driveId, job.file.length(), md5),
                            )
                        }
                    }
                }.awaitAll()
            }

            SessionStore.markSynced(applicationContext, localId)
            Timber.i("Upload complete for %s (%d files, session %s)", localId, total, plan.sessionId)
            stagingDir.deleteRecursively() // done — staged files no longer needed
            Result.success()
        } catch (e: IndicApi.DeviceNotActiveException) {
            // The server has no ACTIVE device record for us (revoked/reset) while
            // our local "registered" flag said otherwise. Re-register and retry
            // instead of stalling forever. Keep staging for the retry.
            Timber.w("Device not active server-side — re-registering and retrying")
            TokenStore.setDeviceRegistered(applicationContext, false)
            runCatching { api.registerDevice(idToken) }
                .onSuccess { TokenStore.setDeviceRegistered(applicationContext, true) }
                .onFailure { Timber.e(it, "Re-registration failed") }
            Result.retry()
        } catch (e: IndicApi.DeviceConflictException) {
            Timber.e("This account is bound to a different device — cannot upload")
            SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
            stagingDir.deleteRecursively()
            failure(applicationContext.getString(R.string.cloud_backup_failed_device))
        } catch (e: IndicApi.ApiException) {
            when {
                // CONFLICT = analysis quota reached, PAYLOAD_TOO_LARGE = too many files.
                UploadWorkOutcomes.isTerminalClientError(e.code) -> {
                    Timber.e("Upload rejected (%d): %s", e.code, e.detail)
                    // CONFLICT means the account's analysis quota is full — raise the
                    // persistent limit gate so the user is told to email support.
                    SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
                    stagingDir.deleteRecursively()
                    if (UploadWorkOutcomes.isQuotaExhausted(e.code)) {
                        // Quota full has its own persistent "email support" screen —
                        // surface it there, not via a transient Home snackbar.
                        TokenStore.setSessionLimitReached(applicationContext, true)
                        applicationContext.startActivity(AppIntents.sessionLimit(applicationContext))
                        Result.failure()
                    } else {
                        // Payload too large — retrying won't help; tell the user.
                        failure(applicationContext.getString(R.string.cloud_backup_failed_too_large))
                    }
                }
                // 400 = the resumable session's expected size no longer matches our
                // files (a session from an earlier build, or content that changed).
                // The session is unrecoverable: drop the pointer + staged files so
                // the next run rebuilds a fresh session that matches.
                e.code == HttpStatus.BAD_REQUEST -> {
                    Timber.e(
                        "Upload 400 (%s) — discarding stale session %s, rebuilding",
                        e.detail,
                        record.cloudSessionId,
                    )
                    // Erase the half-uploaded session so it doesn't orphan and
                    // eat a quota slot, then rebuild fresh next run.
                    runCatching {
                        if (record.cloudSessionId.isNotBlank()) api.deleteSession(idToken, record.cloudSessionId)
                    }.onFailure { Timber.w(it, "Could not delete stale session") }
                    SessionStore.setCloudSessionId(applicationContext, localId, "")
                    stagingDir.deleteRecursively()
                    UploadWorkOutcomes.fromHttpCode(e.code)
                }
                else -> {
                    // Transient — keep the staged files so the retry resumes identically.
                    Timber.e(e, "Upload failed for %s; will retry", localId)
                    UploadWorkOutcomes.fromHttpCode(e.code)
                }
            }
        } catch (e: OutOfMemoryError) {
            // OOM is an Error, not an Exception, so it would otherwise escape every
            // catch above and surface as an untracked WorkManager failure with no
            // reason — the badge would stay "pending" and a manual retry would just
            // re-OOM forever. Treat it as terminal with a clear reason instead.
            Timber.e(e, "Upload ran out of memory bundling %s — failing terminally", localId)
            SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
            stagingDir.deleteRecursively()
            failure(applicationContext.getString(R.string.cloud_backup_failed_too_large))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Upload failed for %s; will retry", localId)
            Result.retry()
        } finally {
            // Stop the progress sampler so this coroutine can complete (a live
            // child would otherwise keep the worker from returning).
            sampler.cancel()
        }
    }

    /**
     * Pack the payload artifacts into one archive, entries named `role/name`
     * (`raw/Reference.png`, `dat/frame_0000.dat`, …) so restore can rebuild the
     * exact per-role layout. Built once into the persistent staging dir and
     * reused byte-identically on retries — zip entry timestamps differ across
     * rebuilds, which would break the declared sha256/size of a resumed upload.
     *
     * Returns the SHA-256 of the finished zip bytes (teed via
     * [java.security.DigestOutputStream] while writing) so createSession need
     * not re-read the whole archive.
     */
    private fun buildSessionBundle(payload: List<Artifact>, out: File): String {
        val digest = Digests.sha256()
        java.security.DigestOutputStream(BufferedOutputStream(out.outputStream()), digest).use { digOut ->
            ZipOutputStream(digOut).use { zip ->
                payload.forEach { art ->
                    // JPEG/PNG/PDF are already compressed — deflating them again
                    // burns CPU (they're ~90% of the payload bytes) for ~0% gain.
                    // Level 0 stores them; .dat/.csv/.json/TIFF still compress.
                    val precompressed = art.name.substringAfterLast('.').lowercase(Locale.US) in NO_RECOMPRESS
                    zip.setLevel(
                        if (precompressed) {
                            java.util.zip.Deflater.NO_COMPRESSION
                        } else {
                            java.util.zip.Deflater.DEFAULT_COMPRESSION
                        },
                    )
                    zip.putNextEntry(ZipEntry("${art.role}/${art.name}"))
                    art.file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        Timber.i("Bundled %d artifacts into %s (%d bytes)", payload.size, out.name, out.length())
        return hex
    }

    private fun sha256(file: File): String {
        val md = Digests.sha256()
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        /** How often the progress sampler pushes phase+percent to WorkManager. */
        const val PROGRESS_SAMPLE_MS = 700L

        /** Files uploaded concurrently. Keeps the link busy without thrashing. */
        const val UPLOAD_CONCURRENCY = 4

        /** Soft cap on concurrent 32 MiB chunk buffers on low-RAM devices. */
        fun uploadConcurrency(context: Context): Int {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val lowRam = am?.isLowRamDevice == true
            return if (lowRam) 1 else UPLOAD_CONCURRENCY
        }

        /** Hex length of a SHA-256 digest. */
        const val SHA256_HEX_LEN = 64

        /** Extensions that are already compressed — stored, not re-deflated, in Session.zip. */
        val NO_RECOMPRESS = setOf("jpg", "jpeg", "png", "pdf", "webp", "zip")
    }
}
