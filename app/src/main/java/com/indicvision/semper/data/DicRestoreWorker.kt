// Restore worker: literal retry/backoff and buffer constants read clearest inline.
@file:Suppress("MagicNumber")

package com.indicvision.semper.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.indicvision.semper.DicKeys
import com.indicvision.semper.data.net.IndicApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Downloads a cloud backup and rebuilds it on this device.
 *
 * This runs in WorkManager rather than an Activity scope on purpose: a restore
 * can be hundreds of megabytes, and a `lifecycleScope` job is cancelled the
 * moment the user leaves the screen — which silently abandoned the download
 * part-way through. As a worker it survives navigation and app death, retries
 * on flaky networks, and reports progress the UI can observe if it's watching.
 */
class DicRestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        TransferNotifications.restoreForeground(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val cloudSessionId = inputData.getString(CloudRestore.KEY_CLOUD_SESSION_ID)
            ?: return@withContext Result.failure()
        val targetLocalId = inputData.getString(CloudRestore.KEY_TARGET_LOCAL_ID)
            ?: return@withContext Result.failure()

        try {
            clearPartialArtifacts(targetLocalId)
            publishProgress(targetLocalId, done = 0, total = 0)
            val localId = CloudRestore.restore(
                applicationContext,
                cloudSessionId,
                targetLocalId,
            ) { done, total ->
                publishProgress(targetLocalId, done, total)
            }
            Timber.i("Restored %s from cloud session %s", localId, cloudSessionId)
            Result.success(workDataOf(KEY_LOCAL_ID to localId))
        } catch (e: CancellationException) {
            clearPartialArtifacts(targetLocalId)
            throw e
        } catch (e: IndicApi.ApiException) {
            clearPartialArtifacts(targetLocalId)
            // 404 = the backup is gone; 403 = not ours. Retrying can't fix either.
            if (e.code == 404 || e.code == 403) {
                Timber.e(e, "Restore of %s rejected — giving up", cloudSessionId)
                Result.failure(workDataOf(KEY_ERROR to e.message))
            } else {
                Timber.w(e, "Restore of %s failed; will retry", cloudSessionId)
                Result.retry()
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            clearPartialArtifacts(targetLocalId)
            Timber.w(e, "Restore of %s failed; will retry", cloudSessionId)
            Result.retry()
        }
    }

    private suspend fun publishProgress(localId: String, done: Int, total: Int) {
        val percent = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
        setProgress(
            workDataOf(
                KEY_DONE to done,
                KEY_TOTAL to total,
                DicKeys.SESSION_LOCAL_ID to localId,
                DicKeys.UPLOAD_PHASE to PHASE_DOWNLOAD,
                DicKeys.UPLOAD_PERCENT to percent,
            ),
        )
    }

    private fun clearPartialArtifacts(localId: String) {
        runCatching { CloudRestore.clearPartialArtifacts(applicationContext, localId) }
            .onFailure { Timber.w(it, "Could not clean partial restore %s", localId) }
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_LOCAL_ID = "localId"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOAD = "download"
    }
}
