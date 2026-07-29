package com.rafad.indicvisiondic.settings

import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.ui.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings → Help & support. The section is the only place in the app a user who
 * is merely stuck can find the support address, so what matters is that it is
 * present, shows the real address, and hands the mail app an actionable intent.
 */
// Pinned like the other Robolectric tests: 4.14 tops out below our targetSdk.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HelpSupportSectionTest {

    private val supportEmail: String =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.support_email)

    private fun settings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    @Test
    fun `section starts collapsed like every other settings section`() {
        val activity = settings()

        assertEquals(View.GONE, activity.findViewById<View>(R.id.bodyHelpSupport).visibility)
    }

    @Test
    fun `tapping the header reveals the support address`() {
        val activity = settings()

        activity.findViewById<View>(R.id.headerHelpSupport).performClick()

        val body = activity.findViewById<View>(R.id.bodyHelpSupport)
        assertEquals(View.VISIBLE, body.visibility)
        assertEquals(supportEmail, activity.findViewById<TextView>(R.id.tvSupportEmail).text.toString())
    }

    @Test
    fun `email support opens a mail intent addressed to support`() {
        val activity = settings()

        activity.findViewById<View>(R.id.btnEmailSupport).performClick()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("no intent was started", started)
        assertEquals(Intent.ACTION_SENDTO, started.action)
        assertEquals("mailto:", started.data.toString())
        assertEquals(supportEmail, started.getStringArrayExtra(Intent.EXTRA_EMAIL)?.single())
        assertEquals(
            activity.getString(R.string.help_support_subject),
            started.getStringExtra(Intent.EXTRA_SUBJECT),
        )
    }

    @Test
    fun `the mail body carries the diagnostics support needs to triage`() {
        val activity = settings()

        activity.findViewById<View>(R.id.btnEmailSupport).performClick()

        val body = shadowOf(activity).nextStartedActivity.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        for (label in listOf("Account:", "Device ID:", "App:", "Device:")) {
            assertTrue("body is missing '$label':\n$body", body.contains(label))
        }
        // The user types above the diagnostics block, not below it.
        assertTrue("diagnostics should not lead the body", body.startsWith("\n"))
    }

    @Test
    fun `signed out the account line falls back instead of crashing`() {
        val activity = settings()

        activity.findViewById<View>(R.id.btnEmailSupport).performClick()

        val body = shadowOf(activity).nextStartedActivity.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertTrue(
            "expected the unknown-account fallback:\n$body",
            body.contains("Account: " + activity.getString(R.string.pending_unknown_account)),
        )
    }
}
