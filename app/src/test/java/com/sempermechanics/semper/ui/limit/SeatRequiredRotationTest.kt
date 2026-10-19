package com.sempermechanics.semper.ui.limit

import android.os.Looper
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.sempermechanics.semper.R
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * A seat request runs in [SeatRequiredViewModel]: a rotation while the backend
 * answers no longer cancels it, the rebuilt screen waits on it, and its
 * outcome is said once, there.
 */
@RunWith(RobolectricTestRunner::class)
class SeatRequiredRotationTest {

    private val screens = mutableListOf<ActivityController<SeatRequiredActivity>>()

    @After
    fun tearDown() {
        screens.forEach { runCatching { it.pause().stop().destroy() } }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun screen(): ActivityController<SeatRequiredActivity> =
        Robolectric.buildActivity(SeatRequiredActivity::class.java).setup().also {
            screens += it
            idle()
        }

    private fun viewModelOf(activity: SeatRequiredActivity) =
        ViewModelProvider(activity)[SeatRequiredViewModel::class.java]

    @Test
    fun `a seat taken across a rotation is said on the new screen, which closes`() {
        val controller = screen()
        val vm = viewModelOf(controller.get())
        val answer = CompletableDeferred<SeatRequiredViewModel.Outcome>()
        var asked = 0
        vm.ask = {
            asked++
            answer.await()
        }
        controller.get().findViewById<View>(R.id.btnTakeSeat).performClick()
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the request's view model outlives the screen", vm, viewModelOf(rebuilt))
        assertFalse("the new screen waits on the request", rebuilt.findViewById<View>(R.id.btnTakeSeat).isEnabled)
        assertFalse(rebuilt.isFinishing)

        ShadowToast.reset()
        answer.complete(SeatRequiredViewModel.Outcome.Taken)
        idle()

        assertEquals(1, asked)
        assertEquals(rebuilt.getString(R.string.seat_taken), ShadowToast.getTextOfLatestToast())
        assertTrue("the new screen closes", rebuilt.isFinishing)
    }

    @Test
    fun `a full pool is said once and leaves the screen up`() {
        val controller = screen()
        val vm = viewModelOf(controller.get())
        vm.ask = { SeatRequiredViewModel.Outcome.NotTaken(R.string.seat_still_full) }
        ShadowToast.reset()
        controller.get().findViewById<View>(R.id.btnTakeSeat).performClick()
        idle()

        assertEquals(controller.get().getString(R.string.seat_still_full), ShadowToast.getTextOfLatestToast())
        assertFalse(controller.get().isFinishing)
        assertTrue(controller.get().findViewById<View>(R.id.btnTakeSeat).isEnabled)

        ShadowToast.reset()
        controller.recreate()
        idle()
        assertEquals("not said again after a rotation", 0, ShadowToast.shownToastCount())
    }
}
