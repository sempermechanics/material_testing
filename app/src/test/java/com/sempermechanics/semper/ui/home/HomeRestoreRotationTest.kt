package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.ui.common.transfer.RestoreRequest
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast

/**
 * A restore Home asks for runs in [HomeViewModel]: a rotation during its cloud
 * lookup no longer cancels it (the selection was already cleared, so the tap
 * did nothing), and what it came to is said once, on the screen that is there.
 */
@RunWith(RobolectricTestRunner::class)
class HomeRestoreRotationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<HomeActivity>>()
    private val target = RestoreStart.Target("c1", "s1", "Beam test")

    @Before
    fun setUp() {
        // Home observes transfer work in onCreate; the app starts WorkManager itself.
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        // No first-run prompt over the screen.
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
    fun `a restore whose lookup outlives a rotation still queues and says so on the new screen`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        val asked = mutableListOf<List<RestoreStart.Target>>()
        vm.requestRestore = { targets ->
            asked += targets
            RestoreRequest.Queued(targets.size, RestoreStart.Counts(started = targets.size, alreadyRunning = 0))
        }
        val lookup = CompletableDeferred<Unit>()
        vm.restore {
            lookup.await()
            listOf(target)
        }
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the restore's view model outlives the screen", vm, viewModelOf(rebuilt))
        assertTrue("nothing asked while the lookup runs", asked.isEmpty())

        ShadowToast.reset()
        lookup.complete(Unit)
        idle()

        assertEquals(listOf(listOf(target)), asked)
        assertEquals(rebuilt.getString(R.string.restore_background_note), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an outcome is said once, not again after the next rotation`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        vm.requestRestore = { targets ->
            RestoreRequest.Queued(targets.size, RestoreStart.Counts(started = targets.size, alreadyRunning = 0))
        }
        ShadowToast.reset()
        vm.restore { listOf(target) }
        idle()
        assertEquals(1, ShadowToast.shownToastCount())

        ShadowToast.reset()
        controller.recreate()
        idle()

        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun `an account without restore is told it is paused and nothing is queued`() {
        val controller = home()
        val vm = viewModelOf(controller.get())
        ShadowToast.reset()

        // The real request: this test account has no licence, so restore is off.
        vm.restore { listOf(target) }
        // The licence is asked on IO (readiness TE1-006), so the answer lands after a hop.
        idleUntil("the paused toast") { ShadowToast.shownToastCount() > 0 }

        assertEquals(controller.get().getString(R.string.restore_paused), ShadowToast.getTextOfLatestToast())
    }
}
