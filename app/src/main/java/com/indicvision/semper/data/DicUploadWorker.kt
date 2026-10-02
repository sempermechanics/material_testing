package com.indicvision.semper.data

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.indicvision.semper.R
import com.indicvision.semper.data.cloud.CloudSync
import com.indicvision.semper.data.cloud.TransferLog
import com.indicvision.semper.data.cloud.TransferNotifications
import com.indicvision.semper.data.cloud.TransferPhase
import com.indicvision.semper.data.cloud.UploadErrors
import com.indicvision.semper.data.cloud.UploadProgressSampler
import com.indicvision.semper.data.net.ApiErrors
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.FileCompleteRequest
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.IndicApiHttp
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.data.session.SessionRecord
import com.indicvision.semper.data.session.SessionStore
import com.indicvision.semper.data.session.SessionZip
import com.indicvision.semper.data.session.StorageBudget
import com.indicvision.semper.diagnostics.SemperAnalytics
import com.indicvision.semper.navigation.AppIntents
import com.indicvision.semper.navigation.DicKeys
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Whether one of this app's activities is on screen. Only then may a worker
 * start an activity: Android 10+ blocks background activity starts. Read from
 * the process's own importance — `lifecycle-process` is not on the compile
 * classpath. A foreground service alone (this worker) ranks below FOREGROUND.
 */
