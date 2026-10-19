package com.sempermechanics.semper.auth

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AccessStatus
import com.sempermechanics.semper.ui.auth.AuthActivity
import com.sempermechanics.semper.ui.auth.AuthMode
import com.sempermechanics.semper.ui.auth.AuthScreen
import com.sempermechanics.semper.ui.auth.AuthViewModel
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The sign-in screen across a rotation: its form is kept (a Create account
 * form stays one), and a sign-in that was answering when the screen was
 * rebuilt still routes on from the new one, once.
 */
@RunWith(RobolectricTestRunner::class)
class AuthRotationTest {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun viewModelOf(activity: AuthActivity) = ViewModelProvider(activity)[AuthViewModel::class.java]

    @Test
    fun `a Create account form is still one after a rotation`() {
        val controller = Robolectric.buildActivity(AuthActivity::class.java).setup()
        controller.get().findViewById<TextView>(R.id.tvToggleMode).performClick()

        val rebuilt = controller.recreate().get()
        idle()

        assertEquals(
            rebuilt.getString(R.string.auth_create_account),
            rebuilt.findViewById<Button>(R.id.btnMainAction).text.toString(),
        )
        assertEquals(View.VISIBLE, rebuilt.findViewById<View>(R.id.tilConfirmPassword).visibility)
    }

    @Test
    fun `the form is kept in the saved state, so a restored process opens on it`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val saved = SavedStateHandle()
        AuthViewModel(app, saved).apply {
            begin(AuthMode.SIGN_IN)
            toggleRegister()
        }

        val restored = AuthViewModel(app, saved)
        restored.begin(AuthMode.SIGN_IN)

        assertEquals(AuthScreen.Form(AuthMode.REGISTER), restored.screen)
    }

    @Test
    fun `a sign-in answering across a rotation routes on from the new screen`() {
        val controller = Robolectric.buildActivity(AuthActivity::class.java).setup()
        val vm = viewModelOf(controller.get())
        val answer = CompletableDeferred<Result<String>>()
        vm.runAuth { answer.await() }
        idle()
        assertTrue("busy while the call runs", vm.busy.value)

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the call's view model outlives the screen", vm, viewModelOf(rebuilt))
        assertFalse(rebuilt.isFinishing)

        answer.complete(Result.success(AccessStatus.PENDING))
        idle()

        assertTrue("the new screen routes on", rebuilt.isFinishing)
        assertNotNull(shadowOf(rebuilt).nextStartedActivity)
        assertFalse(vm.busy.value)
    }

    @Test
    fun `a failed sign-in is said on the screen that is there, and leaves the form`() {
        val controller = Robolectric.buildActivity(AuthActivity::class.java).setup()
        val vm = viewModelOf(controller.get())
        val answer = CompletableDeferred<Result<String>>()
        vm.runAuth { answer.await() }

        val rebuilt = controller.recreate().get()
        answer.complete(Result.failure(IllegalStateException("Incorrect email or password.")))
        idle()

        assertFalse(rebuilt.isFinishing)
        assertEquals(View.GONE, rebuilt.findViewById<View>(R.id.progressBar).visibility)
        assertEquals(AuthScreen.Form(AuthMode.SIGN_IN), vm.screen)
    }
}
