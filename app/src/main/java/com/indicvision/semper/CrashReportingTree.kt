package com.indicvision.semper

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Release logging tree that forwards to Firebase Crashlytics **and** logcat.
 *
 * Debug builds keep the verbose [Timber.DebugTree]; release builds plant this.
 * WARN/ERROR become Crashlytics breadcrumbs (and ERROR+throwable → non-fatal).
 * The same WARN/ERROR lines are also printed to logcat — otherwise alpha field
 * debugging only saw WorkManager `RETRY` with no Semper reason (INFO was
 * dropped and Crashlytics breadcrumbs never appear in `adb logcat`).
 * INFO/DEBUG/VERBOSE stay out of logcat to keep release noise down.
 */
class CrashReportingTree : Timber.Tree() {

    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val safeTag = tag?.take(MAX_TAG_LEN) ?: "Semper"
        // Mirror to logcat first so `adb logcat` works offline / without Crashlytics.
        if (t != null) {
            Log.println(priority, safeTag, message)
            Log.println(priority, safeTag, Log.getStackTraceString(t))
        } else {
            Log.println(priority, safeTag, message)
        }
        crashlytics.log(if (tag != null) "$tag: $message" else message)
        if (priority >= Log.ERROR && t != null) {
            crashlytics.recordException(t)
        }
    }

    private companion object {
        const val MAX_TAG_LEN = 23
    }
}
