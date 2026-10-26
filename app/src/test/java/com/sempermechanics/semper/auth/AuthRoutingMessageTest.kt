package com.sempermechanics.semper.auth

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.auth.AuthActivity
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * AuthActivity is exported for its App Links, so any installed app can start it
 * with extras of its choosing (readiness TE5-008). It shows a routing message only
 * when this app's own routing put it there.
 */
@RunWith(RobolectricTestRunner::class)
class AuthRoutingMessageTest {

    /** The text of the in-window toast (`CrispToast`) the screen opened with, or null. */
    private fun open(intent: Intent): String? {
        val activity = Robolectric.buildActivity(AuthActivity::class.java, intent).setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity.findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<View>("semper_crisp_toast")
            ?.findViewById<TextView>(R.id.tvToast)
            ?.text
            ?.toString()
    }

    @Test
    fun `a message another app put on the intent is not shown`() {
        val foreign = Intent(ApplicationProvider.getApplicationContext(), AuthActivity::class.java)
            .putExtra(IntentKeys.ROUTING_ERROR, "Call this number to restore your account")

        assertNull(open(foreign))
    }

    @Test
    fun `the app's own sign-in route still shows its reason`() {
        val own = AuthRoute.signInIntent(ApplicationProvider.getApplicationContext(), "Session expired.", devBypass = false)

        assertEquals("Session expired.", open(own))
    }
}
