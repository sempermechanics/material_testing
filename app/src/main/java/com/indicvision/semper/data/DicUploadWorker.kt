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
import java.io.File
import java.util.Locale

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

        /** The backend is still opening upload targets — poll again shortly. */
        data object Wait : Resume

        /** Cloud Tasks / Drive failed to open targets — terminal for this session. */
        data object ProvisionFailed : Resume
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
            UploadWorkOutcomes.ResumeKind.WAIT -> Resume.Wait
            UploadWorkOutcomes.ResumeKind.PROVISION_FAILED -> Resume.ProvisionFailed
        }
    }

    /**
     * Poll until the backend has opened this session's upload targets.
     *
     * Provisioning runs as a Cloud Task, so a freshly created session reports
     * PROVISIONING with an empty upload list for a moment. Bounded: if it has
     * not finished within [PROVISION_POLL_ATTEMPTS], hand back to WorkManager
     * rather than holding a foreground worker open indefinitely.
     */
    private suspend fun awaitProvisioned(
        api: IndicApi,
        idToken: String,
        cloudSessionId: String,
        artifacts: List<Artifact>,
    ): Resume {
        var delayMs = PROVISION_POLL_INITIAL_MS
        repeat(PROVISION_POLL_ATTEMPTS) {
            delay(delayMs)
            when (val resumed = resumeSession(api, idToken, cloudSessionId, artifacts)) {
                is Resume.Wait -> delayMs = (delayMs * 2).coerceAtMost(PROVISION_POLL_MAX_MS)
                else -> return resumed
            }
        }
        Timber.w("Session %s still provisioning after polling — will retry later", cloudSessionId)
        return Resume.Wait
    }

    /**
     * Turn a [Resume] from create/poll into a [Plan], or null / throw for the
     * caller to retry or fail.
     */
    private suspend fun planFromResume(
        api: IndicApi,
        idToken: String,
        localId: String,
        cloudSessionId: String,
        resumed: Resume,
    ): Plan? = when (resumed) {
        is Resume.Continue -> Plan(cloudSessionId, resumed.work)
        Resume.Rebuild, Resume.Done -> {
            Timber.w(
                "Session %s create/poll ended %s — clear pointer for recreate",
                cloudSessionId,
                resumed,
            )
            SessionStore.setCloudSessionId(applicationContext, localId, "")
            null
        }
        Resume.Wait -> {
            Timber.w("Session %s still PROVISIONING after create poll budget", cloudSessionId)
            null
        }
        Resume.ProvisionFailed -> {
            Timber.e("Session %s provision failed — not retrying create loop", cloudSessionId)
            runCatching { api.deleteSession(idToken, cloudSessionId) }
            SessionStore.setCloudSessionId(applicationContext, localId, "")
            throw ProvisionFailedException()
        }
    }

    /**
     * Declare the whole analysis and obtain one resumable target per file.
     *
     * The backend opens those targets in a Cloud Task rather than inside the
     * request (600 files was ~1200 sequential Drive round-trips in a 60s
     * budget), so the response may come back PROVISIONING with an empty upload
     * list. In that case we record the session id and poll the resume endpoint
     * until the targets exist. Returns null when provisioning has not finished
     * in time — the caller retries the whole job later.
     *
     * **Never** map `SessionCreateResponse.uploads` by list index. Those
     * targets are listed in Firestore document-id order
     * (`{sid}_{role}_{name}`), so `bundle/Session.zip` sorts *before*
     * `metadata/metadata.json`. Index pairing PUT the ~2 KB JSON onto the
     * ~84 MB zip resumable URI → Drive 400 Content-Range size mismatch.
     * Always resolve via [resumeSession] / [awaitProvisioned] (role+name+size).
     */
    private suspend fun createSession(
        api: IndicApi,
        idToken: String,
        localId: String,
        record: SessionRecord,
        artifacts: List<Artifact>,
    ): Plan? {
        val specs = artifacts.map {
            FileSpecDto(it.name, it.role, it.file.length(), it.sha256Hex ?: Digests.sha256Hex(it.file))
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
        // Persist the pointer before anything can fail: a session that exists in
        // the cloud but is not recorded here would be orphaned against quota.
        SessionStore.setCloudSessionId(applicationContext, localId, session.sessionId)

        return when (val first = resumeSession(api, idToken, session.sessionId, artifacts)) {
            is Resume.Continue -> Plan(session.sessionId, first.work)
            Resume.Wait -> {
                Timber.w("Session %s is provisioning — waiting for upload targets", session.sessionId)
                planFromResume(
                    api,
                    idToken,
                    localId,
                    session.sessionId,
                    awaitProvisioned(api, idToken, session.sessionId, artifacts),
                )
            }
            else -> planFromResume(api, idToken, localId, session.sessionId, first)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Expedited work can fall back to a normal request when the OS is out of
        // expedited quota. Without an explicit foreground promotion the worker is
        // then eligible to be stopped when the app backgrounds mid-prepare —
        // which looked like "preparing finished → pending → preparing again".
        setForeground(getForegroundInfo())

        val api = IndicApi.get(applicationContext)
        if (!api.enabled) {
            Timber.d("Cloud backend not configured — skipping upload")
            return@withContext Result.success()
        }
        val idToken = TokenProvider.usableIdToken()
        if (idToken == null) {
            return@withContext retryLater("(no-session)", "no usable Firebase ID token")
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
        // Only wipe incomplete staging. A blank cloudSessionId after Rebuild /
        // provision failure must NOT destroy a finished Session.zip — that was
        // forcing a full prepare loop on every WorkManager retry.
        if (record.cloudSessionId.isBlank() && !UploadWorkOutcomes.stagingReusable(stagingDir)) {
            stagingDir.deleteRecursively()
        }
        stagingDir.mkdirs()

        // Live progress for the Home row: one throttled sampler emits phase+percent,
        // fed by the bundling frame count ("prepare") then the uploaded byte count
        // ("upload"). Decoupling the emit from the producers keeps WorkManager DB
        // writes cheap regardless of how fast frames/chunks complete.
        val reuseStaging = UploadWorkOutcomes.stagingReusable(stagingDir)
        val progPhase = java.util.concurrent.atomic.AtomicReference(
            if (reuseStaging) "upload" else "prepare",
        )
        val progDone = java.util.concurrent.atomic.AtomicLong(0)
        val progTotal = java.util.concurrent.atomic.AtomicLong(
            if (reuseStaging) 1L else record.defNames.size.toLong().coerceAtLeast(1L),
        )
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
            // dir half-filled by a killed run, or a pass that skipped every
            // PDF/heatmap, must not be mistaken for done.
            val bundlesDone = File(stagingDir, ".bundles_done")
            val needCsv = !analysisCsv.exists() || analysisCsv.length() == 0L
            val needBundles = record.defNames.isNotEmpty() &&
                !UploadWorkOutcomes.bundleArtifactsReady(stagingDir)
            if (needCsv || needBundles) {
                // Restaging invalidates any prior Session.zip — it was built
                // without the artifacts we are about to (re)generate.
                File(stagingDir, "Session.zip").delete()
                File(stagingDir, "Session.zip.tmp").delete()
                File(stagingDir, "Session.zip.sha256").delete()
                if (needBundles) bundlesDone.delete()
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
                if (record.defNames.isNotEmpty()) {
                    if (UploadWorkOutcomes.bundleArtifactsReady(stagingDir)) {
                        bundlesDone.createNewFile()
                    } else if (needBundles) {
                        // Do not upload a raw+dat-only zip as "synced". Sweeps
                        // hit this when report bake skips (bad dims / undecodable
                        // base image / every .dat missing) — retry so a later
                        // pass can succeed, or WorkManager exhausts attempts.
                        Timber.e(
                            "Bundle staging incomplete for %s (csv=%dB, reports/processed missing) — retrying",
                            localId,
                            analysisCsv.length(),
                        )
                        return@withContext retryLater(
                            localId,
                            "bundle staging incomplete — reports/csv/processed not ready",
                        )
                    }
                }
            }
            if (analysisCsv.length() > 0) artifacts += Artifact("csv", "analysis_data.csv", analysisCsv)

            if (record.defNames.isNotEmpty()) {
                val pdfs = reportsDir.listFiles()
                    ?.filter { it.isFile }
                    ?.sortedBy { it.name }
                    .orEmpty()
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
                // Reuse only a sidecar-verified archive (see stagingReusable).
                // Never invent a sidecar from a leftover truncated Session.zip —
                // that uploaded bit-identical corrupt Drive objects.
                val bundleSha = UploadWorkOutcomes.verifiedBundleSha256(bundleZip, hashSidecar)
                    ?.takeIf { reuseStaging }
                    ?: run {
                        bundleZip.delete()
                        hashSidecar.delete()
                        File(stagingDir, "Session.zip.tmp").delete()
                        val hex = buildSessionBundle(payload, bundleZip)
                        hashSidecar.writeText(hex)
                        hex
                    }
                artifacts.filter { it.role == "metadata" } +
                    Artifact("bundle", "Session.zip", bundleZip, bundleSha)
            }

            // Bundling is done — leave the "preparing" badge before we wait on
            // Cloud Tasks / Drive so a provision retry does not look like another
            // full prepare cycle.
            progPhase.set("upload")
            progDone.set(0)
            progTotal.set(1)

            // ── resume an interrupted session, or create a new one ──────────
            Timber.i(
                "Uploading %s: %d files (%s)",
                localId,
                uploadSet.size,
                uploadSet.groupingBy { it.role }.eachCount(),
            )

            // Continue the session a prior run created, tracked by the stored
            // pointer. Deliberately NOT looked up by localSessionId: incomplete
            // staging is cleared when the pointer is blank, so resuming a session
            // found any other way would upload freshly-sized files into a session
            // that expects the old sizes → a size mismatch.
            val existingId = record.cloudSessionId.ifBlank { null }
            val plan: Plan = if (existingId == null) {
                Timber.w("Upload %s — creating a new cloud session", localId)
                createSession(api, idToken, localId, record, uploadSet)
                    // Still provisioning when we ran out of patience. The session
                    // id is already stored, so the next run resumes it rather
                    // than creating a second one.
                    ?: return@withContext retryLater(
                        localId,
                        "createSession returned null (still provisioning or rebuild)",
                    )
            } else {
                when (val r = resumeSession(api, idToken, existingId, uploadSet)) {
                    is Resume.Continue -> {
                        Timber.w(
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
                        Timber.w("Session %s already complete in the cloud", existingId)
                        SessionStore.markSynced(applicationContext, localId)
                        stagingDir.deleteRecursively()
                        return@withContext Result.success()
                    }
                    Resume.Rebuild -> {
                        // Manifest mismatch / gone — erase cloud row, keep staging,
                        // recreate on the next run.
                        Timber.w("Discarding unusable session %s — keeping staging for recreate", existingId)
                        runCatching { api.deleteSession(idToken, existingId) }
                            .onFailure { Timber.w(it, "Could not delete unusable session") }
                        SessionStore.setCloudSessionId(applicationContext, localId, "")
                        return@withContext retryLater(localId, "resume Rebuild — will recreate session")
                    }
                    Resume.ProvisionFailed -> {
                        Timber.e("Session %s provision failed — failing backup (no create loop)", existingId)
                        runCatching { api.deleteSession(idToken, existingId) }
                            .onFailure { Timber.w(it, "Could not delete failed-provision session") }
                        SessionStore.setCloudSessionId(applicationContext, localId, "")
                        SessionStore.setSyncState(
                            applicationContext,
                            localId,
                            SessionRecord.SyncState.FAILED,
                        )
                        return@withContext failure(
                            applicationContext.getString(R.string.cloud_backup_failed_provision),
                        )
                    }
                    Resume.Wait -> {
                        // Do NOT Result.retry() immediately — that burned WorkManager
                        // attempts in ~2s with no progress. Poll in-process first.
                        Timber.w("Session %s still provisioning — polling for upload targets", existingId)
                        when (val polled = awaitProvisioned(api, idToken, existingId, uploadSet)) {
                            is Resume.Continue -> {
                                Timber.w(
                                    "Session %s provisioned — %d files to upload",
                                    existingId,
                                    polled.work.size,
                                )
                                Plan(existingId, polled.work)
                            }
                            Resume.Done -> {
                                SessionStore.markSynced(applicationContext, localId)
                                stagingDir.deleteRecursively()
                                return@withContext Result.success()
                            }
                            Resume.Rebuild -> {
                                Timber.w("Session %s became unusable while polling — recreate", existingId)
                                runCatching { api.deleteSession(idToken, existingId) }
                                SessionStore.setCloudSessionId(applicationContext, localId, "")
                                return@withContext retryLater(
                                    localId,
                                    "provision poll Rebuild — will recreate session",
                                )
                            }
                            Resume.ProvisionFailed -> {
                                Timber.e("Session %s provision failed while polling", existingId)
                                runCatching { api.deleteSession(idToken, existingId) }
                                SessionStore.setCloudSessionId(applicationContext, localId, "")
                                SessionStore.setSyncState(
                                    applicationContext,
                                    localId,
                                    SessionRecord.SyncState.FAILED,
                                )
                                return@withContext failure(
                                    applicationContext.getString(R.string.cloud_backup_failed_provision),
                                )
                            }
                            Resume.Wait -> {
                                return@withContext retryLater(
                                    localId,
                                    "still PROVISIONING after in-process poll budget",
                                )
                            }
                        }
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
            // A session only becomes droppable once it is backed up, so this is
            // the moment an over-budget phone can actually get space back.
            StorageBudget.enforce(applicationContext)
            Result.success()
        } catch (_: ProvisionFailedException) {
            SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
            failure(applicationContext.getString(R.string.cloud_backup_failed_provision))
        } catch (e: IndicApi.DeviceNotActiveException) {
            // The server has no ACTIVE device record for us (revoked/reset) while
            // our local "registered" flag said otherwise. Re-register and retry
            // instead of stalling forever. Keep staging for the retry.
            TokenStore.setDeviceRegistered(applicationContext, false)
            runCatching { api.registerDevice(idToken) }
                .onSuccess { TokenStore.setDeviceRegistered(applicationContext, true) }
                .onFailure { Timber.e(it, "Re-registration failed") }
            retryLater(localId, "device not active — re-registered, retry upload")
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
                    // [record] was snapshotted at doWork start — createSession may
                    // have written cloudSessionId afterward. Re-read before delete.
                    val cloudId = SessionStore.get(applicationContext, localId)
                        ?.cloudSessionId.orEmpty()
                        .ifBlank { record.cloudSessionId }
                    Timber.e(
                        "Upload 400 (%s) — discarding stale session %s, keeping staging",
                        e.detail,
                        cloudId,
                    )
                    // Erase the half-uploaded session so it doesn't orphan and
                    // eat a quota slot. Keep Session.zip so recreate is cheap.
                    runCatching {
                        if (cloudId.isNotBlank()) api.deleteSession(idToken, cloudId)
                    }.onFailure { Timber.w(it, "Could not delete stale session") }
                    SessionStore.setCloudSessionId(applicationContext, localId, "")
                    retryLater(localId, "HTTP 400 stale session — ${e.detail.take(120)}")
                }
                else -> {
                    // Transient — keep the staged files so the retry resumes identically.
                    Timber.e(e, "Upload HTTP %d for %s — %s", e.code, localId, e.detail)
                    retryLater(localId, "HTTP ${e.code}: ${e.detail.take(160)}")
                }
            }
        } catch (e: OutOfMemoryError) {
            // OOM is an Error, not an Exception, so it would otherwise escape every
            // catch above and surface as an untracked WorkManager failure with no
            // reason — the badge would stay "pending" and a manual retry would just
            // re-OOM forever. Treat it as terminal with a clear reason instead.
            // Distinct from HTTP 413 "too large": the session may fit the cloud
            // quota but this device cannot pack it in RAM.
            Timber.e(e, "Upload ran out of memory bundling %s — failing terminally", localId)
            SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
            stagingDir.deleteRecursively()
            failure(applicationContext.getString(R.string.cloud_backup_failed_oom))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Upload failed for %s; will retry", localId)
            retryLater(
                localId,
                "${e.javaClass.simpleName}: ${e.message?.take(160) ?: "(no message)"}",
            )
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
     * Returns the SHA-256 of the finished zip ([SessionZip] tees a digest while
     * writing and round-trip-verifies every entry before promote).
     */
    private fun buildSessionBundle(payload: List<Artifact>, out: File): String =
        SessionZip.build(
            payload.map { SessionZip.Member(it.role, it.name, it.file) },
            out,
        )

    /**
     * Every WorkManager RETRY must leave a WARN in logcat (release
     * [CrashReportingTree] mirrors WARN+). Without this, alpha only saw
     * `Worker result RETRY` with no Semper reason.
     */
    private fun retryLater(localId: String, reason: String): Result {
        Timber.w("Upload RETRY localId=%s — %s", localId, reason)
        return Result.retry()
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

        // Polling while the backend provisions upload targets in a Cloud Task.
        // Bounded on purpose: past this the job hands back to WorkManager rather
        // than holding a foreground worker (and its notification) open. The
        // session pointer is already stored, so the retry resumes it. ~12 polls
        // covers slow Drive/Tasks without immediately bouncing to pending.
        const val PROVISION_POLL_ATTEMPTS = 12
        const val PROVISION_POLL_INITIAL_MS = 1_000L
        const val PROVISION_POLL_MAX_MS = 8_000L
    }

    /** Drive/Cloud Tasks could not open resumable upload targets for this session. */
    private class ProvisionFailedException : Exception()
}
