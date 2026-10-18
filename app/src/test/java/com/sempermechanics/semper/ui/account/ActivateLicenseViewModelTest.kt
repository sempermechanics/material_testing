package com.sempermechanics.semper.ui.account

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.ui.account.ActivationState.Activated
import com.sempermechanics.semper.ui.account.ActivationState.Idle
import com.sempermechanics.semper.ui.account.ActivationState.Invalid
import com.sempermechanics.semper.ui.account.ActivationState.Refused
import com.sempermechanics.semper.ui.account.ActivationState.Running
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The activation screen's state: a key of the wrong shape never leaves the
 * phone, one request runs at a time, a success is final, and a message stays
 * until the key is changed (not when the field restores the same key).
 */
@RunWith(RobolectricTestRunner::class)
class ActivateLicenseViewModelTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<String>()

    private fun model(answer: suspend () -> ActivationOutcome = { ActivationOutcome.Activated }) =
        ActivateLicenseViewModel(app) { _, key ->
            sent += key
            answer()
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `a key of the wrong shape is not sent`() {
        val model = model()
        model.submit("semp-u8bx")
        idle()

        assertEquals(Invalid("SEMPU8BX", LicenseKeyFormat.Check.WrongLength), model.state.value)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a valid key is sent formatted, and success is final`() {
        val model = model()
        model.submit(" semp u8bx4kq2zp7mw3rt ")
        idle()

        assertEquals(listOf(KEY), sent)
        assertEquals(Activated, model.state.value)

        model.submit(KEY)
        model.edited("SEMP")
        idle()
        assertEquals(listOf(KEY), sent)
        assertEquals(Activated, model.state.value)
    }

    @Test
    fun `a second submit while one is out is ignored`() {
        val answer = CompletableDeferred<ActivationOutcome>()
        val model = model { answer.await() }
        model.submit(KEY)
        idle()
        assertEquals(Running, model.state.value)

        model.submit(KEY)
        idle()
        assertEquals(listOf(KEY), sent)

        answer.complete(ActivationOutcome.Activated)
        idle()
        assertEquals(Activated, model.state.value)
    }

    @Test
    fun `a refusal stays for the same key and clears when the key changes`() {
        val model = model { ActivationOutcome.Refused(ActivationError.INVALID_KEY) }
        model.submit(KEY)
        idle()
        val refused = Refused("SEMPU8BX4KQ2ZP7MW3RT", ActivationError.INVALID_KEY)
        assertEquals(refused, model.state.value)

        // The field restoring its text after a rotation, or a reformat.
        model.edited(KEY)
        assertEquals(refused, model.state.value)

        model.edited("SEMP-U8BX-4KQ2-ZP7M-W3R")
        assertEquals(Idle, model.state.value)
    }

    @Test
    fun `a refused key can be sent again`() {
        var answer: ActivationOutcome = ActivationOutcome.Refused(ActivationError.NETWORK)
        val model = model { answer }
        model.submit(KEY)
        idle()

        answer = ActivationOutcome.Activated
        model.submit(KEY)
        idle()
        assertEquals(listOf(KEY, KEY), sent)
        assertEquals(Activated, model.state.value)
    }

    @Test
    fun `editing with nothing showing changes nothing`() {
        val model = model()
        model.edited("SEMP")
        assertEquals(Idle, model.state.value)
    }

    private companion object {
        const val KEY = "SEMP-U8BX-4KQ2-ZP7M-W3RT"
    }
}
