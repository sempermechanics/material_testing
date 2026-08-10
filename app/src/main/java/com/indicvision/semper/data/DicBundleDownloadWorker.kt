// Bundle download worker: literal retry/backoff and percent math read clearest inline.
@file:Suppress("MagicNumber", "LongMethod", "ReturnCount", "ThrowsCount")

package com.indicvision.semper.data

import android.content.Context
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
 * Downloads a cloud Session.zip (or packs a local session) for Save / Share.
 *
 * Runs in WorkManager rather than an Activity lifecycle scope: leaving
 * Analyses data management used to cancel mid-download. As a worker it
 * survives navigation and reports progress the Settings banner can observe.
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

        try {
            publishProgress(done = 0L, total = 0L)
            SemperAnalytics.event(
                applicationContext,
                SemperAnalytics.EXPORT_STARTED,
                mapOf("kind" to "bundle_download"),
            )
            val file = downloadOrPack(cloudSessionId, displayName, localSessionId)
            if (!file.exists() || file.length() <= 0L) {
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "empty"),
                )
                return@withContext Result.failure(workDataOf(KEY_ERROR to "empty"))
            }
            SemperAnalytics.event(
                applicationContext,
                SemperAnalytics.EXPORT_COMPLETED,
                mapOf("kind" to "bundle_download"),
            )
            Result.success(
                workDataOf(
                    KEY_ZIP_PATH to file.absolutePath,
                    CloudRestore.KEY_CLOUD_SESSION_ID to cloudSessionId,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndicApi.ApiException) {
            if (e.code == 404 || e.code == 403) {
                Timber.e(e, "Bundle download of %s rejected — giving up", cloudSessionId)
                SemperAnalytics.event(
                    applicationContext,
                    SemperAnalytics.EXPORT_FAILED,
                    mapOf("kind" to "bundle_download", "reason" to "rejected"),
                )
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "rejected")))
            } else {
                Timber.w(e, "Bundle download of %s failed; will retry", cloudSessionId)
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
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: e.javaClass.simpleName)))
            } else {
                Timber.w(e, "Bundle download of %s failed; will retry", cloudSessionId)
                Result.retry()
            }
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
        const val KEY_ZIP_PATH = "zipPath"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOAD = "download"
    }
}
