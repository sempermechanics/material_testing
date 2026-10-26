package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.examples.ExampleAssetProvider
import com.sempermechanics.semper.data.examples.ExampleCatalog
import com.sempermechanics.semper.data.mechanical.LoadUnit
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisCancel
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.lab
import com.sempermechanics.semper.ui.examples.ExampleHandOff
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Try it opens the wizard on a bundled set (ADR-031): the test, its
 * dimensions, the ROI drawn for the set and its load log are in before the
 * student does anything. The photos go through the ordinary picker hand-off,
 * which its own tests cover, so they are left off here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardExampleHandOffTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val built = mutableListOf<ActivityController<StaticAnalysisActivity>>()
    private val realEngine = AnalysisCancel.toEngine

    @Before
    fun setUp() {
        AnalysisCancel.toEngine = {}
        Robolectric.setupContentProvider(ExampleAssetProvider::class.java, ExampleAssetProvider.authority(app))
    }

    @After
    fun tearDown() {
        built.asReversed().forEach { it.close() }
        AnalysisCancel.clear()
        AnalysisCancel.toEngine = realEngine
        WizardDraft.dirIn(app.filesDir).deleteRecursively()
    }

    private fun wizardOn(id: String): AnalysisViewModel {
        val intent: Intent = ExampleHandOff.intent(app, requireNotNull(ExampleCatalog.find(app.assets, id)))
        intent.removeExtra(IntentKeys.PICKED_REF_URI)
        intent.removeExtra(IntentKeys.PICKED_DEF_URIS)
        val activity = Robolectric.buildActivity(StaticAnalysisActivity::class.java, intent)
            .also { built += it }
            .setup()
            .get()
        return ViewModelProvider(activity)[AnalysisViewModel::class.java]
    }

    @Test
    fun `bending opens with the beam, the ROI and the load log, taps left to the student`() {
        val viewModel = wizardOn("pmma_bending")
        assertEquals(TestType.BENDING, viewModel.lab.testType)
        assertEquals(SpecimenGeometry(75f, 12f, 31f), viewModel.lab.geometry)
        assertFalse(viewModel.lab.geometry.loadPoint.isSet)
        val inputs = viewModel.inputs.value
        assertEquals(ImageSize(2380, 1228), inputs.refSize)
        assertEquals(Roi(59, 69, 2259, 509), inputs.roi)
        assertTrue(inputs.hasCustomRoi)
        idleUntil("the load log is read") { viewModel.lab.parsedLoadCsv != null }
        val log = viewModel.lab.parsedLoadCsv!!
        assertEquals(33, log.rows)
        assertEquals(LoadUnit.N, log.unit)
    }

    @Test
    fun `tensile opens with the area and the load axis`() {
        val viewModel = wizardOn("steel_tensile")
        assertEquals(TestType.TENSILE, viewModel.lab.testType)
        assertEquals(12.5f, viewModel.lab.crossSectionMm2)
        assertTrue(viewModel.lab.loadAxisX)
        idleUntil("the load log is read") { viewModel.lab.parsedLoadCsv != null }
        assertEquals(LoadUnit.KN, viewModel.lab.parsedLoadCsv!!.unit)
    }

    @Test
    fun `the example extras are taken once`() {
        val viewModel = wizardOn("dice_oht_cfrp")
        val activity = built.single().get()
        assertEquals(TestType.DIC_2D, viewModel.lab.testType)
        assertEquals(Roi(40, 40, 320, 950), viewModel.inputs.value.roi)
        assertFalse(activity.intent.hasExtra(IntentKeys.EXAMPLE_ROI))
        assertFalse(activity.intent.hasExtra(IntentKeys.EXAMPLE_IMAGE_SIZE))
        // The set stays named on the Intent, as the test type does.
        assertEquals("dice_oht_cfrp", activity.intent.getStringExtra(IntentKeys.EXAMPLE_ID))
    }
}
