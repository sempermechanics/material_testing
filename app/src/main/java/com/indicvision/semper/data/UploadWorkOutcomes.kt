package com.indicvision.semper.data

import androidx.work.ListenableWorker.Result
import com.indicvision.semper.data.net.ApiErrors
import com.indicvision.semper.data.net.HttpStatus
import com.indicvision.semper.util.Digests
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Pure decision helpers for [DicUploadWorker] HTTP / resume outcomes.
 * Kept free of Android Context so unit tests can pin quota / fail / retry seams
 * without spinning WorkManager.
 */
internal object UploadWorkOutcomes {

    /** Hex length of a SHA-256 digest (Session.zip.sha256 sidecar). */
    const val SHA256_HEX_LEN = 64

    /** Map a backend [IndicApi]-style HTTP status to a WorkManager result. */
    fun fromHttpCode(code: Int): Result = when (code) {
        // Quota full / payload too large — retrying will not help.
        HttpStatus.CONFLICT, HttpStatus.PAYLOAD_TOO_LARGE -> Result.failure()
        // Stale resumable session — rebuild on the next attempt.
        HttpStatus.BAD_REQUEST -> Result.retry()
        // Transient or unknown — keep staging and retry.
        else -> Result.retry()
    }

    /** Whether HTTP [code] and [body] mean the account analysis quota is full. */
    fun isQuotaExhausted(code: Int, body: String? = null): Boolean =
        code == HttpStatus.CONFLICT &&
            body != null &&
            ApiErrors.hasCode(body, ApiErrors.SESSION_QUOTA_EXCEEDED)

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
    enum class ResumeKind { CONTINUE, DONE, REBUILD, WAIT, PROVISION_FAILED }

    fun classifyResume(
        sessionStatus: String,
        pendingCount: Int,
        allPendingMatchArtifacts: Boolean,
    ): ResumeKind = when {
        sessionStatus == STATUS_COMPLETED -> ResumeKind.DONE
        // Drive/Cloud Tasks failed to open upload targets — do not spin create/delete.
        sessionStatus == STATUS_PROVISION_FAILED -> ResumeKind.PROVISION_FAILED
        // Still being provisioned: poll, do not rebuild.
        sessionStatus == STATUS_PROVISIONING -> ResumeKind.WAIT
        !allPendingMatchArtifacts -> ResumeKind.REBUILD
        pendingCount == 0 -> ResumeKind.REBUILD
        else -> ResumeKind.CONTINUE
    }

    /**
     * CSV + at least one PDF report + at least one processed heatmap/GIF.
     * A prepare pass that only logged "skipping reports" must not count as done —
     * otherwise [stagingReusable] freezes an incomplete Session.zip forever.
     */
    fun bundleArtifactsReady(stagingDir: File): Boolean {
        val csv = File(stagingDir, "analysis_data.csv")
        val csvOk = csv.isFile && csv.length() > 0L
        val reports = File(stagingDir, "reports")
        val hasPdf = reports.listFiles()?.any {
            it.isFile && it.name.endsWith(".pdf", ignoreCase = true)
        } == true
        val processed = File(stagingDir, "processed")
        val hasProcessed = processed.isDirectory &&
            processed.walkTopDown().any { it.isFile }
        return csvOk && hasPdf && hasProcessed
    }

    /**
     * Finished prepare output that must survive provision / Rebuild retries.
     * Incomplete dirs (killed mid-prepare, or report bake that produced nothing)
     * must not be treated as done.
     *
     * Also requires a **verified** Session.zip: matching `.sha256` sidecar and a
     * readable central directory. A kill mid-[DicUploadWorker.buildSessionBundle]
     * leaves a truncated file that still starts with `PK` and has length > 0 —
     * hashing that truncate and uploading it produced Drive objects that restore
     * as `ZipException: invalid distance too far back` while size/sha256 "matched".
     */
    fun stagingReusable(stagingDir: File): Boolean {
        val done = File(stagingDir, ".bundles_done")
        val zip = File(stagingDir, "Session.zip")
        val sidecar = File(stagingDir, "Session.zip.sha256")
        return done.isFile &&
            bundleArtifactsReady(stagingDir) &&
            verifiedBundleSha256(zip, sidecar) != null
    }

    /**
     * Return the sidecar hex when [zip] matches it and [ZipFile] can open the
     * archive; otherwise null (caller must rebuild).
     */
    fun verifiedBundleSha256(zip: File, sidecar: File): String? {
        val expected = sidecar.takeIf { it.isFile }?.readText()?.trim()?.lowercase()
            ?.takeIf { it.length == SHA256_HEX_LEN }
        val hashOk = expected != null &&
            zip.isFile &&
            zip.length() > 0L &&
            Digests.sha256Hex(zip) == expected
        val readable = hashOk &&
            try {
                ZipFile(zip).use { it.size() > 0 }
            } catch (_: ZipException) {
                false
            } catch (_: IOException) {
                false
            }
        return expected.takeIf { readable }
    }
}
