package com.sempermechanics.semper.ui.account

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.limit.SessionLimitActivity
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Where the activation screen is offered: Settings' account section and the
 * analysis-limit screen, on demo only.
 */
@RunWith(RobolectricTestRunner::class)
class ActivationEntryPointsTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<ActivityController<out Activity>>()

    @Before
    fun setUp() {
        ensureTestFirebaseApp(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SignOutRun.resetForTest()
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
        SignOutRun.resetForTest()
    }

    private fun demo() = AppRemoteConfig.apply(context, AppConfigDto(mode = "demo", maxSessions = 5))

    private fun licensed() =
        AppRemoteConfig.apply(context, AppConfigDto(mode = "licensed", maxSessions = 50, cloudBackupEnabled = true))

    private fun <A : Activity> open(type: Class<A>): A {
        val controller = Robolectric.buildActivity(type).setup()
        controllers += controller
        shadowOf(controller.get().mainLooper).idle()
        return controller.get()
    }

    private fun Activity.entry(): View = findViewById(R.id.btnActivateLicense)

    private fun Activity.started(): String? = shadowOf(this).nextStartedActivity?.component?.className

    @Test
    fun `Settings offers activation on demo and opens the activation screen`() {
        demo()
        val settings = open(SettingsActivity::class.java)

        assertEquals(View.VISIBLE, settings.entry().visibility)
        settings.entry().performClick()
        assertEquals(ActivateLicenseActivity::class.java.name, settings.started())
    }

    @Test
    fun `Settings has no activation entry once licensed`() {
        licensed()
        val settings = open(SettingsActivity::class.java)

        assertEquals(View.GONE, settings.entry().visibility)
    }

    @Test
    fun `the analysis-limit screen offers activation on demo`() {
        demo()
        val limit = open(SessionLimitActivity::class.java)

        assertEquals(View.VISIBLE, limit.entry().visibility)
        limit.entry().performClick()
        assertEquals(ActivateLicenseActivity::class.java.name, limit.started())
    }

    @Test
    fun `the analysis-limit screen has no activation entry once licensed`() {
        licensed()
        val limit = open(SessionLimitActivity::class.java)

        assertEquals(View.GONE, limit.entry().visibility)
    }
}
