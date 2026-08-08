package com.indicvision.semper.data

import com.indicvision.semper.data.net.HttpStatus

/**
 * Classify proxied restore download failures for Range-resume vs terminal fail.
 *
 * Session.zip streams through API Gateway → Cloud Run → Drive. A gateway /
 * Cloud Run deadline kill often surfaces as HTTP 5xx with an **empty** body
 * (not FastAPI's `{"detail":…}`). Those are retryable with `Range` from the
 * bytes already on disk — same as a mid-stream `IOException`.
 */
object RestoreDownloadOutcomes {

    fun isTransientProxyFailure(code: Int): Boolean = when (code) {
        HttpStatus.INTERNAL_ERROR,
        HttpStatus.BAD_GATEWAY,
        HttpStatus.SERVICE_UNAVAILABLE,
        HttpStatus.GATEWAY_TIMEOUT,
        -> true
        else -> false
    }

    /** Whether [DriveTransfer.downloadFile] should Range-resume after this status. */
    fun shouldResumeAfterHttp(code: Int, attempt: Int, maxAttempts: Int): Boolean =
        isTransientProxyFailure(code) && attempt < maxAttempts
}
