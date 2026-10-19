package com.sempermechanics.semper.ui.limit

import android.content.Intent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.ensureTestFirebaseApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The "update required" gate (A6.1): it says what still works, opens the
 * store, and lets the user back into the app, which stays readable.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateRequiredActivityTest {

    @get:Rule
    val clean = CleanAppState()

    private lateinit var controller: ActivityController<UpdateRequiredActivity>
    private val screen: UpdateRequiredActivity get() = controller.get()

    @Before
    fun setUp() {
        ensureTestFirebaseApp(ApplicationProvider.getApplicationContext())
        controller = Robolectric.buildActivity(UpdateRequiredActivity::class.java).setup()
    }

    @After
    fun tearDown() {
        runCatching { controller.pause().stop().destroy() }
    }

    @Test
    fun `showing the gate marks it shown for this process`() {
        assertTrue(UpdateRequiredActivity.shownThisProcess)
    }

    @Test
    fun `Update opens this app's store listing when Play installed it`() {
        shadowOf(screen.packageManager).setInstallSourceInfo(screen.packageName, null, "com.android.vending")

        screen.findViewById<View>(R.id.btnUpdateApp).performClick()
        val started = shadowOf(screen).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("market://details?id=${screen.packageName}", started.dataString)
    }

    @Test
    fun `Update opens the release download page on a sideloaded build`() {
        // TD-268: no installer recorded (adb, or a downloaded APK) is not Play.
        screen.findViewById<View>(R.id.btnUpdateApp).performClick()
        val started = shadowOf(screen).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(UpdateSource.RELEASES_URL, started.dataString)
    }

    @Test
    fun `Update opens the release download page when another installer put the build there`() {
        shadowOf(screen.packageManager)
            .setInstallSourceInfo(screen.packageName, null, "com.google.android.packageinstaller")

        screen.findViewById<View>(R.id.btnUpdateApp).performClick()
        assertEquals(UpdateSource.RELEASES_URL, shadowOf(screen).nextStartedActivity.dataString)
    }

    @Test
    fun `Open my analyses closes the gate`() {
        screen.findViewById<View>(R.id.tvUpdateBack).performClick()
        shadowOf(screen.mainLooper).idle()
        assertTrue(screen.isFinishing)
    }
}
