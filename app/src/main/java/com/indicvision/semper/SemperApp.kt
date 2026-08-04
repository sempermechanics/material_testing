package com.indicvision.semper

import android.app.Application
import com.indicvision.semper.data.DicSettings
import timber.log.Timber

/**
 * Plants the logging tree: verbose [Timber.DebugTree] in debug builds, and a
 * [CrashReportingTree] (Firebase Crashlytics) in release so field failures — and
 * the WARN breadcrumbs from the quota/upload gates — are no longer invisible.
 */
class SemperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(CrashReportingTree())
        }
        DicSettings.migrate(this)
    }
}
