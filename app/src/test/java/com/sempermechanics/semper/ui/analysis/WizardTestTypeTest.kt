package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.content.Intent
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisCancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * The wizard is Semper's for plain 2D DIC; only the lab tests add to it. Here:
 * the strain window's ⓘ reads Semper's text, and Bending adds why its window
 * starts wider.
 *
 * Every wizard is destroyed after the test, with the engine's cancel swapped
 * for a no-op meanwhile, as in [WizardRestorePreviewTest] (TD-200).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardTestTypeTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val built = mutableListOf<ActivityController<StaticAnalysisActivity>>()
    private val realEngine = AnalysisCancel.toEngine

    @Before
    fun noEngine() {
        AnalysisCancel.toEngine = {}
    }

    @After
    fun tearDown() {
        built.asReversed().forEach { it.close() }
        AnalysisCancel.clear()
        AnalysisCancel.toEngine = realEngine
        WizardDraft.dirIn(app.filesDir).deleteRecursively()
    }

    private fun wizard(type: TestType?): StaticAnalysisActivity {
        val intent = Intent(app, StaticAnalysisActivity::class.java)
        type?.let { intent.putExtra(IntentKeys.TEST_TYPE, it.wireName) }
        return Robolectric.buildActivity(StaticAnalysisActivity::class.java, intent)
            .also { built += it }
            .setup()
            .get()
    }

    private fun strainInfoText(activity: StaticAnalysisActivity): String {
        activity.findViewById<android.view.View>(R.id.btnStrainInfo).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        return dialog.findViewById<TextView>(android.R.id.message)!!.text.toString()
    }

    @Test
    fun `plain 2D DIC's strain window help is Semper's`() {
        assertEquals(app.getString(R.string.info_strain_window), strainInfoText(wizard(TestType.DIC_2D)))
    }

    @Test
    fun `tensile keeps Semper's strain window help`() {
        assertEquals(app.getString(R.string.info_strain_window), strainInfoText(wizard(TestType.TENSILE)))
    }

    @Test
    fun `bending adds why its strain window starts wider`() {
        val expected = app.getString(R.string.info_strain_window) + " " +
            app.getString(R.string.info_strain_window_bending)
        assertEquals(expected, strainInfoText(wizard(TestType.BENDING)))
    }
}
