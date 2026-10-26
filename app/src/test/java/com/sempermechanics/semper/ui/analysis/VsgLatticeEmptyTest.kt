package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.graphics.Color
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.sempermechanics.semper.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The lattice opened with no sweep says there is nothing to show; it used to say
 * every combination had failed (readiness AE1V-020).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VsgLatticeEmptyTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var built: ActivityController<VsgLatticeActivity>? = null

    @After
    fun tearDown() {
        built?.close()
    }

    @Test
    fun `no sweep shows an empty state, not a failure`() {
        val lattice = Robolectric.buildActivity(VsgLatticeActivity::class.java).also { built = it }.setup().get()
        val summary = lattice.findViewById<TextView>(R.id.tvLatticeSummary)
        assertEquals(app.getString(R.string.vsg_lattice_empty), summary.text.toString())
        assertFalse(summary.isClickable)
    }

    /**
     * With nothing to show, Save graph can no more act than View can, and it looks
     * it: its label and outline were single colours, so disabled it still looked
     * enabled beside a greyed View (readiness AE1V-024).
     */
    @Test
    fun `no sweep shows Save graph disabled, as View is`() {
        val lattice = Robolectric.buildActivity(VsgLatticeActivity::class.java).also { built = it }.setup().get()
        val save = lattice.findViewById<MaterialButton>(R.id.btnSaveGraph)
        assertFalse("View", lattice.findViewById<MaterialButton>(R.id.btnView).isEnabled)
        assertFalse("Save graph", save.isEnabled)

        val ink = Color.alpha(ContextCompat.getColor(lattice, R.color.viewer_action_ink))
        val label = Color.alpha(save.currentTextColor)
        val outline = Color.alpha(requireNotNull(save.strokeColor).getColorForState(save.drawableState, 0))
        assertTrue("Save graph's label looks enabled (alpha $label of $ink)", label < ink)
        assertTrue("Save graph's outline looks enabled (alpha $outline of $ink)", outline < ink)
    }
}