private fun appInForeground(): Boolean {
    val info = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(info)
    return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

/**
 * How [DicUploadWorker] reaches the backend and a token. WorkManager builds the
 * worker, so these cannot be constructor parameters (ADR-002); tests swap them
 * for `FakeCloudApi` / `FakeTokens` and put them back.
 */
@VisibleForTesting
internal object DicUploadSeams {
    var api: (Context) -> CloudApi = { IndicApi.get(it) }
    var tokens: TokenSource = TokenProvider
    var inForeground: () -> Boolean = ::appInForeground
}

/** The upload's structured log lines ([TransferLog], phase `upload`). */
internal object UploadLog {

    fun phase(
        outcome: String,
        count: Int? = null,
        stage: String? = null,
        requestId: String? = null,
        httpStatus: Int? = null,
    ) {
        TransferLog.phase(
            TransferLog.PhaseFields(
                phase = "upload",
                outcome = outcome,
                count = count,
                stage = stage,
                requestId = requestId,
                httpStatus = httpStatus,
            ),
        )
    }

    /** WARN breadcrumb + structured phase line for every WorkManager retry. */
    fun retry(reason: String, requestId: String? = null): ListenableWorker.Result {
        Timber.w("Upload RETRY — %s", IndicApiHttp.withRef(reason, requestId))
        phase("retry", requestId = requestId)
        return ListenableWorker.Result.retry()
    }
}

/** Mark backup [localId] FAILED, and count it under [analyticsReason] when there is one. */
internal fun markBackupFailed(context: Context, localId: String, analyticsReason: String?) {
    SessionStore.setSyncState(context, localId, SessionRecord.SyncState.FAILED)
    if (analyticsReason != null) {
        SemperAnalytics.event(context, SemperAnalytics.CLOUD_UPLOAD_FAILED, mapOf("reason" to analyticsReason))
    }
}

/** What one upload run works with: the backend, its token, and the analysis. */
internal class UploadRun(
    val context: Context,
    val api: CloudApi,
    val tokens: TokenSource,
    val idToken: String,
    val record: SessionRecord,
) {
    val localId: String get() = record.id

    /** The token read at the start can have expired during a long upload; a call made late reads it again. */
    suspend fun freshToken(): String = tokens.usableIdToken() ?: idToken

    /** The pointer now: [record] was read at the start, and createSession may have written one since. */
    fun currentCloudId(): String =
        SessionStore.get(context, localId)?.cloudSessionId.orEmpty().ifBlank { record.cloudSessionId }

    /** Terminal failure carrying a reason the UI can show; [requestId] joins it to the backend's access line. */
    fun failure(reason: String, requestId: String? = null): ListenableWorker.Result = ListenableWorker.Result.failure(
        workDataOf(
            DicKeys.UPLOAD_FAIL_REASON to IndicApiHttp.withRef(reason, requestId),
            DicKeys.SESSION_LOCAL_ID to localId,
        ),
    )
}

/**
 * Offline-first cloud sync against the Semper GCP backend — **one backend session
 * per analysis** (not per frame).
 *
 * Enqueued once per analysis with a network constraint. It reads the whole
 * analysis from [SessionStore] (so only the session id travels through
 * WorkManager's small Data), then runs four steps:
 *  1. stage every artifact ([UploadStaging]),
 *  2. ensure the cloud session ([UploadSessionPlanner]): POST /v1/sessions with
 *     the full manifest (creates the Drive folder tree and one resumable upload
 *     URI per file, device-signed), or resume the one a prior run created,
 *  3. upload the files, streaming each **directly to Google Drive** in resumable
 *     chunks — bytes never pass through the backend — and POST
 *     /v1/files/{id}/complete to record each Drive pointer,
 *  4. complete: record the sync and drop the staging.
 *
 * Layout per analysis (3 files — artifacts are bundled rather than uploaded
 * individually to keep Firestore's per-file costs flat):
 * ```
 * session/<sid>/metadata.json   device, time, engine params, frame list
 *               Session.zip     raw/…  (reference + every deformed original),
 *                               dat/frame_%04d.dat ← enables full restore
 *               Extras.zip      csv/analysis_data.csv  (one combined file),
 *                               reports/Master_Report_<frame>.pdf,
 *                               processed/<frame>/<field>.png
 * ```
 * The split is what keeps a restore cheap without losing anything a local run would
 * have produced: `Session.zip` holds every original image plus the engine results, so
 * a restored session is fully usable — including on-device re-export — with one
 * download. `Extras.zip` holds only the **derived** deliverables (regenerated on
 * export, so a restore never needs them). "Save to Files" fetches both and merges
 * them into one archive. See [SessionZip.isRestoreEssential].
 */
class DicUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.uploadForeground(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Expedited work can fall back to a normal request when the OS is out of
        // expedited quota. Without an explicit foreground promotion the worker is
        // then eligible to be stopped when the app backgrounds mid-prepare —
        // which looked like "preparing finished → pending → preparing again".
        setForeground(getForegroundInfo())

        val api = DicUploadSeams.api(applicationContext)
        val tokens = DicUploadSeams.tokens
        if (!api.enabled) {
            // Succeeding quietly left the row "upload pending" for good.
            CloudSync.settleWithoutBackend(applicationContext)
            return@withContext Result.success()
        }
        val idToken = tokens.usableIdToken() ?: return@withContext UploadLog.retry("no usable Firebase ID token")
        val localId = inputData.getString(DicKeys.SESSION_LOCAL_ID) ?: return@withContext Result.failure()
        val record = SessionStore.get(applicationContext, localId) ?: return@withContext Result.failure()
        backUp(UploadRun(applicationContext, api, tokens, idToken, record))
    }

    /** Stage, ensure the cloud session, upload the files, complete; any failure ends as [UploadFailures] says. */
    private suspend fun backUp(run: UploadRun): Result = coroutineScope {
        val staging = UploadStaging(applicationContext, run.record)
        val reuse = staging.prepare()
        // Live progress for the Home row (UploadProgressSampler): the steps feed
        // these counters and the sampler publishes only changes.
        val progress = UploadProgressSampler(
            run.localId,
            initialPhase = if (reuse) TransferPhase.UPLOAD.wire else TransferPhase.PREPARE.wire,
            initialTotal = if (reuse) 1L else run.record.defNames.size.toLong().coerceAtLeast(1L),
        )
        val sampler = progress.launchIn(this, UploadTuning.PROGRESS_SAMPLE_MS) { setProgress(it) }
        val stagingDir = staging.staging.dir
        val failures = UploadFailures(run, stagingDir)
        try {
            when (val staged = staging.stage(reuse, progress)) {
                is StagingResult.Ready -> upload(run, staged.files, progress, stagingDir)
                is StagingResult.Retry -> UploadLog.retry(staged.reason)
                StagingResult.InputsGone ->
                    run.failure(applicationContext.getString(R.string.cloud_backup_failed_missing_files))
            }
        } catch (e: IndicApi.DeviceNotActiveException) {
            failures.deviceNotActive(e)
        } catch (e: IndicApi.DeviceConflictException) {
            failures.deviceConflict(e)
        } catch (e: IndicApi.ApiException) {
            failures.refused(e)
        } catch (e: IndicApi.UploadLinkExpiredException) {
            failures.linkExpired(e)
        } catch (e: OutOfMemoryError) {
            failures.outOfMemory(e)
        } catch (e: CancellationException) {
            // The worker was stopped (constraints lost, cancelled, quota). Not a
            // failure: rethrow so WorkManager reschedules it, instead of logging
            // every stop as an error (a Crashlytics non-fatal) and asking for a retry.
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            failures.unexpected(e)
        } finally {
            // Stop the progress sampler so this coroutine can complete (a live
            // child would otherwise keep the worker from returning).
            sampler.cancel()
        }
    }

    /** Ensure the cloud session for [files], upload into it, and complete. */
    private suspend fun upload(
        run: UploadRun,
        files: List<UploadArtifact>,
        progress: UploadProgressSampler,
        stagingDir: File,
    ): Result {
        // Bundling is done — leave the "preparing" badge before we wait on
        // Cloud Tasks / Drive so a provision retry does not look like another
        // full prepare cycle.
        progress.begin(TransferPhase.UPLOAD, 1)
        Timber.i("Uploading %s: %d files (%s)", run.localId, files.size, files.groupingBy { it.role }.eachCount())
        UploadLog.phase("start", count = files.size, stage = "upload")

        val planner = UploadSessionPlanner(applicationContext, run.api, run.idToken, run.localId)
        return when (val step = planner.ensureSession(run.record, files)) {
            is SessionStep.Upload -> {
                uploadFiles(run, step.plan, progress)
                complete(run, step.plan, stagingDir)
            }
            SessionStep.Synced -> {
                Timber.w("Cloud session already complete")
                SessionStore.markSynced(applicationContext, run.localId)
                stagingDir.deleteRecursively()
                UploadErrors.clearIntegrityRebuilds(File(run.record.sessionDir))
                Result.success()
            }
            is SessionStep.Retry -> step.result
            is SessionStep.Fail -> run.failure(step.reason)
        }
    }

    /**
     * Upload straight to Drive, several files at a time, each recorded with
     * `:complete` as it lands. Sequential uploads left most of the link idle:
     * every chunk waits a full round-trip before the next starts, and a session
     * is mostly many smallish files. A few in flight keeps the pipe full.
     */
    private suspend fun uploadFiles(run: UploadRun, plan: UploadPlan, progress: UploadProgressSampler) {
        // Switch the Home progress to byte-based upload tracking.
        progress.begin(TransferPhase.UPLOAD, plan.work.sumOf { it.file.length() })
        coroutineScope {
            val concurrency = UploadTuning.uploadConcurrency(applicationContext)
            val gate = Semaphore(concurrency)
            plan.work.map { job ->
                async {
                    gate.withPermit { uploadFile(run, plan.sessionId, job, concurrency, progress) }
                }
            }.awaitAll()
        }
    }

    private suspend fun uploadFile(
        run: UploadRun,
        sessionId: String,
        job: UploadJob,
        concurrency: Int,
        progress: UploadProgressSampler,
    ) {
        val chunkBytes = uploadChunkBytes(applicationContext, job.chunkSize, concurrency)
        Timber.d("Uploading %s (%d bytes)…", job.name, job.file.length())
        val (driveId, md5) = run.api.uploadResumable(
            job.uploadUrl,
            job.file,
            chunkBytes,
        ) { n -> progress.done.addAndGet(n) }
        // Re-read the token: a long upload can outlive it.
        run.api.completeFile(
            run.freshToken(),
            job.fileId,
            FileCompleteRequest(sessionId, driveId, job.file.length(), md5),
        )
    }

    /** Every file landed: record the sync, drop the staging, and give storage back. */
    private fun complete(run: UploadRun, plan: UploadPlan, stagingDir: File): Result {
        SessionStore.markSynced(applicationContext, run.localId)
        Timber.i("Upload complete for %s (%d files, session %s)", run.localId, plan.work.size, plan.sessionId)
        UploadLog.phase("complete", count = plan.work.size)
        stagingDir.deleteRecursively() // done — staged files no longer needed
        UploadErrors.clearIntegrityRebuilds(File(run.record.sessionDir))
        // A session only becomes droppable once it is backed up, so this is
        // the moment an over-budget phone can actually get space back.
        StorageBudget.enforce(applicationContext)
        SemperAnalytics.event(applicationContext, SemperAnalytics.CLOUD_UPLOAD_SUCCEEDED)
        return Result.success()
    }
}

