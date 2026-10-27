package com.sempermechanics.semper.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.transfer.RestoreRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
import java.io.File
import java.io.IOException

/**
 * A restore Settings asks for, a change to the improvement consent, an
 * export of the user's data and a phone-only erase run in [SettingsViewModel]:
 * a rotation while one is running no longer cancels it, and what it came to
 * is said once, on the screen that is there.
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
    fun `an open section stays open when the screen is rebuilt`() {
        val controller = settings()
        val first = controller.get()
        first.findViewById<View>(R.id.headerStorage).performClick()
        idle()
        assertEquals(View.VISIBLE, first.findViewById<View>(R.id.bodyStorage).visibility)

        val rebuilt = controller.recreate().get()
        idle()

        assertEquals(View.VISIBLE, rebuilt.findViewById<View>(R.id.bodyStorage).visibility)
        assertEquals(View.GONE, rebuilt.findViewById<View>(R.id.bodyYourData).visibility)
    }

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

    @Test
    fun `a consent change the server refuses leaves the switch and the stored answer as they were`() {
        AccountCache.setImprovementConsent(context, false)
        val controller = settings()
        val vm = viewModelOf(controller.get())
        val api = FakeCloudApi().apply { onSetImprovementConsent = { _, _ -> throw IOException("refused") } }
        val repo = AuthRepository(context, api, FakeTokens(), signedIn = { true })
        // The real repository (TD-242), held on the main thread until it answers,
        // so one idle() delivers the outcome.
        vm.sendConsent = { granted -> runBlocking { repo.setImprovementConsent(granted) } }
        val switch = controller.get().findViewById<CompoundButton>(R.id.switchImprovementConsent)
        assertFalse(switch.isChecked)
        ShadowToast.reset()

        switch.performClick()
        idle()

        assertTrue("the change was sent", "setImprovementConsent" in api.calls)
        assertEquals(false, AccountCache.improvementConsent(context))
        assertFalse("the switch is back at the server's answer", switch.isChecked)
        assertTrue(switch.isEnabled)
        assertEquals(controller.get().getString(R.string.terms_error_generic), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `an export running across a rotation stays in the new screen's banner and ends there`() {
        val controller = settings()
        val vm = viewModelOf(controller.get())
        val produced = CompletableDeferred<File?>()
        vm.produceExport = { _, onProgress ->
            onProgress(1, 4)
            produced.await()
        }
        assertTrue(vm.exportData(SettingsViewModel.DataExport.LOCAL))
        idle()
        assertTrue(controller.get().transferBanner.contains(LOCAL_EXPORT_KEY))
        assertFalse("one export of a kind at a time", vm.exportData(SettingsViewModel.DataExport.LOCAL))

        val rebuilt = controller.recreate().get()
        idle()
        assertTrue("the new screen shows the export", rebuilt.transferBanner.contains(LOCAL_EXPORT_KEY))

        ShadowToast.reset()
        produced.complete(null)
        idle()

        assertFalse(rebuilt.transferBanner.contains(LOCAL_EXPORT_KEY))
        assertEquals(rebuilt.getString(R.string.export_data_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a cancelled export leaves the banner and says nothing`() {
        val controller = settings()
        val vm = viewModelOf(controller.get())
        vm.produceExport = { _, _ -> CompletableDeferred<File?>().await() }
        vm.exportData(SettingsViewModel.DataExport.CLOUD)
        idle()
        controller.recreate()
        idle()
        assertTrue(controller.get().transferBanner.contains(CLOUD_EXPORT_KEY))

        ShadowToast.reset()
        vm.cancelExport(SettingsViewModel.DataExport.CLOUD)
        idle()

        assertFalse(controller.get().transferBanner.contains(CLOUD_EXPORT_KEY))
        assertTrue(vm.exports.value.isEmpty())
        assertEquals(0, ShadowToast.shownToastCount())
    }

    @Test
    fun `a phone-only erase finishing across a rotation is confirmed on the new screen`() {
        val controller = settings()
        val vm = viewModelOf(controller.get())
        val erased = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        vm.eraseLocal = { id ->
            asked += id
            erased.await()
        }
        vm.eraseLocalOnly("s1")
        idle()

        val rebuilt = controller.recreate().get()
        idle()
        assertNull(crispToastText(rebuilt))

        erased.complete(Unit)
        idle()

        assertEquals(listOf("s1"), asked)
        assertEquals(rebuilt.getString(R.string.delete_device_only_done), crispToastText(rebuilt))
    }

    /** The text of the in-window toast (`CrispToast`) on [activity], or null. */
    private fun crispToastText(activity: SettingsActivity): String? =
        activity.findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<View>("semper_crisp_toast")
            ?.findViewById<TextView>(R.id.tvToast)
            ?.text
            ?.toString()

    private companion object {
        const val LOCAL_EXPORT_KEY = "export_local"
        const val CLOUD_EXPORT_KEY = "export_cloud"
    }
}
