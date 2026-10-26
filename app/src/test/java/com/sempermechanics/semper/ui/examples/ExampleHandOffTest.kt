package com.sempermechanics.semper.ui.examples

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.examples.ExampleAssetProvider
import com.sempermechanics.semper.data.examples.ExampleCatalog
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import com.sempermechanics.semper.ui.analysis.rerun.LabRerun
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Try it's Intent: the wizard on a bundled set as if its files were picked (ADR-031). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExampleHandOffTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun set(id: String) = requireNotNull(ExampleCatalog.find(app.assets, id))

    @Test
    fun `bending carries the photos, the log, the beam and the ROI`() {
        val set = set("pmma_bending")
        val intent = ExampleHandOff.intent(app, set)
        assertEquals(StaticAnalysisActivity::class.java.name, intent.component?.className)
        assertEquals("bending", intent.getStringExtra(IntentKeys.TEST_TYPE))
        assertEquals("pmma_bending", intent.getStringExtra(IntentKeys.EXAMPLE_ID))
        fun uri(file: String) = ExampleAssetProvider.uriFor(app, "pmma_bending", file).toString()
        assertEquals(uri("pmma_00.png"), intent.getStringExtra(IntentKeys.PICKED_REF_URI))
        val deformed = intent.getStringArrayListExtra(IntentKeys.PICKED_DEF_URIS)!!
        assertEquals(33, deformed.size)
        assertEquals(uri("pmma_33.png"), deformed.last())
        assertEquals(uri("pmma_loads.csv"), intent.getStringExtra(IntentKeys.PICKED_LOAD_CSV_URI))
        assertArrayEquals(intArrayOf(59, 69, 2259, 509), intent.getIntArrayExtra(IntentKeys.EXAMPLE_ROI))
        assertArrayEquals(intArrayOf(2380, 1228), intent.getIntArrayExtra(IntentKeys.EXAMPLE_IMAGE_SIZE))
        val lab = LabRerun.from(intent)!!
        assertEquals(TestType.BENDING, lab.testType)
        assertEquals(SpecimenGeometry(75f, 12f, 31f), lab.geometry)
        assertFalse("the student taps", lab.geometry.loadPoint.isSet)
    }

    @Test
    fun `tensile carries the area and the load axis`() {
        val lab = LabRerun.from(ExampleHandOff.intent(app, set("steel_tensile")))!!
        assertEquals(TestType.TENSILE, lab.testType)
        assertEquals(12.5f, lab.crossSectionMm2)
        assertEquals(true, lab.loadAxisX)
    }

    @Test
    fun `2D DIC carries the photos and the ROI, and no lab test`() {
        val intent = ExampleHandOff.intent(app, set("dice_oht_cfrp"))
        assertEquals("dic2d", intent.getStringExtra(IntentKeys.TEST_TYPE))
        assertEquals(1, intent.getStringArrayListExtra(IntentKeys.PICKED_DEF_URIS)!!.size)
        assertNull(LabRerun.from(intent))
        assertNull(intent.getStringExtra(IntentKeys.PICKED_LOAD_CSV_URI))
        assertFalse(intent.hasExtra(IntentKeys.CROSS_SECTION_MM2))
    }
}
