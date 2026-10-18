package com.sempermechanics.semper.diagnostics

import android.content.Context
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.sempermechanics.semper.data.prefs.AppSettings
import timber.log.Timber

/** Where the release tree's breadcrumbs and non-fatals go: Crashlytics, or a fake in tests. */
internal interface CrashSink {
    fun log(message: String)

    fun recordException(throwable: Throwable)
}

/**
 * Release tree: Crashlytics breadcrumbs and non-fatals, and logcat, only while
 * the user has consented to diagnostics ([CrashForwarding]). Without consent
 * a line goes nowhere: Crashlytics keeps breadcrumbs and recorded exceptions
 * on disk for its next report, so even a disabled SDK must not be handed them.
 */
class CrashReportingTree internal constructor(
    private val consented: () -> Boolean,
    private val sink: () -> CrashSink,
) : Timber.Tree() {

    constructor(context: Context) : this(
        consented = context.applicationContext.let { app -> { AppSettings.diagnosticsEnabled(app) } },
        sink = { CrashlyticsSink },
    )

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= CrashForwarding.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val plan = CrashForwarding.plan(priority, tag, message, hasThrowable = t != null, consented = consented())
        if (plan.logcat) {
            Log.println(priority, plan.tag, message)
            if (t != null) Log.println(priority, plan.tag, Log.getStackTraceString(t))
        }
        plan.breadcrumb?.let { sink().log(it) }
        if (plan.recordException && t != null) sink().recordException(t)
    }

    private object CrashlyticsSink : CrashSink {
        override fun log(message: String) = FirebaseCrashlytics.getInstance().log(message)

        override fun recordException(throwable: Throwable) =
            FirebaseCrashlytics.getInstance().recordException(throwable)
    }
}
