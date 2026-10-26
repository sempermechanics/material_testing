package com.sempermechanics.semper.ui.admin

import android.os.Looper
import android.view.View
import androidx.lifecycle.ViewModelProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AdminUserDto
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.io.IOException

/**
 * Approvals run in [AdminViewModel]: a rotation while the backend answers no
 * longer drops the answer, the list is not read again for the rotation, and
 * each outcome is said once, on the screen that is there.
 */
@RunWith(RobolectricTestRunner::class)
class AdminRotationTest {

    private val screens = mutableListOf<ActivityController<AdminActivity>>()
    private val defaultCalls = AdminViewModel.callsFor
    private val pending = AdminUserDto(uid = "u1", email = "ada@example.edu")

    /** The backend: one pending user until an approval answers ([answer]). */
    private inner class FakeCalls : AdminViewModel.Calls {
        var lists = 0
        val sent = mutableListOf<Pair<String, String>>()
        val answer = CompletableDeferred<Unit>()

        override suspend fun token(): String = "token"

        override suspend fun listPending(token: String): List<AdminUserDto> {
            lists++
            return if (answer.isCompleted) emptyList() else listOf(pending)
        }

        override suspend fun setStatus(token: String, uid: String, action: String) {
            sent += uid to action
            answer.await()
        }
    }

    private val backend = FakeCalls()

    @Before
    fun setUp() {
        AdminViewModel.callsFor = { backend }
    }

    @After
    fun tearDown() {
        screens.forEach { runCatching { it.pause().stop().destroy() } }
        AdminViewModel.callsFor = defaultCalls
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun admin(): ActivityController<AdminActivity> =
        Robolectric.buildActivity(AdminActivity::class.java).setup().also {
            screens += it
            idle()
        }

    private fun viewModelOf(activity: AdminActivity) = ViewModelProvider(activity)[AdminViewModel::class.java]

    @Test
    fun `an approval answering across a rotation is said on the new screen, which shows the new list`() {
        val controller = admin()
        val vm = viewModelOf(controller.get())
        assertEquals(1, backend.lists)
        vm.act(pending, AdminViewModel.ACTION_APPROVE)
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the approval's view model outlives the screen", vm, viewModelOf(rebuilt))
        assertEquals("a rotation does not read the list again", 1, backend.lists)
        assertEquals(View.VISIBLE, rebuilt.findViewById<View>(R.id.progressAdmin).visibility)

        ShadowToast.reset()
        backend.answer.complete(Unit)
        idle()

        assertEquals(listOf("u1" to AdminViewModel.ACTION_APPROVE), backend.sent)
        assertEquals(
            rebuilt.getString(R.string.admin_approved_toast_fmt, "ada@example.edu"),
            ShadowToast.getTextOfLatestToast(),
        )
        assertEquals("the list is read again after the approval", 2, backend.lists)
        assertEquals(View.VISIBLE, rebuilt.findViewById<View>(R.id.tvEmpty).visibility)
        assertEquals(View.GONE, rebuilt.findViewById<View>(R.id.progressAdmin).visibility)
        assertFalse(rebuilt.isFinishing)
    }

    @Test
    fun `a failed denial is said once, not again after the next rotation`() {
        val controller = admin()
        val vm = viewModelOf(controller.get())
        vm.act(pending, AdminViewModel.ACTION_REVOKE)
        idle()
        ShadowToast.reset()
        backend.answer.completeExceptionally(IOException("offline"))
        idle()

        assertEquals(
            // What the failure means, not the exception's text (readiness AE1-009).
            controller.get().run { getString(R.string.admin_action_error_fmt, getString(R.string.error_offline)) },
            ShadowToast.getTextOfLatestToast(),
        )

        ShadowToast.reset()
        controller.recreate()
        idle()
        assertEquals(0, ShadowToast.shownToastCount())
    }
}
