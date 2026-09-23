// Bundle download worker: literal retry/backoff and percent math read clearest inline.
@file:Suppress("MagicNumber", "LongMethod", "ReturnCount", "ThrowsCount", "CyclomaticComplexMethod")

package com.indicvision.semper.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.indicvision.semper.DicKeys
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.net.HttpStatus
import com.indicvision.semper.data.net.IndicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Downloads a cloud Session.zip (or packs a local session) into a user-chosen
 * SAF document URI.
 *
 * Runs in WorkManager rather than an Activity lifecycle scope: leaving
 * Analyses data management must not cancel mid-download. The destination URI
 * is picked **before** enqueue so this worker only writes to that location —
 * no Save/Share sheet afterward.
 */
class DicBundleDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.downloadForeground(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cloudSessionId = inputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
            ?: return@withContext Result.failure()
        val displayName = inputData.getString(KEY_DISPLAY_NAME).orEmpty()
        val localSessionId = inputData.getString(KEY_LOCAL_SESSION_ID).orEmpty()
        val destUri = inputData.getString(KEY_DEST_URI)?.let(Uri::parse)
            ?: return@withContext Result.failure(workDataOf(DicKeys.DOWNLOAD_ERROR to "no_dest"))

        var staged: File? = null
        var releaseGrant = true
        try {
            publishProgress(done = 0L, total = 0L)
            SemperAnalytics.event(
                applicationContext,
                SemperAnalytics.EXPORT_STARTED,
                mapOf("kind" to "bundle_download"),
            )
            TransferLog.phase(TransferLog.PhaseFields("bundle_download", "start"))
            staged = downloadOrPack(cloudSessionId, displayName, localSessionId)
            if (!staged.exists() || staged.length() <= 0L) {
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "empty"),
                )
                deleteDestDocument(destUri)
                return@withContext Result.failure(workDataOf(DicKeys.DOWNLOAD_ERROR to "empty"))
            }
            if (!copyToDest(staged, destUri)) {
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "write"),
                )
                deleteDestDocument(destUri)
                return@withContext Result.failure(workDataOf(DicKeys.DOWNLOAD_ERROR to "write"))
            }
            SemperAnalytics.event(
                applicationContext,
                SemperAnalytics.EXPORT_COMPLETED,
                mapOf("kind" to "bundle_download"),
            )
            TransferLog.phase(TransferLog.PhaseFields("bundle_download", "complete"))
            Result.success(workDataOf(CloudRestore.KEY_CLOUD_SESSION_ID to cloudSessionId))
        } catch (e: CancellationException) {
            deleteDestDocument(destUri)
            throw e
        } catch (e: IndicApi.ApiException) {
            if (e.code == HttpStatus.NOT_FOUND || e.code == HttpStatus.FORBIDDEN) {
                Timber.e(e, "Bundle download rejected — giving up")
                TransferLog.phase(
                    TransferLog.PhaseFields(
                        phase = "bundle_download",
                        outcome = "rejected",
                        httpStatus = e.code,
                        requestId = e.requestId,
                    ),
                )
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "rejected"),
                )
                deleteDestDocument(destUri)
                // The body, not the message: LicenseErrors parses the `detail` code
                // out of it to say *why* (demo mode) instead of "check your connection".
                Result.failure(workDataOf(DicKeys.DOWNLOAD_ERROR to e.body))
            } else {
                Timber.w(e, "Bundle download failed; will retry")
                TransferLog.phase(
                    TransferLog.PhaseFields(
                        phase = "bundle_download",
                        outcome = "retry",
                        httpStatus = e.code,
                        requestId = e.requestId,
                    ),
                )
                releaseGrant = false
                Result.retry()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (RestoreDownloadOutcomes.isTerminalCorruptFailure(e)) {
                Timber.e(e, "Bundle download corrupt — giving up")
                TransferLog.phase(TransferLog.PhaseFields("bundle_download", "corrupt"))
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "corrupt"),
                )
                deleteDestDocument(destUri)
                Result.failure(workDataOf(DicKeys.DOWNLOAD_ERROR to (e.message ?: e.javaClass.simpleName)))
            } else {
                Timber.w(e, "Bundle download failed; will retry")
                TransferLog.phase(TransferLog.PhaseFields("bundle_download", "retry"))
                releaseGrant = false
                Result.retry()
            }
        } finally {
            staged?.delete()
            if (releaseGrant) releaseDestGrant(destUri)
        }
    }

    /**
     * Prefer the cloud Session.zip; if that backup has no bundle (legacy) or
     * the transfer fails for a non-transient reason that local packing can
     * cover, fall back to packing the on-device session.
     */
    private suspend fun downloadOrPack(
        cloudSessionId: String,
        displayName: String,
        localSessionId: String,
    ): File {
        try {
            return CloudRestore.downloadBundleZip(
                applicationContext,
                cloudSessionId,
                displayName,
            ) { done, total -> publishProgress(done, total) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndicApi.ApiException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (RestoreDownloadOutcomes.isTerminalCorruptFailure(e)) throw e
            val packed = packLocalFallback(localSessionId)
            if (packed != null) {
                Timber.i(e, "Cloud zip unavailable; packed local session fallback")
                return packed
            }
            throw e
        }
    }

    private suspend fun packLocalFallback(localSessionId: String): File? {
        if (localSessionId.isBlank()) return null
        val record = SessionStore.get(applicationContext, localSessionId)
            ?.takeIf { it.hasLocalData() }
            ?: return null
        return SessionEverythingExporter.exportSessionZip(applicationContext, record)
    }

    private fun copyToDest(file: File, destUri: Uri): Boolean =
        runCatching {
            val copied = applicationContext.contentResolver.openOutputStream(destUri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: 0L
            copied > 0L
        }.onFailure { Timber.e(it, "Write Session.zip to destination failed") }
            .getOrDefault(false)

    private fun deleteDestDocument(destUri: Uri) {
        runCatching {
            DocumentsContract.deleteDocument(applicationContext.contentResolver, destUri)
        }.onFailure { Timber.w(it, "Could not delete empty destination document") }
    }

    private fun releaseDestGrant(destUri: Uri) {
        runCatching {
            applicationContext.contentResolver.releasePersistableUriPermission(
                destUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not release destination URI grant") }
    }

    private suspend fun publishProgress(done: Long, total: Long) {
        setProgress(DownloadProgress.data(done, total))
    }

    companion object {
        const val KEY_DISPLAY_NAME = "DISPLAY_NAME"
        const val KEY_LOCAL_SESSION_ID = "LOCAL_SESSION_ID"
        const val KEY_DEST_URI = "DEST_URI"
    }
}