/**
 * What a run that threw does to the row, the cloud session and the staging
 * ([stagingDir]), and the result it ends with.
 */
internal class UploadFailures(private val run: UploadRun, private val stagingDir: File) {

    private val context: Context get() = run.context
    private val sessionDir: File get() = File(run.record.sessionDir)

    /**
     * The server has no ACTIVE device record for us (revoked/reset) while our
     * local "registered" flag said otherwise. Re-register and retry instead of
     * stalling forever. Keep staging for the retry.
     */
    suspend fun deviceNotActive(e: IndicApi.DeviceNotActiveException): ListenableWorker.Result {
        TokenStore.setDeviceRegistered(context, false)
        suspendRunCatching { run.api.registerDevice(run.idToken) }
            .onSuccess { TokenStore.setDeviceRegistered(context, true) }
            .onFailure { Timber.e(it, "Re-registration failed") }
        return UploadLog.retry("device not active — re-registered, retry upload", e.requestId)
    }

    fun deviceConflict(e: IndicApi.DeviceConflictException): ListenableWorker.Result {
        Timber.e("This account is bound to a different device — cannot upload")
        stagingDir.deleteRecursively()
        markBackupFailed(context, run.localId, analyticsReason = ApiErrors.DEVICE_CONFLICT)
        return run.failure(context.getString(R.string.cloud_backup_failed_device), e.requestId)
    }

