package com.sempermechanics.semper.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sempermechanics.semper.fixtures.awaitCondition
import com.sempermechanics.semper.ui.auth.SplashActivity
import com.sempermechanics.semper.ui.common.LauncherIcon
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A dark-mode switch while semper is open keeps its task (readiness AE1-101).
 * Before the fix, swapping the launcher alias disabled the one the task was
 * started from and Android removed the task about a second later, closing the
 * app mid-wizard. Started here the way a launcher starts it, so the task is
 * rooted on the alias.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconThemeSwitchDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val am = context.getSystemService(ActivityManager::class.java)
    private var restoreNight: String? = null

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText()
        }

    private fun systemNight(): Boolean = shell("cmd uimode night").contains("yes")

    private fun taskIds(): Set<Int> = am.appTasks.mapNotNull { runCatching { it.taskInfo?.taskId }.getOrNull() }.toSet()

    private fun resumedScreen(): String? {
        var name: String? = null
        instrumentation.runOnMainSync {
            name = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .firstOrNull()
                ?.javaClass
                ?.name
        }
        return name
    }

    private fun launcherEntries(): List<String> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
        0,
    ).map { it.activityInfo.name }

    @After
    fun restoreTheme() {
        restoreNight?.let { shell("cmd uimode night $it") }
        am.appTasks.forEach { runCatching { it.finishAndRemoveTask() } }
    }

    @Test
    fun aThemeSwitchKeepsTheTaskAndSwapsTheIcon() {
        val startNight = systemNight()
        restoreNight = if (startNight) "yes" else "no"
        // The launcher's start: MAIN/LAUNCHER resolves to the enabled alias.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        awaitCondition("a screen past the splash", timeoutMs = 20_000) {
            resumedScreen().let { it != null && it != SplashActivity::class.java.name }
        }
        val before = taskIds()
        assertTrue("no task after the launch", before.isNotEmpty())

        val night = !startNight
        shell("cmd uimode night ${if (night) "yes" else "no"}")
        awaitCondition("the configuration to reach the app", timeoutMs = 10_000) {
            LauncherIcon.isNight(context.applicationContext.resources.configuration) == night
        }
        // The removal came about a second after the switch; give it three.
        Thread.sleep(SETTLE_MS)

        assertEquals("the app's task was removed by the theme switch", before, before.intersect(taskIds()))
        awaitCondition("a resumed screen after the switch", timeoutMs = 10_000) { resumedScreen() != null }
        assertEquals(
            "the launcher entry follows the theme",
            listOf(if (night) LauncherIcon.DARK else LauncherIcon.LIGHT),
            launcherEntries(),
        )
    }

    private companion object {
        const val SETTLE_MS = 3_000L
    }
}
