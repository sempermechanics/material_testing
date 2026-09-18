package com.indicvision.semper.auth

import android.content.Context
import android.widget.Button
import android.widget.CheckBox
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.data.LegalTerms
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.auth.AccessRouter
import com.indicvision.semper.ui.auth.AuthActivity
import com.indicvision.semper.ui.auth.PendingApprovalActivity
import com.indicvision.semper.ui.auth.TermsActivity
import com.indicvision.semper.ui.home.HomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The clickwrap gate. What matters legally: nobody reaches Home or Pending
 * under terms they have not accepted, acceptance is an affirmative act (the
 * box starts unticked and the button dead), the improvement consent is a
 * separate choice that never unlocks the button, and declining ends the session.
 */
// Pinned like the other Robolectric tests: 4.14 tops out below our targetSdk.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TermsGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun freshDevice() = TokenStore.clear(context)

    @Test
    fun `a fresh device needs acceptance and is routed through the gate`() {
        assertTrue(LegalTerms.needsAcceptance(context))
        assertEquals(LegalTerms.TERMS_VERSION, LegalTerms.requiredVersion(context))

        val intent = AccessRouter.intentFor(context, HomeActivity::class.java)
        assertEquals(TermsActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `pending approval is gated too, sign-in is not`() {
        assertTrue(AccessRouter.isGated(PendingApprovalActivity::class.java))
        assertTrue(AccessRouter.isGated(HomeActivity::class.java))
        assertFalse(AccessRouter.isGated(AuthActivity::class.java))

        val intent = AccessRouter.intentFor(context, AuthActivity::class.java)
        assertEquals(AuthActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `an accepted current version goes straight through`() {
        TokenStore.setTermsAccepted(context, LegalTerms.TERMS_VERSION, synced = true)

        assertFalse(LegalTerms.needsAcceptance(context))
        val intent = AccessRouter.intentFor(context, HomeActivity::class.java)
        assertEquals(HomeActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `a server-side version bump re-gates an already accepted user`() {
        TokenStore.setTermsAccepted(context, LegalTerms.TERMS_VERSION, synced = true)
        TokenStore.setTermsRequiredVersion(context, "2099-01-01")

        assertEquals("2099-01-01", LegalTerms.requiredVersion(context))
        assertTrue(LegalTerms.needsAcceptance(context))
        val intent = AccessRouter.intentFor(context, HomeActivity::class.java)
        assertEquals(TermsActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun `the required box starts unticked and the button is dead until it is ticked`() {
        val activity = launchGate()
        val agree = activity.findViewById<CheckBox>(R.id.cbAgreeTerms)
        val improve = activity.findViewById<CheckBox>(R.id.cbImprovementConsent)
        val button = activity.findViewById<Button>(R.id.btnAgree)

        assertFalse(agree.isChecked)
        assertTrue("improvement consent is pre-ticked by product decision", improve.isChecked)
        assertFalse(button.isEnabled)

        agree.isChecked = true
        assertTrue(button.isEnabled)
        agree.isChecked = false
        assertFalse(button.isEnabled)
    }

    @Test
    fun `the improvement box never unlocks the button either way`() {
        val activity = launchGate()
        val improve = activity.findViewById<CheckBox>(R.id.cbImprovementConsent)
        improve.isChecked = false
        assertFalse(activity.findViewById<Button>(R.id.btnAgree).isEnabled)
        improve.isChecked = true
        assertFalse(activity.findViewById<Button>(R.id.btnAgree).isEnabled)
    }

    @Test
    fun `declining signs out and returns to sign-in`() {
        val activity = launchGate()
        var signedOut = false
        activity.signOut = { signedOut = true }

        activity.findViewById<Button>(R.id.btnDecline).performClick()

        assertTrue(signedOut)
        assertTrue(activity.isFinishing)
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(AuthActivity::class.java.name, next.component?.className)
        assertNull("no acceptance may be recorded on decline", TokenStore.termsAcceptedVersion(context))
    }

    @Test
    fun `back is a decline, not a way around the gate`() {
        val activity = launchGate()
        var signedOut = false
        activity.signOut = { signedOut = true }

        activity.onBackPressedDispatcher.onBackPressed()

        assertTrue(signedOut)
        assertTrue(activity.isFinishing)
        assertEquals(
            AuthActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className,
        )
    }

    private fun launchGate(): TermsActivity {
        val intent = TermsActivity.intent(context, HomeActivity::class.java)
        return Robolectric.buildActivity(TermsActivity::class.java, intent).setup().get()
    }
}
