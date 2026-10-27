package com.sempermechanics.semper.viewer

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * A locked feature reads the same on every surface (readiness AE1-015): the
 * viewer's Share is greyed, announced "Locked" like the Settings sections
 * [com.sempermechanics.semper.ui.common.dialog.LicenseLock] locks, and a tap
 * says how to unlock it. The demo's restore and download refusals end with
 * the same sentence.
 */
@RunWith(RobolectricTestRunner::class)
class ViewerShareLockTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() = AppRemoteConfig.clear(app)

    @After
    fun tearDown() = AppRemoteConfig.clear(app)

    private fun viewer(): ResultViewerActivity {
        val dir = temp.newFolder("batch")
        writeGridBatch(dir, 2, 4, 4)
        return launchViewer(viewerArgs(dir, 4, 4)).also { idleUntil("the viewer") { it.frameSetLoaded } }
    }

    private fun ResultViewerActivity.crispToast(): String? =
        findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<View>("semper_crisp_toast")
            ?.findViewById<TextView>(R.id.tvToast)
            ?.text
            ?.toString()

    @Test
    fun `on the demo the viewer's Share is greyed, says Locked and explains how to unlock`() {
        val activity = viewer()
        val share = activity.findViewById<View>(R.id.btnViewerShare)

        assertEquals(LicenseEntitlements.BLOCKED_ALPHA, share.alpha)
        assertEquals(activity.getString(R.string.license_locked), ViewCompat.getStateDescription(share))

        share.performClick()
        shadowOf(activity.mainLooper).idle()
        assertEquals(activity.getString(R.string.license_lock_note), activity.crispToast())
    }

    @Test
    fun `with sharing in the licence the viewer's Share is plain`() {
        AppRemoteConfig.apply(app, AppConfigDto(mode = "licensed", shareEnabled = true))
        val share = viewer().findViewById<View>(R.id.btnViewerShare)

        assertEquals(1f, share.alpha)
        assertNull(ViewCompat.getStateDescription(share))
    }

    @Test
    fun `the demo's restore and download refusals say how to unlock`() {
        val note = app.getString(R.string.license_lock_note)
        for (id in listOf(R.string.restore_not_licensed, R.string.download_not_licensed)) {
            val text = app.getString(id)
            assertTrue(text, text.endsWith(" $note"))
        }
    }
}
