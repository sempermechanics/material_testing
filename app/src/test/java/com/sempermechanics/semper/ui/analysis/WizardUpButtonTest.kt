package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.WizardDraft
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

/**
 * The wizard's Up arrow is named for TalkBack (readiness AE1V-007); it used to be
 * an unlabelled image button on every step. Its title may wrap to a second line
 * rather than read "New analy…" at font scale 2.0 (AE1V-001).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardUpButtonTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var built: ActivityController<StaticAnalysisActivity>? = null
    private val realEngine = AnalysisCancel.toEngine

    @Before
    fun noEngine() {
        AnalysisCancel.toEngine = {}
    }

    @After
    fun tearDown() {
        built?.close()
        AnalysisCancel.clear()
        AnalysisCancel.toEngine = realEngine
        WizardDraft.dirIn(app.filesDir).deleteRecursively()
    }

    @Test
    fun `the wizard's up arrow is named`() {
        val wizard = Robolectric.buildActivity(StaticAnalysisActivity::class.java).also { built = it }.setup().get()
        val toolbar = wizard.findViewById<Toolbar>(R.id.toolbar)
        assertEquals(app.getString(R.string.back), toolbar.navigationContentDescription)
    }

    @Test
    fun `the wizard's title may take two lines`() {
        val wizard = Robolectric.buildActivity(StaticAnalysisActivity::class.java).also { built = it }.setup().get()
        val toolbar = wizard.findViewById<Toolbar>(R.id.toolbar)
        val title = (0 until toolbar.childCount).map(toolbar::getChildAt).filterIsInstance<TextView>()
            .first { it.text == toolbar.title }
        assertEquals(2, title.maxLines)
    }
}