    /** An HTTP error that ended the run, by what it means for the backup ([UploadErrors.classify]). */
    suspend fun refused(e: IndicApi.ApiException): ListenableWorker.Result =
        when (UploadErrors.classify(e.code, e.body)) {
            UploadErrors.Kind.QUOTA -> quotaFull(e)
            UploadErrors.Kind.TOO_LARGE -> {
                // Too many files for one analysis — retrying won't help; tell the user.
                giveUp(e, "payload")
                run.failure(context.getString(R.string.cloud_backup_failed_too_large), e.requestId)
            }
            UploadErrors.Kind.REJECTED -> {
                // A 409 we have no handling for. It is not "too large" — say
                // only that it failed, with the ref to look it up.
                giveUp(e, "rejected")
                run.failure(context.getString(R.string.cloud_backup_failed_generic), e.requestId)
            }
            // The session can't be finished (Drive's expected size no longer
            // matches, the object or file record is gone). Erase it so it
            // doesn't orphan and eat a quota slot, and keep Session.zip so the
            // recreate is cheap.
            UploadErrors.Kind.STALE_SESSION -> {
                Timber.e("Upload %d (%s) — discarding stale session, keeping staging", e.code, e.parsedDetail)
                discardCloudSession(context, run.api, run.freshToken(), run.localId, run.currentCloudId())
                val detail = e.parsedDetail.take(UploadTuning.STALE_DETAIL_MAX_LEN)
                UploadLog.retry("HTTP ${e.code} stale session — $detail", e.requestId)
            }
            UploadErrors.Kind.INTEGRITY -> integrityMismatch(e)
            UploadErrors.Kind.TRANSIENT -> {
                // Transient — keep the staged files so the retry resumes identically.
                Timber.e("Upload HTTP %d — %s", e.code, e.parsedDetail)
                UploadLog.retry(
                    "HTTP ${e.code}: ${e.parsedDetail.take(UploadTuning.RETRY_REASON_MAX_LEN)}",
                    e.requestId,
                )
            }
        }

