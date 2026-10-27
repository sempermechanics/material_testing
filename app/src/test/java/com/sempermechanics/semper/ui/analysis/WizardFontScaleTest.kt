package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.content.res.Configuration
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisCancel
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The wizard absorbs config changes so a solve is not cancelled by a
 * recreate, and so a system text-size change made while it was open was
 * ignored until it was reopened (readiness AE1V-015). Idle, it now rebuilds
 * for a font-scale or density change; busy, it keeps the run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WizardFontScaleTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val built = mutableListOf<ActivityController<StaticAnalysisActivity>>()
    private val realEngine = AnalysisCancel.toEngine

    // As WizardRestorePreviewTest: destroying a wizard hands a cancel to the engine.
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

    private fun wizard(): ActivityController<StaticAnalysisActivity> =
        Robolectric.buildActivity(StaticAnalysisActivity::class.java).also { built += it }.setup()

    private fun ActivityController<StaticAnalysisActivity>.change(edit: Configuration.() -> Unit) {
        val next = Configuration(get().resources.configuration).apply(edit)
        configurationChange(next)
        shadowOf(get().mainLooper).idle()
    }

    @Test
    fun `idle, a font-scale change rebuilds the wizard`() {
        val controller = wizard()
        val before = controller.get()

        val model = ViewModelProvider(before)[AnalysisViewModel::class.java]

        controller.change { fontScale = 2f }

        assertNotSame("recreated", before, controller.get())
        assertEquals(2f, controller.get().resources.configuration.fontScale)
        // A config-change rebuild: the wizard's state is the same ViewModel.
        assertSame(model, ViewModelProvider(controller.get())[AnalysisViewModel::class.java])
    }

    @Test
    fun `idle, a density change rebuilds the wizard`() {
        val controller = wizard()
        val before = controller.get()

        controller.change { densityDpi = densityDpi * 2 }

        assertNotSame("recreated", before, controller.get())
    }

    @Test
    fun `a rotation is still absorbed`() {
        val controller = wizard()
        val before = controller.get()

        controller.change {
            orientation = if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                Configuration.ORIENTATION_LANDSCAPE
            } else {
                Configuration.ORIENTATION_PORTRAIT
            }
        }

        assertSame(before, controller.get())
    }

    @Test
    fun `busy, a font-scale change keeps the run`() {
        val controller = wizard()
        val before = controller.get()
        before.chromeForTest().beginImport(onCancel = {})

        controller.change { fontScale = 2f }

        assertSame("not recreated mid-run", before, controller.get())
        assertEquals(true, controller.get().chromeForTest().isBusy)
    }
}
