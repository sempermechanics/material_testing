package com.sempermechanics.semper.data

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.SeatHeartbeat
import com.sempermechanics.semper.data.account.SeatLease
import com.sempermechanics.semper.data.account.entitlement.EntitlementRefresh
import com.sempermechanics.semper.data.account.entitlement.EntitlementStore
import com.sempermechanics.semper.data.account.entitlement.RefreshVerdict
import com.sempermechanics.semper.data.cloud.TransferNotifications
import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.data.cloud.enqueueUnique
import com.sempermechanics.semper.data.cloud.oneTimeWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Background `/v1/config` refresh (and floating-seat re-checkout when still
 * licensed). Runs every four hours — shorter than the eight-hour lease so an
 * idle phone is not left licensed for a full TTL after a remote revoke.
 *
 * The same worker also runs as a unique one-off ([refreshNow]): `/v1/config`
 * alone, expedited, retried with backoff a bounded number of times
 * ([EntitlementRefresh.afterAttempt]). It is queued when the entitlement
 * token's `refreshAfter` has passed on start or return to the foreground
 * ([foregroundCheck], TD-210), and when the fetch after a seat
 * checkout fails (TD-212).
 *
 * Not a substitute for the in-process heartbeat ([SeatHeartbeat]): WorkManager
 * is inexact and must not be the only renew path while the app is open.
 */
class LicenseConfigWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!inputData.getBoolean(KEY_REFRESH_NOW, false)) {
            SeatLease.refreshConfigAndSeatBestEffort(applicationContext)
            return Result.success()
        }
        val fetch = SeatLease.fetchConfig(applicationContext)
        return when (EntitlementRefresh.afterAttempt(fetch, runAttemptCount)) {
            RefreshVerdict.RETRY -> Result.retry()
            RefreshVerdict.DONE -> Result.success()
        }
    }

    /**
     * Expedited work runs as a foreground service before Android 12, which
     * needs a notification; from 12 on this is never asked for. Same channel
     * and type as the transfers ([TransferNotifications]).
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        TransferNotifications.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, TransferNotifications.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.license_refresh_title))
            .setSmallIcon(R.drawable.ic_cloud_download)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .build()
        return ForegroundInfo(REFRESH_NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val PERIOD_HOURS = 4L
        private const val REFRESH_NOTIF_ID = 4104

        /** Input of the one-off: `/v1/config` only, retried on failure. */
        internal const val KEY_REFRESH_NOW = "refresh_now"

        fun enqueue(context: Context) {
            runCatching {
                val work = PeriodicWorkRequestBuilder<LicenseConfigWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .addTag(WorkTags.LICENSE_CONFIG)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(WorkTags.LICENSE_CONFIG_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
            }.onFailure {
                Timber.w(it, "Could not schedule license config refresh")
            }
        }

        /**
         * Fetch `/v1/config` as soon as the network allows: unique under
         * [WorkTags.LICENSE_CONFIG_NOW_NAME], [ExistingWorkPolicy.KEEP] so a
         * queued or running one is not restarted, expedited, with the
         * transfers' exponential backoff.
         */
        fun refreshNow(context: Context) {
            runCatching {
                val work = oneTimeWork<LicenseConfigWorker>(
                    tags = listOf(WorkTags.LICENSE_CONFIG),
                    input = workDataOf(KEY_REFRESH_NOW to true),
                    expedited = true,
                )
                enqueueUnique(context, WorkTags.LICENSE_CONFIG_NOW_NAME, ExistingWorkPolicy.KEEP, work)
            }.onFailure {
                Timber.w(it, "Could not queue a license config refresh")
            }
        }

        /** [refreshNow] when the stored entitlement token's `refreshAfter` has passed, or it lapsed. */
        fun refreshNowIfDue(context: Context) {
            val due = runCatching { EntitlementStore.refreshDue(context) }
                .onFailure { Timber.w(it, "Could not read the entitlement token's refresh time") }
                .getOrDefault(false)
            if (due) refreshNow(context)
        }

        /**
         * For [AppForeground.install], from [Application.onCreate]: each time
         * the app comes to the foreground (its first screen starting, at launch
         * or on return), [refreshNowIfDue] on [scope], off the main thread,
         * since the first read of the token touches the Keystore and the disk.
         */
        fun foregroundCheck(app: Application, scope: CoroutineScope): AppForeground.Listener =
            object : AppForeground.Listener {
                override fun onForeground() {
                    scope.launch { refreshNowIfDue(app) }
                }
            }

        fun cancel(context: Context) {
            runCatching {
                val workManager = WorkManager.getInstance(context.applicationContext)
                workManager.cancelUniqueWork(WorkTags.LICENSE_CONFIG_NAME)
                workManager.cancelUniqueWork(WorkTags.LICENSE_CONFIG_NOW_NAME)
            }
        }
    }
}
