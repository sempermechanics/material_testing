package com.sempermechanics.semper.ui.limit

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * Where "Refresh licence" is offered: Settings' account section and the
 * analysis-limit screen. Both show the result as one line and never open a
 * screen, a link or a purchase. The CI build has no backend, so the fetch is
 * never made and the line is "couldn't reach", with the retry queued.
 */
@RunWith(RobolectricTestRunner::class)
class LicenseRefreshEntryPointsTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<ActivityController<out Activity>>()

    @Before
    fun setUp() {
        ensureTestFirebaseApp(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SignOutRun.resetForTest()
        AppRemoteConfig.apply(context, AppConfigDto(mode = "demo", maxSessions = 5))
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

    private fun <A : Activity> open(type: Class<A>): A {
        val controller = Robolectric.buildActivity(type).setup()
        controllers += controller
        shadowOf(controller.get().mainLooper).idle()
        return controller.get()
    }

    private fun Activity.tapRefresh() {
        val button = findViewById<View>(R.id.btnRefreshLicense)
        assertEquals(View.VISIBLE, button.visibility)
        assertTrue(button.isEnabled)
        button.performClick()
    }

    private fun Activity.awaitResult(): String {
        val unreachable = getString(R.string.license_refresh_unreachable)
        idleUntil("the refresh result") { ShadowToast.getTextOfLatestToast() == unreachable }
        assertEquals("nothing opened", null, shadowOf(this).nextStartedActivity)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork("license-config-now").get().size)
        return ShadowToast.getTextOfLatestToast()
    }

    @Test
    fun `Settings refreshes the licence in place and says what it found`() {
        assumeTrue(BuildConfig.SEMPER_API_BASE_URL.isBlank())
        val settings = open(SettingsActivity::class.java)

        settings.tapRefresh()

        assertEquals(settings.getString(R.string.license_refresh_unreachable), settings.awaitResult())
        assertTrue("the button is back", settings.findViewById<View>(R.id.btnRefreshLicense).isEnabled)
    }

    @Test
    fun `the analysis-limit screen refreshes the licence and stays up while nothing changed`() {
        assumeTrue(BuildConfig.SEMPER_API_BASE_URL.isBlank())
        val limit = open(SessionLimitActivity::class.java)

        limit.tapRefresh()

        assertEquals(limit.getString(R.string.license_refresh_unreachable), limit.awaitResult())
        assertFalse(limit.isFinishing)
        assertTrue("the button is back", limit.findViewById<View>(R.id.btnRefreshLicense).isEnabled)
    }
}
