package com.sempermechanics.semper.ui.examples

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.examples.ExampleCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The set's sheet as the student reads it (ADR-031). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ExampleDetailSheetTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun setup(id: String) =
        ExampleDetailSheet.setupText(app, requireNotNull(ExampleCatalog.find(app.assets, id)))

    @Test
    fun `the frame count agrees with its noun`() {
        val pair = setup("dice_oht_cfrp")
        assertTrue(pair, "1 deformed frame," in pair)
        val pmma = setup("pmma_bending")
        assertTrue(pmma, "33 deformed frames," in pmma)
    }
}
