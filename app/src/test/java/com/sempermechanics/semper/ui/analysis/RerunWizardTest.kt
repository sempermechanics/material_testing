package com.sempermechanics.semper.ui.analysis

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.navigation.IntentKeys
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.recommend.SubsetRecommendationController
import com.sempermechanics.semper.ui.analysis.rerun.keepBatchRerun
import com.sempermechanics.semper.ui.analysis.rerun.keepSweepRerun
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.snapshotRunInputs
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisCancel
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.WizardStep
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor

/**
 * Home's Re-run opens the wizard on a new analysis copied from a saved one:
 * its photos in the wizard's own import, its settings on the sliders, on the
 * parameters page and ready to Compute. The reference is a RAW (RGBA) blob,
 * which previews on the JVM, so no native library is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RerunWizardTest {

    @get:Rule
    val clean = CleanAppState()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var built: ActivityController<StaticAnalysisActivity>? = null
    private val realEngine = AnalysisCancel.toEngine
    private val realMeasure = SubsetRecommendationController.engineThread

    /** Speckle measurements queue here and never run: there is no native library on the JVM. */
    private val measurements = mutableListOf<Runnable>()

    @Before
    fun noEngine() {
        AnalysisCancel.toEngine = {}
        SubsetRecommendationController.engineThread = { Executor { measurements += it }.asCoroutineDispatcher() }
    }

    @After
    fun tearDown() {
        built?.close()
        AnalysisCancel.clear()
        AnalysisCancel.toEngine = realEngine
        SubsetRecommendationController.engineThread = realMeasure
        WizardDraft.dirIn(app.filesDir).deleteRecursively()
        File(app.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).deleteRecursively()
    }

    private fun await(what: String, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private val params = DicParams(subset = 41, step = 12, strainWindow = SweepStudy.vsgFor(7, 12))

    /** A saved single run of three frames in session `saved`, with its Re-run inputs. */
    private fun savedRun(): File {
        val size = ImageSize(W, H)
        val dir = SessionStore.dirFor(app, "saved")
        val raw = SessionLayout(dir).rawDeformedDir.apply { mkdirs() }
        val frames = (0 until 3).map { File(raw, "def_$it.png").apply { writeText("image $it") } }
        val wizard = AnalysisViewModel().apply {
            applyNewReference(ByteArray(W * H * 4) { (it % 251).toByte() }, "ref.dng", size)
            deformedFrames = frames.map { DeformedFrame(it.absolutePath, it.name, size = size) }
        }
        val spec = RunSpec.of(params, Roi.insetFullFrame(size, params.subset), null, use6x6 = true, debugDir = null)
        keepBatchRerun(dir, wizard.snapshotRunInputs(), spec, frames.map { it.absolutePath }.toMutableList())
        File(dir, "frame_0000.dat").writeText("dat")
        assertTrue(SessionStore.upsert(app, sessionRecord(id = "saved", sessionDir = dir.absolutePath), true))
        return dir
    }

    @Test
    fun `Re-run opens a new analysis on the parameters page with the saved settings`() {
        val dir = savedRun()
        val intent = Intent(app, StaticAnalysisActivity::class.java).putExtra(IntentKeys.RERUN_SESSION_ID, "saved")

        val wizard = Robolectric.buildActivity(StaticAnalysisActivity::class.java, intent).also { built = it }
            .setup().get()
        val vm = ViewModelProvider(wizard)[AnalysisViewModel::class.java]
        await("the copied frames") { vm.defCount == 3 && vm.step == WizardStep.SETTINGS }

        assertEquals("ref.dng", vm.refName)
        assertEquals(listOf("def_0.png", "def_1.png", "def_2.png"), vm.defOriginalNames)
        assertTrue(vm.defFilePaths.none { File(it).canonicalPath.startsWith(dir.canonicalPath) })
        assertEquals(params.subset, wizard.findViewById<Slider>(R.id.sliderSubsetSize).value.toInt())
        assertEquals(params.step, wizard.findViewById<Slider>(R.id.sliderStepSize).value.toInt())
        assertEquals(7, wizard.findViewById<Slider>(R.id.sliderStrainWindow).value.toInt())
        assertNull("nothing names the saved analysis", vm.workingLocalId)
        assertTrue("ready to Compute", vm.isReadyToCompute())
        assertTrue("the parameters page is shown", wizard.findViewById<View>(R.id.sliderSubsetSize).isShown)
        assertEquals("the speckle measurement was asked for", 1, measurements.size)
    }

    @Test
    fun `a sweep's Re-run opens on the sweep page with its ranges as the user's own`() {
        val size = ImageSize(W, H)
        val dir = SessionStore.dirFor(app, "sweep")
        val frame = SessionLayout(dir).rawDeformed("def_0.png").apply {
            parentFile?.mkdirs()
            writeText("image")
        }
        val ranges = SweepRanges(15, 19, 3, 5, subsetSamples = 3, strainWinSamples = 2, stepDenominator = 3)
        val source = AnalysisViewModel().apply {
            applyNewReference(ByteArray(W * H * 4), "ref.dng", size)
            deformedFrames = listOf(DeformedFrame(frame.absolutePath, frame.name, size = size))
            sweepMode = true
            sweepRanges = ranges
            lineCutHorizontal = false
        }
        val plan = ranges.plan(subsetCeiling = 19)
        val spec = RunSpec.sweep(
            RunSpec.Sweep(plan, plan.map { "$it" }, lineCutHorizontal = false, frameIndex = 0),
            Roi.insetFullFrame(size, 19),
            null,
            use6x6 = false,
            debugDir = null,
        )
        keepSweepRerun(dir, source.snapshotRunInputs(), spec, frame.name)
        File(dir, "frame_0000.dat").writeText("dat")
        assertTrue(SessionStore.upsert(app, sessionRecord(id = "sweep", sessionDir = dir.absolutePath), true))
        val intent = Intent(app, StaticAnalysisActivity::class.java).putExtra(IntentKeys.RERUN_SESSION_ID, "sweep")

        val wizard = Robolectric.buildActivity(StaticAnalysisActivity::class.java, intent).also { built = it }
            .setup().get()
        val vm = ViewModelProvider(wizard)[AnalysisViewModel::class.java]
        await("the sweep page") { vm.defCount == 1 && vm.step == WizardStep.SWEEP }

        assertTrue(vm.sweepMode)
        assertEquals(ranges, vm.sweepRanges)
        assertFalse(vm.lineCutHorizontal)
        fun checked(group: Int) = wizard.findViewById<MaterialButtonToggleGroup>(group).checkedButtonId
        assertEquals(R.id.rbModeSweep, checked(R.id.rgAnalysisMode))
        assertEquals(R.id.rbAxisY, checked(R.id.rgLineCutAxis))
    }

    private companion object {
        const val W = 64
        const val H = 48
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
