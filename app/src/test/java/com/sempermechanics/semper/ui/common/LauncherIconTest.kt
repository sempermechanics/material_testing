package com.sempermechanics.semper.ui.common

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The launcher shows the light entry by day and the jet-black one at night, never both or neither. */
@RunWith(RobolectricTestRunner::class)
class LauncherIconTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val pm = context.packageManager

    private fun state(name: String) = pm.getComponentEnabledSetting(ComponentName(context, name))

    @Test
    fun `night enables the dark entry and disables the light one`() {
        LauncherIcon.sync(context, night = true)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(LauncherIcon.DARK))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(LauncherIcon.LIGHT))
    }

    @Test
    fun `day after night swaps back`() {
        LauncherIcon.sync(context, night = true)
        LauncherIcon.sync(context, night = false)

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(LauncherIcon.LIGHT))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(LauncherIcon.DARK))
    }

    @Test
    fun `day on a fresh install leaves the manifest defaults alone`() {
        LauncherIcon.sync(context, night = false)

        // DEFAULT means the manifest's: LauncherLight enabled, LauncherDark not.
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(LauncherIcon.LIGHT))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(LauncherIcon.DARK))
    }

    @Test
    fun `exactly one launcher entry resolves in either theme`() {
        for (night in listOf(true, false)) {
            LauncherIcon.sync(context, night)
            val entries = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
                0,
            ).map { it.activityInfo.name }
            assertEquals("night=$night", listOf(if (night) LauncherIcon.DARK else LauncherIcon.LIGHT), entries)
        }
    }

    @Test
    fun `an entry that roots a live task is never disabled, so the task is not removed`() {
        // Readiness AE1-101: disabling the alias a task was started from makes
        // Android remove that task, closing Semper mid-wizard on a theme change.
        LauncherIcon.sync(context, night = true, liveRoots = { setOf(LauncherIcon.LIGHT) })

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(LauncherIcon.LIGHT))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, state(LauncherIcon.DARK))
    }

    @Test
    fun `the deferred swap happens once no task is rooted on the old entry`() {
        LauncherIcon.sync(context, night = true, liveRoots = { setOf(LauncherIcon.LIGHT) })
        // The splash screen has finished and handed the task's identity to Home.
        val home = "com.sempermechanics.semper.ui.home.HomeActivity"
        LauncherIcon.sync(context, night = true, liveRoots = { setOf(home) })

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(LauncherIcon.DARK))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(LauncherIcon.LIGHT))
    }

    @Test
    fun `a task rooted on the wanted entry does not hold the swap back`() {
        LauncherIcon.sync(context, night = true)
        LauncherIcon.sync(context, night = false, liveRoots = { setOf(LauncherIcon.LIGHT) })

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(LauncherIcon.LIGHT))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(LauncherIcon.DARK))
    }

    @Test
    fun `leaving the app retries a deferred swap`() {
        LauncherIcon.sync(context, night = true, liveRoots = { setOf(LauncherIcon.LIGHT) })

        LauncherIcon.foregroundListener(context, night = { true }).onBackground()

        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, state(LauncherIcon.DARK))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, state(LauncherIcon.LIGHT))
    }

    @Test
    fun `night is read from the configuration's ui mode`() {
        val config = Configuration()
        config.uiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES
        assertTrue(LauncherIcon.isNight(config))
        config.uiMode = Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_NO
        assertFalse(LauncherIcon.isNight(config))
    }
}
