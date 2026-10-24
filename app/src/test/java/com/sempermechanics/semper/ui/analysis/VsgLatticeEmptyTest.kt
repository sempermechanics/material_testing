package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
