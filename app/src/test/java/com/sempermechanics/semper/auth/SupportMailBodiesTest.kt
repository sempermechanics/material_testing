package com.sempermechanics.semper.auth

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import com.sempermechanics.semper.ui.auth.PendingApprovalActivity
import com.sempermechanics.semper.ui.common.auth.SupportMail
import com.sempermechanics.semper.ui.limit.SessionLimitActivity
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The support mails Pending ("request access") and Session limit (neutral:
 * room to write, then the account, quota and device) send, byte for byte:
 * support triages by these lines.
 */
@RunWith(RobolectricTestRunner::class)
class SupportMailBodiesTest {

    private val built = mutableListOf<ActivityController<out Activity>>()

    @After
    fun tearDown() = built.forEach { runCatching { it.pause().stop().destroy() } }

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val unknown = context.getString(R.string.pending_unknown_account)
    private val deviceId get() = DeviceKeys.deviceId(context)

    /** Pending reads the signed-in address through Firebase Auth, which needs an app; nobody is signed in. */
    @Before
    fun firebase() = ensureTestFirebaseApp(context)

    private fun <A : Activity> mailFrom(type: Class<A>, button: Int): Intent {
        val controller = Robolectric.buildActivity(type).setup().also { built += it }
        val activity = controller.get()
        activity.findViewById<View>(button).performClick()
        return shadowOf(activity).nextStartedActivity
    }

    @Test
    fun `the last lines name the app and the phone`() {
        // A handset is a phone in what the user reads, their own mail draft included
        // (readiness AE3-002); "device" stays only in the Device ID line.
        assertEquals(
            "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Phone: ${Build.MANUFACTURER} ${Build.MODEL} — Android ${Build.VERSION.RELEASE}",
            SupportMail.deviceLines(),
        )
    }

    @Test
    fun `session limit mail asks for nothing and leaves room above the account, quota and device`() {
        val mail = mailFrom(SessionLimitActivity::class.java, R.id.btnEmailSupport)

        val quota = "${AccountCache.quotaUsed(context)}/${AccountCache.quotaMax(context)}"
        assertEquals(
            "\n\n---\n" +
                "Account: $unknown\n" +
                "Quota: $quota\n" +
                "Device ID: $deviceId\n" +
                SupportMail.deviceLines(),
            mail.getStringExtra(Intent.EXTRA_TEXT),
        )
        assertEquals("Semper Classroom support — $unknown", mail.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `settings support mail leaves room to write above the account and device`() {
        val mail = mailFrom(SettingsActivity::class.java, R.id.btnEmailSupport)

        assertEquals(
            "\n\n---\n" +
                "Account: $unknown\n" +
                "Device ID: $deviceId\n" +
                SupportMail.deviceLines(),
            mail.getStringExtra(Intent.EXTRA_TEXT),
        )
    }

    @Test
    fun `access request mail names the account, then the device`() {
        val mail = mailFrom(PendingApprovalActivity::class.java, R.id.btnRequestAccess)

        assertEquals(
            "I'd like access to Semper Classroom.\n\n" +
                "Account: $unknown\n" +
                "Device ID: $deviceId\n" +
                SupportMail.deviceLines(),
            mail.getStringExtra(Intent.EXTRA_TEXT),
        )
        assertEquals(
            context.getString(R.string.request_access_subject) + " — " + unknown,
            mail.getStringExtra(Intent.EXTRA_SUBJECT),
        )
    }
}
