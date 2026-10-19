package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Application
import android.graphics.Bitmap
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.getRoi
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.sweep.SweepRanges
import com.sempermechanics.semper.ui.analysis.sweep.SweepSetupController
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The value types the wizard will adopt read and write exactly the saved
 * state [WizardState] writes today: the `"roi"` array, the `"sweepRanges"`
 * array and the draft's frame list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardValueTypesTest {

    private fun wizard(): AnalysisViewModel = AnalysisViewModel().apply {
        edit {
            it.copy(
                refSize = ImageSize(400, 300),
                hasCustomRoi = true,
                roi = Roi(10, 20, 300, 200),
                deformedFrames = listOf(
                    DeformedFrame("/c/f1.png", "IMG_1.png", 100L, ImageSize(400, 300)),
                    DeformedFrame("/c/f2.png", "IMG_2.png", Long.MAX_VALUE),
                    DeformedFrame("/c/f3.png", "IMG_3.png", 50L, ImageSize(400, 300)),
                ),
                sweepRanges = SweepRanges(21, 61, 9, 31, subsetSamples = 4, strainWinSamples = 5, stepDenominator = 4),
            )
        }
    }

    @Test
    fun `one sweep axis edits as a copy of the view model's sweep ranges`() {
        val vm = wizard()
        val before = vm.inputs.value.sweepRanges
        vm.edit { it.copy(sweepRanges = it.sweepRanges.copy(strainWinSamples = 7)) }
        assertEquals(before.copy(strainWinSamples = 7), vm.inputs.value.sweepRanges)
    }

    @Test
    fun `the saved roi array reads back as the view model's ROI`() {
        val vm = wizard()
        val saved = WizardState.save(vm, "")
        assertEquals(Roi(10, 20, 300, 200), saved.getRoi("roi"))
    }

    @Test
    fun `sweep ranges pack exactly as the saved state does and restore the same fields`() {
        val vm = wizard()
        val saved = WizardState.save(vm, "")
        assertEquals(saved.getIntArray("sweepRanges")!!.toList(), vm.inputs.value.sweepRanges.toIntArray().toList())

        val restored = AnalysisViewModel()
        WizardState.restoreScalars(restored, saved)
        assertEquals(vm.inputs.value.sweepRanges, restored.inputs.value.sweepRanges)
        assertEquals(vm.inputs.value.sweepRanges, SweepRanges.fromIntArray(saved.getIntArray("sweepRanges")))
    }

    @Test
    fun `sweep ranges reject a packed array of the wrong length, as the restore does`() {
        assertNull(SweepRanges.fromIntArray(null))
        assertNull(SweepRanges.fromIntArray(IntArray(6)))
        assertNull(SweepRanges.fromIntArray(IntArray(8)))
    }

    @Test
    fun `unseeded ranges are the view model's defaults`() {
        assertEquals(SweepRanges.UNSEEDED, AnalysisViewModel().inputs.value.sweepRanges)
    }

    /** Only [SweepSetupController.Callbacks.maxSubsetForRoi] matters to `currentPlan`. */
    private class Ceiling(var max: Int) : SweepSetupController.Callbacks {
        override fun clearRunStatus() = Unit
        override fun goToStep(step: WizardStep, animate: Boolean) = Unit
        override fun updateWizardChrome() = Unit
        override fun checkReady() = Unit
        override fun commitParamFields() = Unit
        override fun startSweep() = Unit
        override fun currentSubsetSize(): Int = 21
        override fun maxSubsetForRoi(): Int = max
        override fun refPreviewBitmap(): Bitmap? = null
        override fun renderParamField(field: EditText, value: Int) = Unit
        override fun confirmOpenFaq(url: String) = Unit
    }

    /**
     * `SweepSetupController.currentPlan` as it was before it called [SweepRanges.plan]:
     * the body verbatim, its ceiling passed in rather than read from the callbacks.
     */
    private fun legacyCurrentPlan(viewModel: AnalysisViewModel, ceiling: Int): List<SweepStudy.Point> {
        if (viewModel.inputs.value.sweepRanges.subsetMin > ceiling) return emptyList()
        return SweepStudy.plan(
            subsetMin = viewModel.inputs.value.sweepRanges.subsetMin,
            subsetMax = viewModel.inputs.value.sweepRanges.subsetMax.coerceAtMost(ceiling),
            subsetSamples = viewModel.inputs.value.sweepRanges.subsetSamples,
            strainWinMin = viewModel.inputs.value.sweepRanges.strainWinMin,
            strainWinMax = viewModel.inputs.value.sweepRanges.strainWinMax,
            strainWinSamples = viewModel.inputs.value.sweepRanges.strainWinSamples,
            stepDenominator = viewModel.inputs.value.sweepRanges.stepDenominator,
        )
    }

    @Test
    fun `currentPlan and plan match the old inline plan for ceilings below, inside and above the range`() {
        val vm = wizard()
        val ceiling = Ceiling(0)
        // currentPlan never touches the views, so the helper is never set up.
        val helper = SweepSetupController(AppCompatActivity(), vm, ceiling)
        for (max in listOf(1, 20, 21, 22, 41, 60, 61, 62, 101, 301)) {
            ceiling.max = max
            val expected = legacyCurrentPlan(vm, max)
            assertEquals("currentPlan, ceiling $max", expected, helper.currentPlan())
            assertEquals("plan, ceiling $max", expected, vm.inputs.value.sweepRanges.plan(subsetCeiling = max))
        }
        assertEquals(emptyList<SweepStudy.Point>(), vm.inputs.value.sweepRanges.plan(subsetCeiling = 20))
        assertEquals(41, vm.inputs.value.sweepRanges.plan(subsetCeiling = 41).maxOf { it.subset })
    }

    @Test
    fun `the view model's frame lists are its deformed frames, index-aligned`() {
        val vm = wizard()
        assertEquals(listOf("/c/f1.png", "/c/f2.png", "/c/f3.png"), vm.defFilePaths)
        assertEquals(listOf("IMG_1.png", "IMG_2.png", "IMG_3.png"), vm.defOriginalNames)
        assertEquals(listOf(100L, Long.MAX_VALUE, 50L), vm.defFrameDates)
        // A frame with no measured size has no entry.
        assertEquals(mapOf("/c/f1.png" to (400 to 300), "/c/f3.png" to (400 to 300)), vm.defFrameSizes)
        assertEquals(3, vm.inputs.value.deformedFrames.size)
    }

    @Test
    fun `draft frame list converts both ways exactly as WizardState writes and restores it`() {
        val vm = wizard()
        val draftFrames = WizardState.frames(vm)
        assertEquals(listOf(400, -1, 400), draftFrames.widths)
        val asFrames = draftFrames.toDeformedFrames()
        assertEquals(vm.inputs.value.deformedFrames, asFrames)
        assertEquals(draftFrames, wizardFramesOf(asFrames))
    }

    @Test
    fun `a draft frame list with short or unmeasured entries pads rather than drops frames`() {
        val draft = WizardState.Frames(
            paths = listOf("a", "b"),
            names = listOf("A"),
            widths = listOf(0, 5),
            heights = listOf(3, 5),
        )
        val frames = draft.toDeformedFrames()
        assertEquals(listOf("A", ""), frames.map { it.name })
        assertEquals(listOf(DeformedFrame.UNKNOWN_DATE, DeformedFrame.UNKNOWN_DATE), frames.map { it.date })
        assertEquals(listOf(null, ImageSize(5, 5)), frames.map { it.size })
    }
}
