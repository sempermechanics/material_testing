package com.indicvision.semper

import android.app.Application
import com.indicvision.semper.data.DicSettings
import timber.log.Timber

/**
 * Plants the logging tree: verbose Timber logging in debug builds only.
 * Release builds log nothing (crash reporting will get its own tree later).
 */
class SemperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        DicSettings.migrate(this)
    }
}
