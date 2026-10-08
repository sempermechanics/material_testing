package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.widget.ImageView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisCancel
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
 * The wizard rebuilt after a process death (ADR-005) shows the reference it
 * had. A RAW/DNG reference is held as a headerless RGBA blob, which the
 * native decoder cannot read, so the restore must preview it the way the ROI
 * studio does. It used to hand the blob to OpenCV and come back empty.
 *
 * The blob is previewed on the JVM, so no native library is needed here; a
 * path that reached for the native decoder fails this test outright.
 *
 * Both wizards are destroyed after the test: one left open keeps its frame
 * list registered with the main thread's GapWorker, and it and its themes
 * outlive the test (TD-200). Destroying cancels the run, which would hand
 * the flag to the engine, so the engine is swapped for a no-op meanwhile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WizardRestorePreviewTest {

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

    private fun wizard(): ActivityController<StaticAnalysisActivity> =
        Robolectric.buildActivity(StaticAnalysisActivity::class.java).also { built += it }

    private fun await(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    @Test
    fun `a RAW reference has its thumbnail back after a process death`() {
        val raw = ByteArray(W * H * 4) { (it % 251).toByte() }
        val before = wizard().setup()
        val vm = ViewModelProvider(before.get())[AnalysisViewModel::class.java]
        vm.applyNewReference(raw, "shot.dng", ImageSize(W, H))
        val draft = WizardDraft(app)
        await("the draft's reference") { draft.readReference()?.size == raw.size }

        val state = Bundle()
        before.saveInstanceState(state)
        await("the draft's frame list") { draft.readFrames() != null }

        // A new Activity and view model, built from the saved state alone.
        val after = wizard().setup(state).get()
        val thumb = after.findViewById<ImageView>(R.id.imgRefThumb)
        await("the reference thumbnail") { thumb.drawable != null }

        assertNotNull(thumb.drawable)
        assertEquals("shot.dng", after.findViewById<android.widget.TextView>(R.id.tvRefName).text.toString())
    }

    private companion object {
        const val W = 64
        const val H = 48
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
