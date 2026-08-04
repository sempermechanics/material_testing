package com.indicvision.semper

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Release logging tree that forwards to Firebase Crashlytics.
 *
 * Debug builds keep the verbose [Timber.DebugTree]; release builds plant this so
 * the app is not silent in the field. WARN/ERROR logs become Crashlytics
 * breadcrumbs (so the gate/upload-deferral `Timber.w` lines are visible in the
 * trail leading to a report), and any ERROR carrying a throwable is recorded as a
 * non-fatal. INFO/DEBUG/VERBOSE are dropped to keep the breadcrumb trail signal-y.
 */
class CrashReportingTree : Timber.Tree() {

    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        crashlytics.log(if (tag != null) "$tag: $message" else message)
        if (priority >= Log.ERROR && t != null) {
            crashlytics.recordException(t)
        }
    }
}
