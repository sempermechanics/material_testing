package com.sempermechanics.semper.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.widget.CompoundButton
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.transfer.RestoreRequest
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowToast
import java.io.IOException

/**
 * A restore Settings asks for, and a change to the improvement consent, run
 * in [SettingsViewModel]: a rotation while either is answering no longer
 * cancels it, and what it came to is said once, on the screen that is there.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRotationTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<SettingsActivity>>()
    private val target = RestoreStart.Target("c1", "s1", "Beam test")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SignOutRun.resetForTest()
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        screens.forEach { runCatching { it.pause().stop().destroy() } }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        SignOutRun.resetForTest()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settings(): ActivityController<SettingsActivity> =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().also {
            screens += it
            idle()
        }

    private fun viewModelOf(activity: SettingsActivity) = ViewModelProvider(activity)[SettingsViewModel::class.java]

    private val queued = RestoreRequest.Queued(1, RestoreStart.Counts(started = 1, alreadyRunning = 0))

    @Test
    fun `a restore answering across a rotation still queues and says so on the new screen`() {
        val controller = settings()
        val vm = viewModelOf(controller.get())
        val answer = CompletableDeferred<RestoreRequest>()
        val asked = mutableListOf<RestoreStart.Target>()
        vm.requestRestore = { target ->
            asked += target
            answer.await()
        }
        vm.restore("c1", target)
        idle()
        assertEquals(setOf("c1"), vm.restoringKeys)

        val rebuilt = controller.recreate().get()
        idle()
        assertSame("the restore's view model outlives the screen", vm, viewModelOf(rebuilt))

        ShadowToast.reset()
        answer.complete(queued)
        idle()

        assertEquals(listOf(target), asked)
        assertTrue(vm.restoringKeys.isEmpty())
        assertEquals(rebuilt.getString(R.string.restore_background_note), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a restore's outcome is said once, not again after the next rotation`() {
        val controller = settings()
        val vm = viewModelOf(controller.get())
        vm.requestRestore = { queued }
        ShadowToast.reset()
        vm.restore("c1", target)
        idle()
        assertEquals(1, ShadowToast.shownToastCount())

        ShadowToast.reset()
        controller.recreate()
        idle()

        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun `a paused restore says so`() {
        val controller = settings()
        val vm = viewModelOf(controller.get())
        vm.requestRestore = { RestoreRequest.Paused }
        ShadowToast.reset()

        vm.restore("c1", target)
        idle()

        assertEquals(controller.get().getString(R.string.restore_paused), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a consent change answering across a rotation waits on the new screen, then is said there`() {
        val controller = settings()
        val vm = viewModelOf(controller.get())
        val answer = CompletableDeferred<Result<Unit>>()
        val sent = mutableListOf<Boolean>()
        vm.sendConsent = { granted ->
            sent += granted
            answer.await()
        }
        val switch = controller.get().findViewById<CompoundButton>(R.id.switchImprovementConsent)
        assertFalse(switch.isChecked)
        switch.performClick()
        idle()
        assertFalse("the switch waits while the change is sent", switch.isEnabled)

        val rebuilt = controller.recreate().get()
        idle()
        val newSwitch = rebuilt.findViewById<CompoundButton>(R.id.switchImprovementConsent)
        assertTrue("the new screen shows the value being sent", newSwitch.isChecked)
        assertFalse("and still waits", newSwitch.isEnabled)

        ShadowToast.reset()
        answer.complete(Result.failure(IOException("offline")))
        idle()

        assertEquals("sent once, not again by the rebuilt switch", listOf(true), sent)
        assertTrue(newSwitch.isEnabled)
        assertEquals(rebuilt.getString(R.string.terms_error_generic), ShadowToast.getTextOfLatestToast())
    }
}
