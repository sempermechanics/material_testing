package com.indicvision.semper.settings

import android.view.View
import com.indicvision.semper.R
import com.indicvision.semper.ui.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * Deleting an account is gated on proving the identity, and that proof now
 * happens on the sign-in screen rather than in a password dialog of its own.
 */
// Pinned like the other Robolectric tests: 4.14 tops out below our targetSdk.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeleteAccountReauthTest {

    private fun settings(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    @Test
    fun `delete asks for confirmation before anything else happens`() {
        val activity = settings()

        activity.findViewById<View>(R.id.btnDeleteAccount).performClick()

        assertNotNull("expected the are-you-sure dialog", ShadowDialog.getLatestDialog())
        assertNull("nothing should be launched yet", shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `confirming hands off to the sign-in screen instead of a password box`() {
        val activity = settings()

        activity.findViewById<View>(R.id.btnDeleteAccount).performClick()
        val confirm = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        confirm.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(activity.mainLooper).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("expected the re-auth screen to be launched", started)
        assertEquals(
            "com.indicvision.semper.ui.auth.AuthActivity",
            started.component?.className,
        )
        assertTrue(
            "the screen must be asked for re-auth, not a fresh sign-in",
            started.getBooleanExtra("reauth", false),
        )
    }
}
