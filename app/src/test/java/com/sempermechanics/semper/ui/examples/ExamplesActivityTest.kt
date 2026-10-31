package com.sempermechanics.semper.ui.examples

import android.app.Application
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.examples.ExampleCatalog
import com.sempermechanics.semper.data.examples.ExampleDownloads
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** The example sets screen (ADR-031): a card per set, its sheet, Try it and Save to phone. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExamplesActivityTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val built = mutableListOf<ActivityController<ExamplesActivity>>()

    @get:Rule
    val clean = CleanAppState()

    @After
    fun tearDown() = built.asReversed().forEach { it.close() }

    private fun screen(): ExamplesActivity {
        val activity = Robolectric.buildActivity(ExamplesActivity::class.java).also { built += it }.setup().get()
        activity.canStart = { true }
        val list = activity.findViewById<android.view.ViewGroup>(R.id.examplesList)
        idleUntil("the sets are listed") { list.childCount > 2 }
        return activity
    }

    private fun card(activity: ExamplesActivity, index: Int): View =
        activity.findViewById<android.view.ViewGroup>(R.id.examplesList).getChildAt(2 + index)

    @Test
    fun `a card per set, in order, with its photo count and licence`() {
        val activity = screen()
        val list = activity.findViewById<android.view.ViewGroup>(R.id.examplesList)
        assertEquals(2 + 4, list.childCount)
        val steel = card(activity, 0)
        assertEquals("Steel strip in tension", steel.findViewById<TextView>(R.id.tvExampleTitle).text.toString())
        assertEquals("41 photos", steel.findViewById<TextView>(R.id.tvExampleMeta).text.toString())
        assertTrue(steel.findViewById<TextView>(R.id.tvExampleLicence).text.startsWith("CC BY 4.0"))
    }

    @Test
    fun `the sheet shows the setup, what to expect and the credit, and Try it opens the wizard`() {
        val activity = screen()
        card(activity, 1).performClick()
        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        val setup = sheet.findViewById<TextView>(R.id.tvDetailSetup)!!.text.toString()
        assertTrue(setup, "Span L 75 mm" in setup && "x ≈ 1170 px" in setup && "pmma_loads.csv, in N" in setup)
        val expected = sheet.findViewById<TextView>(R.id.tvDetailExpected)!!.text.toString()
        assertTrue(expected, "E from the graph: ≈ 1.98 GPa" in expected)
        val credit = sheet.findViewById<TextView>(R.id.tvDetailCredit)!!.text.toString()
        listOf("doi:10.5281/zenodo.1172068", "CC BY 4.0", "No endorsement").forEach { assertTrue(credit, it in credit) }

        sheet.findViewById<View>(R.id.btnDetailTry)!!.performClick()
        idleUntil("the wizard is started") { shadowOf(activity).peekNextStartedActivity() != null }
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(StaticAnalysisActivity::class.java.name, started.component?.className)
        assertEquals("pmma_bending", started.getStringExtra(IntentKeys.EXAMPLE_ID))
    }

    @Test
    fun `the source and licence buttons open their pages`() {
        val activity = screen()
        card(activity, 0).performClick()
        val sheet = ShadowDialog.getLatestDialog() as BottomSheetDialog
        sheet.findViewById<View>(R.id.btnDetailSource)!!.performClick()
        assertEquals("https://doi.org/10.5281/zenodo.18311953", shadowOf(activity).nextStartedActivity.data.toString())
        sheet.findViewById<View>(R.id.btnDetailLicence)!!.performClick()
        val licence = shadowOf(activity).nextStartedActivity.data.toString()
        assertEquals("https://creativecommons.org/licenses/by/4.0/", licence)
    }

    @Test
    fun `a set with more frames than the account allows is explained, not opened`() {
        AppSettings.setMaxFrames(app, 10, 0)
        val activity = screen()
        card(activity, 0).performClick()
        (ShadowDialog.getLatestDialog() as BottomSheetDialog).findViewById<View>(R.id.btnDetailTry)!!.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertTrue(dialog.isShowing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `a gate that refuses keeps the wizard closed`() {
        val activity = screen()
        activity.canStart = { false }
        card(activity, 2).performClick()
        (ShadowDialog.getLatestDialog() as BottomSheetDialog).findViewById<View>(R.id.btnDetailTry)!!.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `save to phone reports where the files went`() {
        val activity = screen()
        val viewModel = androidx.lifecycle.ViewModelProvider(activity)[ExamplesViewModel::class.java]
        val sink = RecordingSink()
        viewModel.downloads = { ExampleDownloads(app.assets, sink) }
        card(activity, 3).performClick()
        (ShadowDialog.getLatestDialog() as BottomSheetDialog).findViewById<View>(R.id.btnDetailSave)!!.performClick()
        idleUntil("the save reports") {
            (ShadowDialog.getLatestDialog() as? AlertDialog)?.isShowing == true
        }
        val set = requireNotNull(ExampleCatalog.find(app.assets, "dice_translation"))
        assertEquals(set.files, sink.names)
        val body = (ShadowDialog.getLatestDialog() as AlertDialog).findViewById<TextView>(android.R.id.message)!!.text
        assertTrue(body.toString(), "Download/semper examples/DICe translation/" in body)
    }

    /** Records the names written; everything succeeds. */
    private class RecordingSink : ExampleDownloads.Sink {
        val names = mutableListOf<String>()
        override fun exists(relativePath: String, name: String, size: Long) = false
        override fun create(relativePath: String, name: String, mimeType: String): android.net.Uri {
            names += name
            return android.net.Uri.parse("fake:$name")
        }
        override fun write(uri: android.net.Uri, source: java.io.InputStream) {
            source.readBytes()
        }
        override fun publish(uri: android.net.Uri) = Unit
        override fun discard(uri: android.net.Uri) = Unit
    }
}
