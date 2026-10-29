package com.sempermechanics.semper.ui.common

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import com.sempermechanics.semper.data.AppForeground
import timber.log.Timber

/**
 * Keeps the launcher icon in step with the system's dark theme: the light icon
 * by day, the inverted S on jet black at night.
 *
 * Launchers never swap an icon's `-night` resources when the theme changes, so
 * the manifest carries two aliases of SplashActivity, [LIGHT] (enabled by
 * default) and [DARK], and this enables the one that fits. It runs at process
 * start, on a configuration change and when the app goes to the background, so
 * a theme change made while Semper is not running shows at its next start.
 *
 * Disabling the alias a task was started from makes Android remove that task:
 * a dark-mode switch closed the app mid-wizard (readiness AE1-101). So the swap
 * waits while any of this app's tasks is still rooted on the entry it would
 * disable. SplashActivity gives up the task's identity to the screen it opens
 * (`relinquishTaskIdentity` in the manifest), so once the splash has routed on,
 * the task no longer names the alias and the swap goes ahead.
 */
object LauncherIcon {

    const val LIGHT = "com.sempermechanics.semper.LauncherLight"
    const val DARK = "com.sempermechanics.semper.LauncherDark"

    /**
     * Enables the entry for [night] and disables the other; a no-op when they
     * already match, and deferred while [liveRoots] (the components this app's
     * tasks were started from) names the entry it would disable.
     */
    fun sync(
        context: Context,
        night: Boolean = isNight(Resources.getSystem().configuration),
        liveRoots: () -> Set<String> = { liveTaskRoots(context) },
    ) {
        val pm = context.packageManager
        val wanted = if (night) DARK else LIGHT
        val other = if (night) LIGHT else DARK
        try {
            val otherComponent = ComponentName(context, other)
            if (isEnabled(pm, otherComponent) && other in liveRoots()) {
                Timber.i("Launcher icon swap deferred: a task is rooted on %s", other)
                return
            }
            // Enable first, so the app is never without a launcher entry.
            setEnabled(pm, ComponentName(context, wanted), enabled = true)
            setEnabled(pm, otherComponent, enabled = false)
        } catch (e: SecurityException) {
            Timber.w(e, "Launcher icon not switched")
        } catch (e: IllegalArgumentException) {
            // A manifest without the aliases (a test or a stripped variant).
            Timber.w(e, "Launcher icon not switched")
        }
    }

    /** Retries a deferred swap as the app's last screen stops (installed by `SemperApp`). */
    fun foregroundListener(
        context: Context,
        night: () -> Boolean = { isNight(Resources.getSystem().configuration) },
    ): AppForeground.Listener = object : AppForeground.Listener {
        override fun onForeground() = Unit

        override fun onBackground() = sync(context, night())
    }

    fun isNight(config: Configuration): Boolean =
        config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** The class names each of this app's tasks was started from (an alias when launched from the launcher). */
    internal fun liveTaskRoots(context: Context): Set<String> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptySet()
        return am.appTasks.flatMap { task ->
            val info = runCatching { task.taskInfo }.getOrNull() ?: return@flatMap emptyList()
            listOfNotNull(info.baseIntent.component?.className, info.origActivity?.className)
        }.toSet()
    }

    private fun isEnabled(pm: PackageManager, component: ComponentName): Boolean =
        when (pm.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> component.className == LIGHT
            else -> false
        }

    private fun setEnabled(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        if (isEnabled(pm, component) == enabled) return
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
