package com.indicvision.semper.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Erases a cloud backup, after a delay long enough for the user to undo it.
 *
 * The delay is the safety net: a confirmed deletion sits here for
 * [UNDO_WINDOW_SECONDS] and reaches the backend only if nobody cancels it, so
 * a mis-tapped bin followed by a reflexive confirm is still recoverable.
 *
 * It runs in WorkManager rather than a screen scope for the same reason
 * [DicRestoreWorker] does: the user already confirmed, so leaving the settings
 * page — or the app — must not quietly abandon the request, and a delete that
 * fails offline should retry rather than be lost.
 */
class BackupDeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cloudSessionId = inputData.getString(KEY_CLOUD_SESSION_ID) ?: return Result.failure()
        val localSessionId = inputData.getString(KEY_LOCAL_SESSION_ID).orEmpty()
        val alsoLocal = inputData.getBoolean(KEY_ALSO_LOCAL, false)

        val ok = if (alsoLocal) {
            CloudSync.eraseEverywhere(applicationContext, localSessionId) ==
                CloudSync.EraseResult.ERASED_EVERYWHERE
        } else {
            CloudSync.eraseCloudBackup(applicationContext, cloudSessionId, localSessionId)
        }

        // The list is built from the backend's answer; drop the cached copy so
        // the deleted backup does not reappear until the cache expires.

        return if (ok) {
            Timber.i("Deleted cloud backup %s", cloudSessionId)
            Result.success()
        } else {
            // Nothing was deleted (offline, or no usable token) — try again.
            Timber.w("Cloud backup delete for %s did not complete; retrying", cloudSessionId)
            Result.retry()
        }
    }

    companion object {
        const val KEY_CLOUD_SESSION_ID = "cloud_session_id"
        const val KEY_LOCAL_SESSION_ID = "local_session_id"
        const val KEY_ALSO_LOCAL = "also_local"

        /** How long a confirmed delete stays cancellable before it is sent. */
        const val UNDO_WINDOW_SECONDS = 5L

        private const val BACKOFF_SECONDS = 30L

        fun workName(cloudSessionId: String): String = "delete-backup-$cloudSessionId"

        /**
         * Schedules the deletion. Cancel with [cancel] inside the undo window to
         * call it off; after that the request is on its way.
         */
        fun enqueue(
            context: Context,
            cloudSessionId: String,
            localSessionId: String,
            alsoLocal: Boolean,
        ) {
            val work = OneTimeWorkRequestBuilder<BackupDeleteWorker>()
                .setInitialDelay(UNDO_WINDOW_SECONDS, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_CLOUD_SESSION_ID to cloudSessionId,
                        KEY_LOCAL_SESSION_ID to localSessionId,
                        KEY_ALSO_LOCAL to alsoLocal,
                    ),
                )
                .addTag("delete-backup")
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(cloudSessionId),
                ExistingWorkPolicy.REPLACE,
                work,
            )
        }

        fun cancel(context: Context, cloudSessionId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(cloudSessionId))
        }
    }
}
