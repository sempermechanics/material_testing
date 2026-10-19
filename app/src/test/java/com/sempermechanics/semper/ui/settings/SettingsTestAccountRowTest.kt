package com.sempermechanics.semper.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.TestAccountDto
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The account section's test-account row (ADR-027): "Test account · ends
 * {day}" for a QC account, "Test account (CI)" for a CI one without an end,
 * and no row on a real account.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTestAccountRowTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<SettingsActivity>? = null

    /** 2026-11-18T10:00:00Z. */
    private val endsMs = 1_794_996_000_000L

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SignOutRun.resetForTest()
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
        SignOutRun.resetForTest()
    }

    private fun account(testAccount: TestAccountDto?) = AppRemoteConfig.apply(
        context,
        AppConfigDto(mode = "licensed", cloudBackupEnabled = true, maxSessions = 50, testAccount = testAccount),
        authoritative = true,
    )

    private fun row(): TextView {
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val settings = controller!!.get()
        shadowOf(settings.mainLooper).idle()
        return settings.findViewById(R.id.tvAccountTestAccount)
    }

    @Test
    fun `a QC account shows when it ends`() {
        account(TestAccountDto(purpose = "qc", expiresAt = "2026-11-18T10:00:00+00:00"))
        val row = row()
        assertEquals(View.VISIBLE, row.visibility)
        val day = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(endsMs))
        assertEquals("Test account · ends $day", row.text.toString())
    }

    @Test
    fun `a CI account without an end says CI`() {
        account(TestAccountDto(purpose = "ci", expiresAt = null))
        val row = row()
        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("Test account (CI)", row.text.toString())
    }

    @Test
    fun `a real account has no row`() {
        account(null)
        assertEquals(View.GONE, row().visibility)
    }

    @Test
    fun `the line is localised, read on the UTC day, and plain for an odd account`() {
        val line = { account: AppRemoteConfig.TestAccount?, locale: Locale ->
            SettingsAccountSection.testAccountLine(context, account, locale)
        }
        assertNull(line(null, Locale.US))
        assertEquals("Test account · ends Nov 18, 2026", line(AppRemoteConfig.TestAccount("qc", endsMs), Locale.US))
        // 23:30 UTC on the 18th is the 19th east of UTC: the day shown is the UTC one.
        val lateMs = endsMs + (13 * 60 + 30) * 60_000L
        assertEquals("Test account · ends Nov 18, 2026", line(AppRemoteConfig.TestAccount("qc", lateMs), Locale.US))
        assertEquals("Test account · ends 18.11.2026", line(AppRemoteConfig.TestAccount("ci", endsMs), Locale.GERMANY))
        val noEnd = AppRemoteConfig.NO_INSTANT
        assertEquals("Test account (CI)", line(AppRemoteConfig.TestAccount("ci", noEnd), Locale.US))
        assertEquals("Test account", line(AppRemoteConfig.TestAccount("qc", noEnd), Locale.US))
    }
}
