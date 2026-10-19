package com.sempermechanics.semper.data

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether one of this app's screens is started, from one count of started
 * activities that `SemperApp` installs at launch ([install]). It replaces three
 * checks that each kept their own: the upload's read of the process importance
 * before it opens the limit screen ([DicUploadSeams.inForeground]), and the
 * started counts of [com.sempermechanics.semper.data.policy.ManagedConfigReader]
 * and [LicenseConfigWorker]'s foreground refresh, now [Listener]s.
 *
 * A started activity is a visible one, which is what Android 10+ asks of a
 * process that starts an activity from the background.
 */
object AppForeground {

    /** What runs as the app's first screen starts, and as its last one stops. Main thread. */
    interface Listener {
        fun onForeground()

        fun onBackground() = Unit
    }

    @Volatile
    private var count: StartedCount? = null

    /** Whether a screen of the app is started now. Any thread; false before [install]. */
    val isForeground: Boolean get() = count?.isStarted == true

    /**
     * From `Application.onCreate`: count [app]'s started activities, and tell
     * [listeners] when the first one starts and the last one stops.
     */
    fun install(app: Application, vararg listeners: Listener) {
        val started = StartedCount(listeners.toList())
        app.registerActivityLifecycleCallbacks(started)
        count = started
    }

    private class StartedCount(private val listeners: List<Listener>) : Application.ActivityLifecycleCallbacks {
        /** Written on the main thread, read by [isForeground] from any. */
        private val started = AtomicInteger()

        val isStarted: Boolean get() = started.get() > 0

        override fun onActivityStarted(activity: Activity) {
            if (started.incrementAndGet() == 1) listeners.forEach { it.onForeground() }
        }

        override fun onActivityStopped(activity: Activity) {
            // A stop with no start counted (installed while a screen was up) is not a background.
            if (started.get() == 0) return
            if (started.decrementAndGet() == 0) listeners.forEach { it.onBackground() }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
