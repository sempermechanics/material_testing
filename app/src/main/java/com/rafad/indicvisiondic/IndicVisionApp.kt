package com.rafad.indicvisiondic

import android.app.Application
import timber.log.Timber

/**
 * Plants the logging tree: verbose Timber logging in debug builds only.
 * Release builds log nothing (crash reporting will get its own tree later).
 */
class IndicVisionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }
}
