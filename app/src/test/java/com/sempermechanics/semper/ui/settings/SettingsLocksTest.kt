package com.sempermechanics.semper.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.CompoundButton
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.ui.common.auth.SignOutRun
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
import org.robolectric.shadows.ShadowToast

/**
 * Locked, not hidden (`FeatureLocks.settings`): on demo, Cloud backup and
 * Analyses data stay in Settings with a lock, their controls disabled and one
 * note; a tap says the note and opens nothing. Backup off by policy (the kill
 * switch) hides them as before.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsLocksTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: ActivityController<SettingsActivity>? = null
    private val settings: SettingsActivity get() = controller!!.get()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        SignOutRun.resetForTest()
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        controller?.let { runCatching { it.pause().stop().destroy() } }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
        SignOutRun.resetForTest()
    }

    private fun open() {
        controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        shadowOf(settings.mainLooper).idle()
    }

    private fun <T : View> view(id: Int): T = settings.findViewById(id)

    /** The lock in the header of the section whose header include is [headerId]. */
    private fun lockOf(headerId: Int): View = view<View>(headerId).findViewById(R.id.imgSectionLock)

    private fun demo(killSwitches: List<String>? = null) =
        AppRemoteConfig.apply(context, AppConfigDto(mode = "demo", maxSessions = 5, killSwitches = killSwitches))

    private fun licensed(killSwitches: List<String>? = null) = AppRemoteConfig.apply(
        context,
        AppConfigDto(mode = "licensed", cloudBackupEnabled = true, maxSessions = 50, killSwitches = killSwitches),
    )

    @Test
    fun `on demo the cloud section is shown locked, its switches off and disabled`() {
        AppSettings.setSaveToCloudEnabled(context, true)
        demo()
        open()

        assertEquals(View.VISIBLE, view<View>(R.id.headerCloud).visibility)
        assertEquals(View.VISIBLE, lockOf(R.id.headerCloud).visibility)
        assertEquals(View.VISIBLE, view<View>(R.id.tvCloudLockNote).visibility)
        for (id in listOf(R.id.switchSaveCloud, R.id.switchWifiOnly, R.id.switchSyncOtherPhones)) {
            val switch = view<CompoundButton>(id)
            assertFalse(switch.isEnabled)
            assertFalse(switch.isChecked)
        }
        assertTrue("the stored choice is kept", AppSettings.saveToCloudEnabled(context))
    }

    @Test
    fun `a tap on a locked section says the note and opens nothing`() {
        demo()
        open()

        view<View>(R.id.bodyCloud).performClick()
        assertEquals(settings.getString(R.string.license_lock_note), ShadowToast.getTextOfLatestToast())

        view<View>(R.id.bodyAnalysesData).performClick()
        assertEquals(settings.getString(R.string.license_lock_note), ShadowToast.getTextOfLatestToast())

        view<View>(R.id.groupStorageFreeUp).performClick()
        assertEquals(settings.getString(R.string.license_lock_note), ShadowToast.getTextOfLatestToast())
        assertNull(shadowOf(settings).nextStartedActivity)
    }

    @Test
    fun `on demo the analyses data section is shown locked with no list`() {
        demo()
        open()

        assertEquals(View.VISIBLE, view<View>(R.id.headerAnalysesData).visibility)
        assertEquals(View.VISIBLE, lockOf(R.id.headerAnalysesData).visibility)
        assertEquals(View.VISIBLE, view<View>(R.id.tvAnalysesDataLockNote).visibility)
        assertEquals(View.GONE, view<View>(R.id.analysesDataList).visibility)
        assertEquals(View.GONE, view<View>(R.id.tvAnalysesDataCount).visibility)
    }

    @Test
    fun `a licensed account sees no lock and live switches`() {
        licensed()
        open()

        assertEquals(View.GONE, lockOf(R.id.headerCloud).visibility)
        assertEquals(View.GONE, lockOf(R.id.headerAnalysesData).visibility)
        assertEquals(View.GONE, view<View>(R.id.tvCloudLockNote).visibility)
        assertEquals(View.GONE, view<View>(R.id.tvAnalysesDataLockNote).visibility)
        assertTrue(view<CompoundButton>(R.id.switchSaveCloud).isEnabled)
    }

    @Test
    fun `the backup kill switch hides the sections as before, licensed or not`() {
        for (setUp in listOf({ licensed(listOf("backup")) }, { demo(listOf("backup")) })) {
            setUp()
            open()

            for (id in listOf(R.id.headerCloud, R.id.bodyCloud, R.id.headerAnalysesData, R.id.bodyAnalysesData)) {
                assertEquals("view $id", View.GONE, view<View>(id).visibility)
            }
            assertEquals(View.GONE, view<View>(R.id.btnStorageFreeUp).visibility)
            assertEquals(View.GONE, view<View>(R.id.tvStorageLockNote).visibility)
            controller?.pause()?.stop()?.destroy()
            controller = null
        }
    }
}
