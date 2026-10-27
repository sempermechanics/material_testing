package com.sempermechanics.semper.ui.limit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.QuotaBackendOn
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import com.sempermechanics.semper.fixtures.idleUntil
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
 * The limit screen when the device half of the demo cap holds the stop
 * (TD-219): it says the phone is full, hides the account's counter, and its
 * Re-check keeps the screen up rather than reporting room the next upload
 * refuses.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLimitDeviceStopTest {

    @get:Rule
    val clean = CleanAppState()

    @get:Rule
    val backend = QuotaBackendOn()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<SessionLimitActivity>? = null

    @Before
    fun setUp() {
        ensureTestFirebaseApp(context)
        AppRemoteConfig.apply(context, AppConfigDto(mode = "demo", maxSessions = 5))
        AccountCache.setQuota(context, used = 1, localCount = 0)
        AccountCache.setDeviceLimitReached(context, true)
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        AppRemoteConfig.clear(context)
    }

    private fun open(): Activity = Robolectric.buildActivity(SessionLimitActivity::class.java).setup()
        .also { controller = it }
        .get()

    @Test
    fun `the screen says the phone is full and hides the account's counter`() {
        val limit = open()

        assertEquals(
            limit.getString(R.string.limit_body_device),
            limit.findViewById<TextView>(R.id.tvLimitBody).text.toString(),
        )
        assertEquals(View.GONE, limit.findViewById<View>(R.id.tvLimitQuota).visibility)
    }

    @Test
    fun `the support mail calls the stop the phone's limit`() {
        // A handset is a phone in what the user reads, their own mail draft included
        // (readiness AE3-002); "device" stays only in the Device ID line.
        val limit = open()

        limit.findViewById<View>(R.id.btnEmailSupport).performClick()

        val body = shadowOf(limit).nextStartedActivity.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val quota = body.lines().single { it.startsWith("Quota: ") }
        assertTrue(quota, quota.endsWith(" (phone limit)"))
    }

    @Test
    fun `Re-check keeps the device's stop and the screen`() {
        // The CI build has no backend, so the reconcile brings no numbers; the
        // rule still has one (QuotaBackendOn), and the device's stop holds.
        assumeTrue(BuildConfig.SEMPER_API_BASE_URL.isBlank())
        val limit = open()

        limit.findViewById<View>(R.id.btnRecheckLimit).performClick()

        val still = limit.getString(R.string.limit_still_full_device)
        idleUntil("the re-check result") { ShadowToast.getTextOfLatestToast() == still }
        assertFalse(limit.isFinishing)
        assertTrue(AccountCache.isDeviceLimitForced(context))
    }
}
