package com.sempermechanics.semper.ui.viewer

import android.app.Application
import android.view.View
import android.view.ViewStub
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityResultViewerBinding
import com.sempermechanics.semper.databinding.LabResultsPanelBinding
import com.sempermechanics.semper.databinding.SheetSettingsUsedBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The lab Results panel is written once (`lab_results_panel`) and inflated
 * from a stub on both surfaces: the viewer's summary page and the Details sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LabResultsPanelTest {

    private lateinit var activity: AppCompatActivity

    @Before
    fun setUp() {
        val built = Robolectric.buildActivity(AppCompatActivity::class.java)
        built.get().setTheme(R.style.Theme_Semper)
        activity = built.setup().get()
    }

    private fun assertPanel(stub: ViewStub, inflatedId: Int) {
        assertEquals(R.layout.lab_results_panel, stub.layoutResource)
        val root = stub.inflate()
        assertEquals(inflatedId, root.id)
        val panel = LabResultsPanelBinding.bind(root)
        assertEquals(activity.getString(R.string.results_title), panel.tvLabResultsTitle.text.toString())
        assertEquals(R.id.btnRangeWhole, panel.toggleLabResultsRange.checkedButtonId)
        assertFalse(panel.labResultsRangeClip.isVisible)
        assertFalse(panel.btnLabResultsAdjust.isVisible)
        assertFalse(panel.tvLabResultsText.isVisible)
    }

    @Test
    fun `the summary page inflates the shared panel`() {
        val viewer = ActivityResultViewerBinding.inflate(activity.layoutInflater)

        assertFalse(viewer.summaryResults.isVisible)
        assertPanel(viewer.summaryResultsStub, R.id.summaryResultsPanel)
    }

    @Test
    fun `the Details sheet inflates the same panel`() {
        val sheet = SheetSettingsUsedBinding.inflate(activity.layoutInflater)

        assertFalse(sheet.stressStrainSection.isVisible)
        assertPanel(sheet.stressStrainStub, R.id.stressStrainPanel)
        assertEquals(
            activity.resources.getDimensionPixelSize(R.dimen.lab_results_plot_height),
            sheet.root.findViewById<View>(R.id.plotLabResults).layoutParams.height,
        )
    }
}
