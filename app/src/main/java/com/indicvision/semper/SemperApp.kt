package com.indicvision.semper

import android.app.Application
import com.indicvision.semper.data.CacheJanitor
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.StorageBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Plants the logging tree: verbose [Timber.DebugTree] in debug builds, and a
 * [CrashReportingTree] (Firebase Crashlytics) in release so field failures — and
 * the WARN breadcrumbs from the quota/upload gates — are no longer invisible.
 */
class SemperApp : Application() {

    /** Outlives every screen; only startup housekeeping runs here. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(CrashReportingTree())
        }
        DicSettings.migrate(this)
        // Manifest disables Crashlytics/Analytics collection, so a fresh install
        // sends nothing until the user opts in. This re-applies their choice on
        // every launch — including turning collection back OFF after a withdrawal.
        Diagnostics.apply(this)

        // Startup is the one moment nothing is in flight, so it is where cache
        // leftovers can be reclaimed without racing an import or a share.
        appScope.launch {
            CacheJanitor.sweepOnStartup(this@SemperApp)
            StorageBudget.enforce(this@SemperApp)
        }
    }
}
