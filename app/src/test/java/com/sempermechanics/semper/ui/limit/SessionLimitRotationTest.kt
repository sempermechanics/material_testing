package com.sempermechanics.semper.ui.limit

import android.os.Looper
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.CleanAppState
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * Re-check and Refresh licence run in [SessionLimitViewModel]: a rotation
 * while the backend answers no longer cancels them, the rebuilt screen waits
 * on them, and what each came to is said once, there.
 */
@RunWith(RobolectricTestRunner::class)
class SessionLimitRotationTest {

    @get:Rule
    val clean = CleanAppState()

    private val screens = mutableListOf<ActivityController<SessionLimitActivity>>()

    @After
    fun tearDown() {
        screens.forEach { runCatching { it.pause().stop().destroy() } }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screen(): ActivityController<SessionLimitActivity> =
        Robolectric.buildActivity(SessionLimitActivity::class.java).setup().also {
            screens += it
            idle()
        }

    private fun viewModelOf(activity: SessionLimitActivity) =
        ViewModelProvider(activity)[SessionLimitViewModel::class.java]

    @Test
    fun `a re-check answering across a rotation closes the new screen when there is room`() {
        val controller = screen()
        val vm = viewModelOf(controller.get())
        val answer = CompletableDeferred<SessionLimitViewModel.Outcome>()
        var checks = 0
        vm.check = {
            checks++
            answer.await()
        }
        controller.get().findViewById<View>(R.id.btnRecheckLimit).performClick()
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the check's view model outlives the screen", vm, viewModelOf(rebuilt))
        assertFalse("the new screen waits on the check", rebuilt.findViewById<View>(R.id.btnRecheckLimit).isEnabled)

        ShadowToast.reset()
        answer.complete(SessionLimitViewModel.Outcome.Cleared)
        idle()

        assertEquals(1, checks)
        assertEquals(rebuilt.getString(R.string.limit_cleared), ShadowToast.getTextOfLatestToast())
        assertTrue("the new screen closes", rebuilt.isFinishing)
    }

    @Test
    fun `a licence that changed across a rotation is checked again, and each answer said once`() {
        val controller = screen()
        val vm = viewModelOf(controller.get())
        val refreshed = CompletableDeferred<LicenseRefresh.Outcome>()
        vm.refresh = { refreshed.await() }
        var checks = 0
        vm.check = {
            checks++
            SessionLimitViewModel.Outcome.StillFull(R.string.limit_still_full)
        }
        controller.get().findViewById<View>(R.id.btnRefreshLicense).performClick()
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        ShadowToast.reset()
        refreshed.complete(LicenseRefresh.Outcome(LicenseRefreshResult.LICENSED_INDIVIDUAL, changed = true))
        idle()

        assertEquals("the changed licence is checked again", 1, checks)
        assertEquals(2, ShadowToast.shownToastCount())
        assertEquals(rebuilt.getString(R.string.limit_still_full), ShadowToast.getTextOfLatestToast())
        assertFalse(rebuilt.isFinishing)
        assertTrue("the buttons are back", rebuilt.findViewById<View>(R.id.btnRefreshLicense).isEnabled)

        ShadowToast.reset()
        controller.recreate()
        idle()
        assertEquals("not said again after the next rotation", 0, ShadowToast.shownToastCount())
    }
}
