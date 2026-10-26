package com.sempermechanics.semper.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.policy.AppPolicy
import com.sempermechanics.semper.navigation.AppIntents
import com.sempermechanics.semper.ui.limit.UpdateRequiredActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Home's half of the version gate (readiness TE4-006): VersionGate's decision
 * and UpdateRequiredActivity were tested, the wiring between them was not. A
 * build below `minSupportedVersionCode` opens the gate once a process; one
 * below `recommendedVersionCode` gets one snackbar per version pair.
 */
@RunWith(RobolectricTestRunner::class)
class HomeUpdateGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screens = mutableListOf<ActivityController<HomeActivity>>()

    private companion object {
        const val INSTALLED = 1040
    }

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AppRemoteConfig.clear(context)
        AppPolicy.versionCodeForTest = INSTALLED
        UpdateRequiredActivity.shownThisProcess = false
    }

    // As HomeFirstRunPromptsTest: close Home, then undo the WorkManager delegate.
    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        screens.forEach { it.close() }
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppPolicy.versionCodeForTest = null
        UpdateRequiredActivity.shownThisProcess = false
        AppRemoteConfig.clear(context)
    }

    private fun config(min: Int? = null, recommended: Int? = null) = AppRemoteConfig.apply(
        context,
        AppConfigDto(minSupportedVersionCode = min, recommendedVersionCode = recommended),
        authoritative = true,
    )

    private fun home(): HomeActivity {
        val activity = Robolectric.buildActivity(HomeActivity::class.java).setup().also { screens += it }.get()
        shadowOf(activity.mainLooper).idle()
        return activity
    }

    /** The next Activity Home started that is the update gate, skipping any other. */
    private fun HomeActivity.startedGate(): Boolean {
        val gate = AppIntents.updateRequired(this).component
        while (true) {
            val next = shadowOf(this).nextStartedActivity ?: return false
            if (next.component == gate) return true
        }
    }

    private fun HomeActivity.snackbarText(): String? =
        window.decorView.findViewById<View>(com.google.android.material.R.id.snackbar_text)
            ?.let { (it as TextView).text.toString() }

    @Test
    fun `below the minimum Home opens the update gate`() {
        config(min = INSTALLED + 1)

        assertEquals(true, home().startedGate())
    }

    @Test
    fun `the gate opens once a process, not on every Home`() {
        config(min = INSTALLED + 1)
        home().startedGate()
        // The gate screen ran (Robolectric only records the start, so mark it).
        UpdateRequiredActivity.shownThisProcess = true

        assertEquals(false, home().startedGate())
    }

    @Test
    fun `at or above the minimum Home opens no gate`() {
        config(min = INSTALLED)

        assertEquals(false, home().startedGate())
    }

    @Test
    fun `below the recommended version one snackbar shows, once per version pair`() {
        config(recommended = INSTALLED + 1)

        val first = home()
        assertEquals(first.getString(R.string.update_recommended), first.snackbarText())
        assertEquals(false, first.startedGate())

        assertNull("the same pair is not nudged again", home().snackbarText())

        config(recommended = INSTALLED + 2)
        assertNotNull("a new recommendation is", home().snackbarText())
    }
}
