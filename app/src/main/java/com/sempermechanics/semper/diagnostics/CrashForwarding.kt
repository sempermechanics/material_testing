package com.sempermechanics.semper.diagnostics

/**
 * What one release log line may do, decided without Android so it is
 * unit-tested on the JVM. Nothing leaves the phone, and nothing reaches
 * logcat, unless the user opted in to diagnostics
 * (`AppSettings.diagnosticsEnabled`, the one consent flag analytics and crash
 * reporting share).
 */
internal object CrashForwarding {

    /** `android.util.Log.WARN`: the release tree's floor. */
    const val WARN = 5

    /** `android.util.Log.ERROR`: from here a throwable becomes a non-fatal. */
    const val ERROR = 6

    /** Logcat's tag limit. */
    const val MAX_TAG_LEN = 23

    data class Plan(
        /** Echo the line to logcat under [tag]. */
        val logcat: Boolean,
        val tag: String,
        /** The Crashlytics breadcrumb, or null for none. */
        val breadcrumb: String?,
        /** Record the throwable as a Crashlytics non-fatal. */
        val recordException: Boolean,
    )

    fun plan(priority: Int, tag: String?, message: String, hasThrowable: Boolean, consented: Boolean): Plan {
        val safeTag = tag?.take(MAX_TAG_LEN) ?: "Semper"
        return if (!consented) {
            Plan(logcat = false, tag = safeTag, breadcrumb = null, recordException = false)
        } else {
            Plan(
                logcat = true,
                tag = safeTag,
                breadcrumb = if (tag != null) "$tag: $message" else message,
                recordException = priority >= ERROR && hasThrowable,
            )
        }
    }
}
