package com.sempermechanics.semper.ui.account

import android.content.Context
import android.content.RestrictionsManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.textfield.TextInputLayout
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.policy.ManagedConfig
import com.sempermechanics.semper.data.policy.ManagedConfigReader
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The licence activation screen (TD-259): the EMM's `enrollmentInvite` fills
 * the field only when it has a key's shape, a key of the wrong shape is
 * refused on the field without a request, and a request that cannot reach
 * the server leaves the screen up and says why.
 */
@RunWith(RobolectricTestRunner::class)
class ActivateLicenseActivityTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<ActivateLicenseActivity>? = null
    private val screen: ActivateLicenseActivity get() = controller!!.get()

    private val field: EditText get() = screen.findViewById(R.id.etLicenseKey)
    private val fieldLayout: TextInputLayout get() = screen.findViewById(R.id.tilLicenseKey)
    private val serverMessage: TextView get() = screen.findViewById(R.id.tvActivateError)
    private val activate: View get() = screen.findViewById(R.id.btnActivateLicense)

    @Before
    fun setUp() {
        ensureTestFirebaseApp(context)
        manage(invite = null)
    }

    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        manage(invite = null)
    }

    /** The EMM's managed configuration, with [invite] as `enrollmentInvite` (none for null). */
    private fun manage(invite: String?) {
        val restrictions = Bundle()
        invite?.let { restrictions.putString(ManagedConfig.KEY_ENROLLMENT_INVITE, it) }
        shadowOf(context.getSystemService(RestrictionsManager::class.java)).setApplicationRestrictions(restrictions)
        ManagedConfigReader.refresh(context)
    }

    private fun open() {
        controller = Robolectric.buildActivity(ActivateLicenseActivity::class.java).setup()
        idle()
    }

    private fun idle() = shadowOf(screen.mainLooper).idle()

    private fun submit(typed: String) {
        field.setText(typed)
        activate.performClick()
        idle()
    }

    @Test
    fun `an enrollment invite with a licence key's shape fills the field`() {
        manage(invite = "semp-u8bx-4kq2-zp7m-w3rt")
        open()

        assertEquals(KEY, field.text.toString())
        assertNull(fieldLayout.error)
        assertFalse(serverMessage.isShown)
    }

    @Test
    fun `an invite code that is not a licence key leaves the field empty`() {
        manage(invite = "inv_7Hq2kZ9x")
        open()

        assertEquals("", field.text.toString())
    }

    @Test
    fun `typing is laid out as a key`() {
        open()
        field.setText("semp u8bx4kq2")

        assertEquals("SEMP-U8BX-4KQ2", field.text.toString())
    }

    @Test
    fun `a key of the wrong length is refused on the field and nothing is sent`() {
        open()
        submit("SEMP-U8BX-4KQ2")

        assertEquals(screen.getString(R.string.activate_invalid_length), fieldLayout.error?.toString())
        assertEquals(View.GONE, serverMessage.visibility)
        assertTrue("nothing went out, so the button stays usable", activate.isEnabled)
        assertFalse(screen.isFinishing)
    }

    @Test
    fun `a key without the prefix says so, and editing the key clears the message`() {
        open()
        submit("U8BX4KQ2ZP7MW3RT")
        assertEquals(screen.getString(R.string.activate_invalid_prefix), fieldLayout.error?.toString())

        field.setText("SEMP")
        idle()
        assertNull(fieldLayout.error)
    }

    @Test
    fun `an empty field asks for the key`() {
        open()
        submit("")

        assertEquals(screen.getString(R.string.activate_invalid_empty), fieldLayout.error?.toString())
    }

    @Test
    fun `activating signed out says Semper could not be reached and stays on the screen`() {
        manage(invite = KEY)
        open()
        activate.performClick()
        idleUntil("the answer") { serverMessage.visibility == View.VISIBLE }

        assertEquals(screen.getString(R.string.activate_error_network), serverMessage.text.toString())
        assertNull(fieldLayout.error)
        assertFalse("not activated, so the screen stays", screen.isFinishing)
        assertTrue("the button is usable again", activate.isEnabled)
    }

    @Test
    fun `Not now closes the screen`() {
        open()
        screen.findViewById<View>(R.id.tvActivateBack).performClick()
        idle()

        assertTrue(screen.isFinishing)
    }

    @Test
    fun `each wrong shape has its own field message`() {
        assertEquals(
            R.string.activate_invalid_empty,
            ActivateLicenseActivity.invalidMessage(LicenseKeyFormat.Check.Empty),
        )
        assertEquals(
            R.string.activate_invalid_prefix,
            ActivateLicenseActivity.invalidMessage(LicenseKeyFormat.Check.MissingPrefix),
        )
        assertEquals(
            R.string.activate_invalid_length,
            ActivateLicenseActivity.invalidMessage(LicenseKeyFormat.Check.WrongLength),
        )
    }

    private companion object {
        const val KEY = "SEMP-U8BX-4KQ2-ZP7M-W3RT"
    }
}
