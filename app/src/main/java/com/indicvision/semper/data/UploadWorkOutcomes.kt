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

    /**
     * Resume planner outcome when comparing pending uploads to local artifacts.
     * Mirrors [DicUploadWorker.resumeSession] without the network call.
     */
    enum class ResumeKind { CONTINUE, DONE, REBUILD }

    fun classifyResume(
        sessionStatus: String,
        pendingCount: Int,
        allPendingMatchArtifacts: Boolean,
    ): ResumeKind = when {
        sessionStatus == "COMPLETED" -> ResumeKind.DONE
        !allPendingMatchArtifacts -> ResumeKind.REBUILD
        pendingCount == 0 -> ResumeKind.REBUILD
        else -> ResumeKind.CONTINUE
    }
}
