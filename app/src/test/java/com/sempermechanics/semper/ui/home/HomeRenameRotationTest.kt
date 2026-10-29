package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.prefs.AppSettings
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * A rename Home asks for runs in [HomeViewModel] (ADR-016 rule 1, readiness
 * AE1-111): it used to run on the screen's lifecycleScope, which the
 * activity-scope check flagged, and a rotation dropped its "saved as" toast.
 * Now it outlives the screen and is said once, on the screen that is there.
 */
@RunWith(RobolectricTestRunner::class)
class HomeRenameRotationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<HomeActivity>>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AccountCache.setBetaNoticeAcked(context)
        AppSettings.setDiagnosticsEnabled(context, false)
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        screens.forEach { it.close() }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun home(): ActivityController<HomeActivity> =
        Robolectric.buildActivity(HomeActivity::class.java).setup().also {
            screens += it
            idle()
        }

    private fun viewModelOf(activity: HomeActivity) = ViewModelProvider(activity)[HomeViewModel::class.java]

    @Test
    fun `a rename that outlives a rotation is applied and its new name said on the new screen`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        val gate = CompletableDeferred<Unit>()
        val applied = mutableListOf<Pair<String, String>>()
        vm.applyRename = { id, name ->
            gate.await()
            applied += id to name
            "$name (2)"
        }
        vm.rename("s1", "Beam test")
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the rename's view model outlives the screen", vm, viewModelOf(rebuilt))
        ShadowToast.reset()
        gate.complete(Unit)
        idle()

        assertEquals(listOf("s1" to "Beam test"), applied)
        assertEquals(
            rebuilt.getString(R.string.rename_saved_as_fmt, "Beam test (2)"),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `a rename kept as asked says nothing, and is not said again after a rotation`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        vm.applyRename = { _, name -> name }
        ShadowToast.reset()
        vm.rename("s1", "Coupon")
        idle()
        assertEquals(0, ShadowToast.shownToastCount())

        vm.applyRename = { _, name -> "$name (2)" }
        vm.rename("s1", "Coupon")
        idle()
        assertEquals(1, ShadowToast.shownToastCount())

        ShadowToast.reset()
        controller.recreate()
        idle()
        assertEquals(0, ShadowToast.shownToastCount())
    }
}
