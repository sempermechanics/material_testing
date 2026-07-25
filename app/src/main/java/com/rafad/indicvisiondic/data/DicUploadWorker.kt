package com.rafad.indicvisiondic.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.DicResult
import com.rafad.indicvisiondic.data.net.FileCompleteRequest
import com.rafad.indicvisiondic.data.net.FileSpecDto
import com.rafad.indicvisiondic.data.net.IndicApi
import com.rafad.indicvisiondic.data.net.SessionCreateRequest
import com.rafad.indicvisiondic.data.net.TokenProvider
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.report.AnalysisCsvWriter
import com.rafad.indicvisiondic.report.EngineStats
import com.rafad.indicvisiondic.report.FieldResult
import com.rafad.indicvisiondic.report.PdfReportGenerator
import com.rafad.indicvisiondic.report.ReportBuilder
import com.rafad.indicvisiondic.report.RoiData
import com.rafad.indicvisiondic.ui.analysis.AnalysisViewModel
import com.rafad.indicvisiondic.ui.limit.SessionLimitActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Offline-first cloud sync against the inDIC GCP backend — **one backend session
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

    private data class Artifact(val role: String, val name: String, val file: File)

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
                return Resume.Rebuild
            }
            work.add(UploadJob(u.fileId, u.uploadUrl, u.chunkSize, u.name, art.file))
        }
        if (work.isEmpty()) {
            // No pending files, yet not COMPLETED — inconsistent; don't trust it.
            Timber.w("Session %s has no pending uploads but isn't COMPLETED — rebuilding", cloudSessionId)
            return Resume.Rebuild
        }
        return Resume.Continue(work)
    }

    /** Declare the whole analysis and get one resumable target per file. */
    private suspend fun createSession(
        api: IndicApi,
        idToken: String,
        localId: String,
        record: SessionRecord,
        artifacts: List<Artifact>,
    ): Plan {
        val specs = artifacts.map { FileSpecDto(it.name, it.role, it.file.length(), sha256(it.file)) }
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
        val api = IndicApi(applicationContext)
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

        val sessionDir = File(record.sessionDir)
        val rawDeformedDir = File(sessionDir, AnalysisViewModel.RAW_DEFORMED_SUBDIR)

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

        try {
            val artifacts = mutableListOf<Artifact>()

            // ── session-level metadata (generated once, then reused) ────────
            val metaFile = File(stagingDir, "metadata.json")
            if (!metaFile.exists()) metaFile.writeText(buildMetadataJson(record))
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

            // ── one combined CSV for the whole analysis, via the same writer the
            //    app's share/export uses, instead of a bare file per frame ──────
            val analysisCsv = File(stagingDir, "analysis_data.csv")
            if (!analysisCsv.exists()) {
                AnalysisCsvWriter.write(analysisCsv, record.isSweep, csvFrames(record, sessionDir))
            }
            if (analysisCsv.length() > 0) artifacts += Artifact("csv", "analysis_data.csv", analysisCsv)

            // ── per-frame reports + heatmaps (generated once, as plain files:
            // Session.zip compresses the whole payload, so nesting archives
            // inside it would just deflate already-deflated bytes) ───────────
            if (record.defNames.isNotEmpty()) {
                val reportsDir = File(stagingDir, "reports")
                val processedDir = File(stagingDir, "processed")
                // Marker written only after a COMPLETE generation pass — a dir
                // half-filled by a killed run must not be mistaken for done.
                val bundlesDone = File(stagingDir, ".bundles_done")
                if (!bundlesDone.exists()) {
                    buildBundles(record, sessionDir, refFile, rawDeformedDir, stagingDir)
                    bundlesDone.createNewFile()
                }
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
                if (!bundleZip.exists() || bundleZip.length() == 0L) {
                    buildSessionBundle(payload, bundleZip)
                }
                artifacts.filter { it.role == "metadata" } + Artifact("bundle", "Session.zip", bundleZip)
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
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val total = plan.work.size
            coroutineScope {
                val gate = Semaphore(UPLOAD_CONCURRENCY)
                plan.work.map { job ->
                    async {
                        gate.withPermit {
                            Timber.d("Uploading %s (%d bytes)…", job.name, job.file.length())
                            val (driveId, md5) = api.uploadResumable(job.uploadUrl, job.file, job.chunkSize)
                            // Re-read the token: a long upload can outlive it.
                            val tk = TokenProvider.usableIdToken() ?: idToken
                            api.completeFile(
                                tk,
                                job.fileId,
                                FileCompleteRequest(plan.sessionId, driveId, job.file.length(), md5),
                            )
                            setProgress(
                                androidx.work.workDataOf(
                                    "done" to done.incrementAndGet(),
                                    "total" to total,
                                ),
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
            Result.failure()
        } catch (e: IndicApi.ApiException) {
            when {
                // 409 = analysis quota reached, 413 = too many files: retrying won't help.
                e.code == 409 || e.code == 413 -> {
                    Timber.e("Upload rejected (%d): %s", e.code, e.detail)
                    // 409 means the account's analysis quota is full — raise the
                    // persistent limit gate so the user is told to email support.
                    if (e.code == 409) {
                        TokenStore.setSessionLimitReached(applicationContext, true)
                        applicationContext.startActivity(
                            Intent(applicationContext, SessionLimitActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                            },
                        )
                    }
                    SessionStore.setSyncState(applicationContext, localId, SessionRecord.SyncState.FAILED)
                    stagingDir.deleteRecursively()
                    Result.failure()
                }
                // 400 = the resumable session's expected size no longer matches our
                // files (a session from an earlier build, or content that changed).
                // The session is unrecoverable: drop the pointer + staged files so
                // the next run rebuilds a fresh session that matches.
                e.code == 400 -> {
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
                    Result.retry()
                }
                else -> {
                    // Transient — keep the staged files so the retry resumes identically.
                    Timber.e(e, "Upload failed for %s; will retry", localId)
                    Result.retry()
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.e(e, "Upload failed for %s; will retry", localId)
            Result.retry()
        }
    }

    /** Session-level metadata: device, time, engine params, and the frame list. */
    /** One JSON object per frame: its label, files, and (for a sweep) its settings. */
    private fun framesJson(record: SessionRecord): JSONArray {
        val frames = JSONArray()
        record.defNames.forEachIndexed { index, name ->
            val frameObj = JSONObject()
                .put("index", index)
                .put(
                    "frame",
                    if (record.isSweep) {
                        record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
                    } else {
                        "Frame_${index + 1}"
                    },
                )
                .put("image", name)
                .put("dat", String.format(Locale.US, "frame_%04d.dat", index))
            if (record.isSweep) {
                val subset = record.sweepSubsets.getOrElse(index) { record.subset }
                val step = record.sweepSteps.getOrElse(index) { record.step }
                val window = record.sweepStrainWindows.getOrElse(index) { record.strainWindow }
                frameObj
                    .put("subset", subset)
                    .put("step", step)
                    .put("strainWindow", window)
                    .put("vsg", (window - 1) * step + 1)
            }
            frames.put(frameObj)
        }
        return frames
    }

    private fun buildMetadataJson(record: SessionRecord): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())
        val frames = framesJson(record)
        val metrics = JSONObject()
            .put("pointsConverged", record.pointsConverged)
            .put("avgIterations", record.avgIterations.toDouble())
            .put("executionTimeMs", record.executionTimeMs)
        if (record.isSweep) {
            metrics
                .put("isSweep", true)
                .put("sweepSolved", record.frameCount)
                .put("sweepSkipped", record.sweepSkipCount)
        }
        return JSONObject()
            .put("schema", "indic.session.metadata/2")
            .put("localSessionId", record.id)
            .put("name", record.name)
            .put("specimen", record.refName)
            .put("capturedAtUtc", iso)
            .put("frameCount", record.frameCount)
            .put("analysisKind", if (record.isSweep) "vsg_study" else "batch")
            // One combined CSV for the whole analysis (every frame's points, keyed
            // by the leading columns) rather than a file per frame.
            .put("csv", "analysis_data.csv")
            .put("frames", frames)
            .put(
                "app",
                JSONObject()
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE),
            )
            .put("device", deviceJson(applicationContext))
            .put(
                "user",
                JSONObject()
                    .put("uid", TokenStore.cachedUid(applicationContext))
                    .put("email", TokenStore.cachedEmail(applicationContext)),
            )
            .put("engine", engineJson(record))
            .put("metrics", metrics)
            .toString(2)
    }

    /**
     * The frames for the combined analysis CSV. A sweep leads each row with its
     * per-combination settings and shares the one image it ran on; a batch leads
     * with each frame's own image.
     */
    private fun csvFrames(record: SessionRecord, sessionDir: File): List<AnalysisCsvWriter.Frame> {
        val sweepImage = record.defNames.firstOrNull().orEmpty()
        return record.defNames.indices.map { index ->
            val datFile = File(sessionDir, String.format(Locale.US, "frame_%04d.dat", index))
            AnalysisCsvWriter.Frame(
                image = if (record.isSweep) sweepImage else record.defNames.getOrElse(index) { "Frame_${index + 1}" },
                subset = record.sweepSubsets.getOrElse(index) { record.subset },
                step = record.sweepSteps.getOrElse(index) { record.step },
                strainWindow = record.sweepStrainWindows.getOrElse(index) { record.strainWindow },
                data = { if (datFile.exists()) DicResult.decodeDatBytes(datFile.readBytes()) else null },
            )
        }
    }

    /**
     * Pack the payload artifacts into one archive, entries named `role/name`
     * (`raw/Reference.png`, `dat/frame_0000.dat`, …) so restore can rebuild the
     * exact per-role layout. Built once into the persistent staging dir and
     * reused byte-identically on retries — zip entry timestamps differ across
     * rebuilds, which would break the declared sha256/size of a resumed upload.
     */
    private fun buildSessionBundle(payload: List<Artifact>, out: File) {
        ZipOutputStream(BufferedOutputStream(out.outputStream())).use { zip ->
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
        Timber.i("Bundled %d artifacts into %s (%d bytes)", payload.size, out.name, out.length())
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
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

    private data class BundleCounts(val reports: Int, val processed: Int)

    /**
     * One pass over the frames producing both artifact sets as plain files in
     * the staging dir (Session.zip compresses everything at the end, so there
     * is no point deflating them twice into nested archives):
     *  - `reports/Master_Report_Frame_N.pdf`
     *  - `processed/Frame_N_<field>.png` — the U/V/Exx/Eyy/Exy heatmaps
     *
     * They're built together deliberately: [ReportBuilder.buildReport] already
     * bakes the field heatmaps to make the PDF, so writing them out here costs
     * nothing extra. Rendering them in a second pass would double the most
     * expensive work in the whole upload.
     *
     * The PDF is rendered to a scratch file reused per frame, and each frame's
     * bitmaps are recycled before moving on, so memory stays flat regardless of
     * frame count.
     */
    private suspend fun buildBundles(
        record: SessionRecord,
        sessionDir: File,
        refFile: File,
        rawDeformedDir: File,
        stagingDir: File,
    ): BundleCounts = withContext(Dispatchers.Default) {
        val reportsDir = File(stagingDir, "reports").apply { mkdirs() }
        val processedDir = File(stagingDir, "processed").apply { mkdirs() }
        var reports = 0
        var processed = 0
        if (record.imgW <= 0 || record.imgH <= 0) {
            Timber.e("Bad image dimensions for %s — skipping reports", record.id)
            return@withContext BundleCounts(0, 0)
        }

        // The reference is the SAME image in every frame's report — decode and
        // scale it once for the whole session, not once per frame. On a long
        // analysis the repeated full-resolution decodes used to be the single
        // largest CPU cost of staging after the PDF rendering itself. Falls back
        // to a deformed frame if the reference won't decode, so a bad reference
        // does not take the whole session's reports and heatmaps down with it.
        val originalBaseImg = decodeBaseImage(refFile, rawDeformedDir, record.defNames.firstOrNull())
        if (originalBaseImg == null) {
            Timber.e("No decodable base image (reference %s) — skipping reports", refFile.absolutePath)
            return@withContext BundleCounts(0, 0)
        }
        val baseImg = Bitmap.createScaledBitmap(originalBaseImg, record.imgW, record.imgH, true)
        if (baseImg !== originalBaseImg) originalBaseImg.recycle()

        val scratch = File(applicationContext.cacheDir, "upload_${record.id}_frame.pdf")
        val ctx = RenderContext(record, baseImg, scratch)
        try {
            record.defNames.forEachIndexed { index, defName ->
                val datFile = File(sessionDir, String.format(Locale.US, "frame_%04d.dat", index))
                if (!datFile.exists()) return@forEachIndexed
                val data = DicResult.decodeDatBytes(datFile.readBytes()) ?: return@forEachIndexed

                val frameName = if (record.isSweep) {
                    record.sweepLabels.getOrElse(index) { "Combination_${index + 1}" }
                        .replace('/', '-').replace('\\', '-')
                } else {
                    "Frame_${index + 1}"
                }
                val defFile = File(rawDeformedDir, defName)

                // One processed/<frame>/ subfolder per combination, so its five
                // field maps stay together instead of all frames' maps landing
                // flat in processed/.
                val frameDir = File(processedDir, frameName)
                frameDir.mkdirs()
                val ok = renderFrame(ctx, data, defFile, frameName, index) { fields ->
                    fields.forEach { field ->
                        File(frameDir, "${field.fieldKey}.png").outputStream().buffered().use { out ->
                            field.bakedHeatmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                        }
                        processed++
                    }
                }
                if (!ok || scratch.length() == 0L) {
                    Timber.w("Report generation failed for %s", frameName)
                    return@forEachIndexed
                }
                scratch.copyTo(File(reportsDir, "Master_Report_$frameName.pdf"), overwrite = true)
                reports++
            }
        } finally {
            scratch.delete()
            baseImg.recycle()
        }
        Timber.i("Staged %d frame reports and %d processed images", reports, processed)
        BundleCounts(reports, processed)
    }

    /** Per-session state shared by every frame's report render. */
    private class RenderContext(
        val record: SessionRecord,
        /** Reference image, already scaled to engine dimensions. NOT owned by renderFrame. */
        val baseImg: Bitmap,
        /** Scratch PDF file, reused per frame. */
        val scratch: File,
    )

    /**
     * Build one frame's report: writes the classic single-frame PDF to
     * [RenderContext.scratch] and hands the freshly baked per-field heatmaps to
     * [onFieldHeatmaps] before they are recycled. The reference bitmap comes
     * pre-scaled from the context and is shared across frames — never recycled
     * here.
     */
    @Suppress("LongParameterList") // per-frame render inputs plus the heatmap callback
    private suspend fun renderFrame(
        ctx: RenderContext,
        data: FloatArray,
        defFile: File,
        frameName: String,
        frameIndex: Int,
        onFieldHeatmaps: (List<FieldResult>) -> Unit,
    ): Boolean = withContext(Dispatchers.Default) {
        val record = ctx.record

        // The deformed original is only the cover image; fall back to the
        // reference rather than losing the whole report over it.
        val originalDefImg = BitmapFactory.decodeFile(defFile.absolutePath)
        val defImg = if (originalDefImg != null) {
            Bitmap.createScaledBitmap(originalDefImg, record.imgW, record.imgH, true)
        } else {
            ctx.baseImg
        }

        val statsArray = FloatArray(ENGINE_STATS_SIZE) { record.engineStats.getOrElse(it) { 0f } }
        val frameSubset = record.sweepSubsets.getOrElse(frameIndex) { record.subset }
        val frameStep = record.sweepSteps.getOrElse(frameIndex) { record.step }
        val frameWindow = record.sweepStrainWindows.getOrElse(frameIndex) { record.strainWindow }
        val reportData = ReportBuilder.buildReport(
            ReportBuilder.ReportBuildParams(
                data = data,
                baseImg = ctx.baseImg,
                defImgForCover = defImg,
                imgW = record.imgW,
                imgH = record.imgH,
                step = frameStep,
                sessionId = record.id,
                specimenName = record.refName,
                analysisDate = ReportBuilder.currentAnalysisDate(),
                subsetSize = frameSubset,
                strainWindow = frameWindow,
                strainMethod = record.strainMethod.ifBlank { "VSG" },
                roiData = RoiData(record.roiX, record.roiY, record.roiW, record.roiH),
                engineStats = EngineStats.fromArray(statsArray),
                referenceImageName = "Baseline",
                deformedImageName = frameName,
                drawMinMarker = false,
            ),
        )

        var ok = true
        try {
            ctx.scratch.outputStream().use { stream ->
                PdfReportGenerator.generate(reportData, stream).collect { progress ->
                    if (progress is PdfReportGenerator.Progress.Error) {
                        Timber.e(progress.ex, "PDF generation failed for %s", frameName)
                        ok = false
                    }
                }
            }
            onFieldHeatmaps(reportData.fieldResults)
        } finally {
            reportData.fieldResults.forEach { it.bakedHeatmap.recycle() }
            reportData.znssdHeatmap.recycle()
            if (defImg !== ctx.baseImg && defImg !== originalDefImg) defImg.recycle()
            if (originalDefImg !== null && originalDefImg !== defImg) originalDefImg.recycle()
        }
        ok
    }

    private companion object {
        const val ENGINE_STATS_SIZE = 16
        const val PNG_QUALITY = 100

        /** Files uploaded concurrently. Keeps the link busy without thrashing. */
        const val UPLOAD_CONCURRENCY = 4

        /** Extensions that are already compressed — stored, not re-deflated, in Session.zip. */
        val NO_RECOMPRESS = setOf("jpg", "jpeg", "png", "pdf", "webp", "zip")
    }
}

// File-level helpers — kept off the worker class so they don't count against
// its function budget; they touch no worker state.

/** The base image for a session's reports: the reference, or a deformed frame if the reference won't decode. */
private fun decodeBaseImage(refFile: File, rawDeformedDir: File, defName: String?): Bitmap? =
    BitmapFactory.decodeFile(refFile.absolutePath)
        ?: defName?.let { BitmapFactory.decodeFile(File(rawDeformedDir, it).absolutePath) }

private fun deviceJson(context: Context): JSONObject = JSONObject()
    .put("id", DeviceKeyManager(context).getDeviceId())
    .put("manufacturer", Build.MANUFACTURER)
    .put("model", Build.MODEL)
    .put("os", "Android ${Build.VERSION.RELEASE}")
    .put("sdkInt", Build.VERSION.SDK_INT)

private fun engineJson(record: SessionRecord): JSONObject {
    val engine = JSONObject()
        .put("subset", record.subset)
        .put("step", record.step)
        .put("strainWindow", record.strainWindow)
        .put("strainMethod", record.strainMethod)
        .put("use6x6", record.use6x6)
        .put("imageWidth", record.imgW)
        .put("imageHeight", record.imgH)
        .put(
            "roi",
            JSONObject()
                .put("x", record.roiX).put("y", record.roiY)
                .put("w", record.roiW).put("h", record.roiH),
        )
        .put("stats", JSONArray(record.engineStats))
    if (record.isSweep) {
        engine.put(
            "sweep",
            JSONObject()
                .put("lineCutHorizontal", record.lineCutHorizontal)
                .put("subsets", JSONArray(record.sweepSubsets))
                .put("steps", JSONArray(record.sweepSteps))
                .put("strainWindows", JSONArray(record.sweepStrainWindows))
                .put("labels", JSONArray(record.sweepLabels))
                .put(
                    "skipped",
                    JSONObject()
                        .put("subsets", JSONArray(record.sweepSkipSubsets))
                        .put("steps", JSONArray(record.sweepSkipSteps))
                        .put("strainWindows", JSONArray(record.sweepSkipStrainWindows)),
                ),
        )
    }
    return engine
}