    /**
     * Drive no longer knows a resumable link (404/410 on its probe), and no
     * retry with that link can work. Like a stale session: erase it and
     * recreate from the same staging. Caught before the generic catch, which
     * retried it forever (it is an IOException).
     */
    suspend fun linkExpired(e: IndicApi.UploadLinkExpiredException): ListenableWorker.Result {
        Timber.e("Drive upload link expired (HTTP %d) — discarding the session, keeping staging", e.code)
        discardCloudSession(context, run.api, run.freshToken(), run.localId, run.currentCloudId())
        return UploadLog.retry("upload link expired (HTTP ${e.code}) — will recreate session")
    }

    /**
     * OOM is an Error, not an Exception, so it would otherwise escape every
     * catch and surface as an untracked WorkManager failure with no reason —
     * the badge would stay "pending" and a manual retry would just re-OOM
     * forever. Terminal with a clear reason instead. Distinct from HTTP 413
     * "too large": the session may fit the cloud quota but this device cannot
     * pack it in RAM.
     */
    fun outOfMemory(e: OutOfMemoryError): ListenableWorker.Result {
        Timber.e(e, "Upload ran out of memory bundling — failing terminally")
        stagingDir.deleteRecursively()
        markBackupFailed(context, run.localId, analyticsReason = null)
        return run.failure(context.getString(R.string.cloud_backup_failed_oom))
    }

    /**
     * Anything else: retry. Class name only: an I/O message carries the file's
     * path, and a deformed image's path is the user's own file name (→ Crashlytics).
     */
    fun unexpected(e: Exception): ListenableWorker.Result {
        Timber.e("Upload failed (%s); will retry", e.javaClass.simpleName)
        return UploadLog.retry(e.javaClass.simpleName)
    }

    /** Quota full has its own persistent "email support" screen, and no reason text. */
    private fun quotaFull(e: IndicApi.ApiException): ListenableWorker.Result {
        giveUp(e, "quota")
        TokenStore.setSessionLimitReached(context, true)
        // Android blocks a background activity start, so open it only while the
        // app is on screen; otherwise Home opens it from the gate (and can read
        // UPLOAD_FAIL_KIND).
        if (DicUploadSeams.inForeground()) {
            runCatching { context.startActivity(AppIntents.sessionLimit(context)) }
                .onFailure { Timber.w("Could not open the limit screen (%s)", it.javaClass.simpleName) }
        }
        return ListenableWorker.Result.failure(
            workDataOf(
                UploadErrors.UPLOAD_FAIL_KIND to UploadErrors.FAIL_KIND_QUOTA,
                DicKeys.SESSION_LOCAL_ID to run.localId,
            ),
        )
    }

    /**
     * Drive holds other bytes than ours. Completing again fails the same way,
     * so start over — new session, freshly staged files — a bounded number of
     * times.
     */
    private suspend fun integrityMismatch(e: IndicApi.ApiException): ListenableWorker.Result {
        Timber.e("Upload %d (%s) — Drive bytes differ from the staged file", e.code, e.parsedDetail)
        return when {
            // The session is still ours to resume, so its staging must stay as
            // declared: restaging under a live pointer would resume the old
            // session (bad object finalized) with new bytes and burn every
            // rebuild. Try the delete again later.
            !discardCloudSession(context, run.api, run.freshToken(), run.localId, run.currentCloudId()) ->
                UploadLog.retry("HTTP ${e.code} integrity — session not deleted yet", e.requestId)
            UploadErrors.recordIntegrityRebuild(sessionDir) > UploadErrors.MAX_INTEGRITY_REBUILDS -> {
                giveUp(e, "integrity")
                run.failure(context.getString(R.string.cloud_backup_failed_generic), e.requestId)
            }
            else -> {
                stagingDir.deleteRecursively()
                UploadLog.retry("HTTP ${e.code} integrity — restaging", e.requestId)
            }
        }
    }

    /**
     * Terminal: mark the row failed and drop its staging. The next attempt is a
     * fresh one, so the integrity count starts over too.
     */
    private fun giveUp(e: IndicApi.ApiException, analyticsReason: String) {
        Timber.e("Upload rejected (%d): %s", e.code, e.parsedDetail)
        stagingDir.deleteRecursively()
        UploadErrors.clearIntegrityRebuilds(sessionDir)
        markBackupFailed(context, run.localId, analyticsReason)
    }
}
