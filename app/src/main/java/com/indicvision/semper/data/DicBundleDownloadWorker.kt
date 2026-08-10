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
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "no_dest"))

        var staged: File? = null
        var releaseGrant = true
        try {
            publishProgress(done = 0L, total = 0L)
            SemperAnalytics.event(
                applicationContext,
                SemperAnalytics.EXPORT_STARTED,
                mapOf("kind" to "bundle_download"),
            )
            staged = downloadOrPack(cloudSessionId, displayName, localSessionId)
            if (!staged.exists() || staged.length() <= 0L) {
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "empty"),
                )
                deleteDestDocument(destUri)
                return@withContext Result.failure(workDataOf(KEY_ERROR to "empty"))
            }
            if (!copyToDest(staged, destUri)) {
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "write"),
                )
                deleteDestDocument(destUri)
                return@withContext Result.failure(workDataOf(KEY_ERROR to "write"))
            }
            SemperAnalytics.event(
                applicationContext,
                SemperAnalytics.EXPORT_COMPLETED,
                mapOf("kind" to "bundle_download"),
            )
            Result.success(workDataOf(CloudRestore.KEY_CLOUD_SESSION_ID to cloudSessionId))
        } catch (e: CancellationException) {
            deleteDestDocument(destUri)
            throw e
        } catch (e: IndicApi.ApiException) {
            if (e.code == 404 || e.code == 403) {
                Timber.e(e, "Bundle download of %s rejected — giving up", cloudSessionId)
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "rejected"),
                )
                deleteDestDocument(destUri)
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "rejected")))
            } else {
                Timber.w(e, "Bundle download of %s failed; will retry", cloudSessionId)
                releaseGrant = false
                Result.retry()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            if (RestoreDownloadOutcomes.isTerminalCorruptFailure(e)) {
                Timber.e(e, "Bundle download of %s corrupt — giving up", cloudSessionId)
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "corrupt"),
                )
                deleteDestDocument(destUri)
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
            } else {
                Timber.w(e, "Bundle download of %s failed; will retry", cloudSessionId)
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
                Timber.i(e, "Cloud zip unavailable; packed local session %s", localSessionId)
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
        }.onFailure { Timber.e(it, "Write Session.zip to %s failed", destUri) }
            .getOrDefault(false)

    private fun deleteDestDocument(destUri: Uri) {
        runCatching {
            DocumentsContract.deleteDocument(applicationContext.contentResolver, destUri)
        }.onFailure { Timber.w(it, "Could not delete empty dest %s", destUri) }
    }

    private fun releaseDestGrant(destUri: Uri) {
        runCatching {
            applicationContext.contentResolver.releasePersistableUriPermission(
                destUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not release URI grant %s", destUri) }
    }

    private suspend fun publishProgress(done: Long, total: Long) {
        val percent = if (total > 0L) {
            ((done.coerceAtLeast(0L) * 100L) / total).toInt().coerceIn(0, 100)
        } else {
            0
        }
        setProgress(
            workDataOf(
                KEY_DONE to done.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                KEY_TOTAL to total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                DicKeys.UPLOAD_PHASE to PHASE_DOWNLOAD,
                DicKeys.UPLOAD_PERCENT to percent,
            ),
        )
    }

    companion object {
        const val KEY_DISPLAY_NAME = "DISPLAY_NAME"
        const val KEY_LOCAL_SESSION_ID = "LOCAL_SESSION_ID"
        const val KEY_DEST_URI = "DEST_URI"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOAD = "download"
    }
}
