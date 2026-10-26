package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.CacheJanitor
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sweep graph PNG is an export like the viewer's (readiness AE1-014): Save
 * graph goes through the same Share check, greyed and explained when the
 * licence lacks sharing, as the viewer's Share button is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VsgLatticeShareGateTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var built: ActivityController<VsgLatticeActivity>? = null

    @Before
    fun setUp() = AppRemoteConfig.clear(app)

    @After
    fun tearDown() {
        built?.close()
        AppRemoteConfig.clear(app)
    }

    private fun open(): VsgLatticeActivity {
        val fixture = VsgLatticeFixture(temp.newFolder("sweep")).apply { writeFrames() }
        val lattice = Robolectric.buildActivity(VsgLatticeActivity::class.java, fixture.intent(app))
            .also { built = it }.setup().get()
        idleUntil("the strain plot to load") {
            lattice.findViewById<View>(R.id.strainPlotSection).visibility == View.VISIBLE
        }
        return lattice
    }

    private fun VsgLatticeActivity.crispToast(): String? =
        findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<View>("semper_crisp_toast")
            ?.findViewById<TextView>(R.id.tvToast)
            ?.text
            ?.toString()

    @Test
    fun `on the demo Save graph is greyed and a tap explains the licence instead of sharing`() {
        val lattice = open()
        val save = lattice.findViewById<View>(R.id.btnSaveGraph)
        assertEquals(LicenseEntitlements.BLOCKED_ALPHA, save.alpha)

        save.performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(lattice.getString(R.string.license_lock_note), lattice.crispToast())
        assertNull("no share sheet", shadowOf(lattice).nextStartedActivity)
    }

    @Test
    fun `with sharing in the licence Save graph renders the PNG to share`() {
        AppRemoteConfig.apply(app, AppConfigDto(mode = "licensed", shareEnabled = true))
        val lattice = open()
        val save = lattice.findViewById<View>(R.id.btnSaveGraph)
        assertEquals(1f, save.alpha)

        save.performClick()

        // The PNG lands in the share cache, which the share sheet is handed
        // (Robolectric's FileProvider cannot serve it on Windows paths, so the
        // chooser itself is not asserted).
        val shareDir = CacheJanitor.shareDir(lattice.cacheDir)
        idleUntil("the graph PNG to be written") {
            shareDir.listFiles().orEmpty().any { it.name.startsWith("vsg_strain_graph_") }
        }
        assertNull(lattice.crispToast())
    }
}
