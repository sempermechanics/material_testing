package com.sempermechanics.semper.ui.common

import android.app.Application
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * On a tablet the main screens keep a readable column instead of stretching a
 * phone layout edge to edge (readiness AE1-019): Home's list, Settings, sign-in
 * and the wizard pages are inset by content_side_inset, which is 0 on a phone
 * and grows at 600 / 840 / 1240 dp so the column stays near 600 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LargeScreenWidthTest {

    private val themed get() = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Application>(),
        R.style.Theme_Semper,
    )

    private fun insetOf(layout: Int, id: Int): Int {
        val root = LayoutInflater.from(themed).inflate(layout, LinearLayout(themed), false)
        val lp = root.findViewById<View>(id).layoutParams as ViewGroup.MarginLayoutParams
        val name = themed.resources.getResourceEntryName(id)
        assertEquals("start and end inset of $name", lp.marginStart, lp.marginEnd)
        return lp.marginStart
    }

    private fun columns(): Map<String, Int> = mapOf(
        "home list" to insetOf(R.layout.activity_home, R.id.swipeRefresh),
        "settings" to insetOf(R.layout.activity_settings, R.id.settingsSections),
        "sign-in" to insetOf(R.layout.activity_auth, R.id.authColumn),
        "wizard images" to insetOf(R.layout.activity_static_analysis, R.id.contentColumn),
        "wizard parameters" to insetOf(R.layout.wizard_step_settings, R.id.settingsColumn),
        "wizard sweep" to insetOf(R.layout.wizard_step_sweep, R.id.sweepColumn),
    )

    private fun dp(value: Int) = (value * themed.resources.displayMetrics.density).toInt()

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun `a phone keeps the full width`() {
        assertEquals(columns().mapValues { 0 }, columns())
    }

    @Test
    @Config(qualifiers = "w840dp-h1200dp")
    fun `a large tablet insets the main screens to a centred column`() {
        assertEquals(columns().mapValues { dp(TABLET_INSET_DP) }, columns())
    }

    private companion object {
        /** (840 - 600) / 2: a 600 dp column on an 840 dp screen. */
        const val TABLET_INSET_DP = 120
    }
}
