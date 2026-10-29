package com.sempermechanics.semper.data.cloud.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.data.prefs.AppSettings
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * "Download from my other phones": every few hours, on an unmetered network with
 * the battery not low, restore the account's backups this phone has no row
 * for ([PhoneSyncRun], [PhoneSync]). A holder with several activated phones
 * (ADR-033) then finds each phone's analyses on the others without asking.
 *
 * Scheduled on each approved sign-in or launch while the Settings switch is
 * on ([schedule]), and by the switch itself; cancelled by the switch and at
 * sign-out ([cancel]). Restores it has queued are their own work and run on.
 * A run that cannot list the backups ends; the next one asks again.
 */
class PhoneSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = PhoneSyncRun.run(applicationContext)
        Timber.d("Sync from other phones: %s", outcome)
        return Result.success()
    }

    companion object {
        private const val PERIOD_HOURS = 6L

        /**
         * Queue the periodic sync when the switch is on. [ExistingPeriodicWorkPolicy.KEEP]
         * makes a repeat free, so every approved launch may call it.
         */
        fun schedule(context: Context) {
            runCatching {
                if (!AppSettings.syncFromOtherPhonesEnabled(context)) return
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .build()
                val work = PeriodicWorkRequestBuilder<PhoneSyncWorker>(PERIOD_HOURS, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .addTag(WorkTags.PHONE_SYNC)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(WorkTags.PHONE_SYNC_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
            }.onFailure {
                Timber.w(it, "Could not schedule the sync from other phones")
            }
        }

        /** Stop future runs. Restores already queued are separate work and carry on. */
        fun cancel(context: Context) {
            runCatching {
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WorkTags.PHONE_SYNC_NAME)
            }
        }

        /** The Settings switch: save it, then [schedule] or [cancel]. */
        fun setEnabled(context: Context, enabled: Boolean) {
            AppSettings.setSyncFromOtherPhonesEnabled(context, enabled)
            if (enabled) schedule(context) else cancel(context)
        }
    }
}
