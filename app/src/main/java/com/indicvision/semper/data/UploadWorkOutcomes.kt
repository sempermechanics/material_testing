package com.indicvision.semper.data

import androidx.work.ListenableWorker.Result
import com.indicvision.semper.data.net.HttpStatus

/**
 * Pure decision helpers for [DicUploadWorker] HTTP / resume outcomes.
 * Kept free of Android Context so unit tests can pin quota / fail / retry seams
 * without spinning WorkManager.
 */
internal object UploadWorkOutcomes {

    /** Map a backend [IndicApi]-style HTTP status to a WorkManager result. */
    fun fromHttpCode(code: Int): Result = when (code) {
        // Quota full / payload too large — retrying will not help.
        HttpStatus.CONFLICT, HttpStatus.PAYLOAD_TOO_LARGE -> Result.failure()
        // Stale resumable session — rebuild on the next attempt.
        HttpStatus.BAD_REQUEST -> Result.retry()
        // Transient or unknown — keep staging and retry.
        else -> Result.retry()
    }

    /** Whether HTTP [code] means the account analysis quota is full. */
    fun isQuotaExhausted(code: Int): Boolean = code == HttpStatus.CONFLICT

    /** Whether retrying cannot help (quota or payload size). */
    fun isTerminalClientError(code: Int): Boolean =
        code == HttpStatus.CONFLICT || code == HttpStatus.PAYLOAD_TOO_LARGE

    /** Backend session states. Provisioning happens off the request path, so a
     * freshly created session has no upload targets yet. */
    const val STATUS_PROVISIONING = "PROVISIONING"
    const val STATUS_PROVISION_FAILED = "PROVISION_FAILED"
    const val STATUS_COMPLETED = "COMPLETED"

    /**
     * Resume planner outcome when comparing pending uploads to local artifacts.
     * Mirrors [DicUploadWorker.resumeSession] without the network call.
     *
     * [ResumeKind.WAIT] exists because the backend now opens Drive resumable
     * sessions in a Cloud Task rather than inside POST /v1/sessions: an empty
     * upload list means "not ready yet", not "nothing to do". Treating it as
     * REBUILD would spin, creating a fresh session on every poll.
     */
    enum class ResumeKind { CONTINUE, DONE, REBUILD, WAIT }

    fun classifyResume(
        sessionStatus: String,
        pendingCount: Int,
        allPendingMatchArtifacts: Boolean,
    ): ResumeKind = when {
        sessionStatus == STATUS_COMPLETED -> ResumeKind.DONE
        // Provisioning failed permanently — the session will never get targets.
        sessionStatus == STATUS_PROVISION_FAILED -> ResumeKind.REBUILD
        // Still being provisioned: poll, do not rebuild.
        sessionStatus == STATUS_PROVISIONING -> ResumeKind.WAIT
        !allPendingMatchArtifacts -> ResumeKind.REBUILD
        pendingCount == 0 -> ResumeKind.REBUILD
        else -> ResumeKind.CONTINUE
    }
}
