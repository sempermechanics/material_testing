package com.sempermechanics.semper.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.LabSession
import com.sempermechanics.semper.data.mechanical.MechanicalTestInputs
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.session.SessionRecordSettings
import com.sempermechanics.semper.data.session.SkippedNode
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.VsgLatticeActivity
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.snapshotRunInputs
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.SweepSession
import com.sempermechanics.semper.ui.viewer.ViewerArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [RunSpec] (ADR-004): the engine gets exactly what it got before the spec
 * existed, and the viewer a run opens shows the settings its saved session
 * records (TD-61).
 */
@RunWith(RobolectricTestRunner::class)
class RunSpecTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val debugDir = File("debug")
    private val mask = byteArrayOf(1, 0, 1)

    // The ROI the engine solves, after the inset of the one the user drew (0, 0, 2000, 435).
    private val resolved = intArrayOf(20, 20, 1960, 395)

    private val spec = RunSpec.of(
        subset = 21,
        step = 5,
        strainWindow = 15,
        roi = resolved,
        mask = mask,
        use6x6 = true,
        debugDir = debugDir,
    )

    @Test
    fun `the spec holds exactly the values the engine is handed`() {
        assertEquals(DicParams(subset = 21, step = 5, strainWindow = 15), spec.params)
        assertEquals(Roi(resolved[0], resolved[1], resolved[2], resolved[3]), spec.roi)
        assertEquals(true, spec.use6x6)
        assertSame(debugDir, spec.debugDir)
        assertSame("the engine reads the mask the user drew, not a copy", mask, spec.mask)
    }

    @Test
    fun `the saved record gets the settings the engine solved with`() {
        // What BatchAnalysis built from the params before ADR-004.
        val before = SessionRecordSettings(
            subset = 21,
            step = 5,
            strainWin = 15,
            roiX = resolved[0],
            roiY = resolved[1],
            roiW = resolved[2],
            roiH = resolved[3],
            use6x6 = true,
        )

        assertEquals(before, spec.recordSettings())
    }

    @Test
    fun `no mask is an empty one, as the engine expects`() {
        val noMask = RunSpec.of(21, 5, 15, resolved, mask = null, use6x6 = false, debugDir = null)

        assertEquals(0, noMask.mask.size)
    }

    @Test
    fun `a sweep's first combination stands in for the scalar settings`() {
        val plan = listOf(SweepStudy.Point(31, 10, 9), SweepStudy.Point(41, 14, 15))
        val sweep = RunSpec.Sweep(plan, listOf("a", "b"), lineCutHorizontal = false, frameIndex = 1)

        val spec = RunSpec.sweep(sweep, Roi.fromXywh(resolved)!!, mask, use6x6 = false, debugDir = null)

        assertEquals(31, spec.params.subset)
        assertEquals(10, spec.params.step)
        // 9 points at step 10: the engine gets the VSG, (9 - 1) * 10 + 1 px.
        assertEquals(81, spec.params.strainWindow)
        assertEquals(sweep, spec.sweep)
    }

    // --------------------------------------------------------------- TD-61

    private fun savedRecord(settings: SessionRecordSettings) = sessionRecord(
        id = "f0f0406f-8c2",
        name = "steel_24",
        frameCount = 2,
        subset = settings.subset,
        step = settings.step,
        strainWindow = settings.strainWin,
        imgW = 2000,
        imgH = 435,
        roiX = settings.roiX,
        roiY = settings.roiY,
        roiW = settings.roiW,
        roiH = settings.roiH,
        refPath = "/sessions/f0f0406f-8c2/reference.png",
        refName = "steel_24.png",
        sessionDir = "/sessions/f0f0406f-8c2",
    ).copy(plannedFrameCount = 2)

    @Test
    fun `the viewer opens on the settings and ROI the run solved, as its saved row records them`() {
        // The row a run saves records its spec (BatchAnalysis), and the run's
        // result and Home both open from that row: one builder, one answer.
        val args = ViewerArgs.of(savedRecord(spec.recordSettings()))

        assertEquals(resolved.toList(), listOf(args.roiX, args.roiY, args.roiW, args.roiH))
        assertEquals(listOf(21, 5, 15), listOf(args.subsetSize, args.step, args.strainWindow))
        assertEquals("f0f0406f-8c2", args.sessionLocalId)
        assertEquals(2, args.plannedFrames)
        assertEquals("/sessions/f0f0406f-8c2", args.batchDirPath)
    }

    @Test
    fun `the lab test reaches a result opened right after a run, as Home would open it`() {
        // TD-301, TD-305: a run's result and Home both open ViewerArgs.of the
        // saved row, so the lab test must ride the row: one LabSession, the
        // record's own, and the loads the run recorded.
        val geometry = SpecimenGeometry(80f, 10.5f, 4f, BeamEdgeTaps(100f, 40f, 112f, 90f))
        val mechanical = MechanicalTestInputs(
            testType = "bending",
            loadAxisX = false,
            geometry = geometry,
            loadsN = listOf(0f, 100f),
        )
        // What SessionRepository.buildSessionRecord saves from spec.mechanical.
        val record = savedRecord(spec.copy(mechanical = mechanical).recordSettings()).copy(
            testType = mechanical.testType,
            loadAxisX = mechanical.loadAxisX,
            geometry = mechanical.geometry,
            loadsN = mechanical.loadsN,
            defNames = listOf("f1.png", "f2.png"),
        )

        val args = ViewerArgs.of(record)

        assertEquals(LabSession("bending", loadAxisX = false, geometry = geometry), args.labSession)
        assertEquals(record.labSession, args.labSession)
        assertEquals(listOf(0f, 100f), args.loadsN)
    }

    @Test
    fun `a sweep that solved nothing shows its plan's settings`() {
        val plan = listOf(SweepStudy.Point(31, 10, 9))
        val sweep = RunSpec.Sweep(plan, listOf("a"), lineCutHorizontal = false, frameIndex = 0)
        val sweepSpec = RunSpec.sweep(sweep, Roi.fromXywh(resolved)!!, null, use6x6 = false, debugDir = null)
        val vm = AnalysisViewModel().apply {
            edit {
                it.copy(
                    refBytes = ByteArray(8),
                    refSize = ImageSize(2000, 435),
                    refName = "steel_24.png",
                    deformedFrames = listOf(DeformedFrame("/frames/a.png", "a.png")),
                )
            }
        }
        val result = SweepStudyRunner.SweepResult(runs = emptyList(), firstMetrics = null, engineErrorCode = -3)
        val run = SweepSession("x", File("/sessions/x"), vm.snapshotRunInputs(), result, sweepSpec, 0)

        val args = ViewerArgs.forFailedSweep(run)

        assertEquals(31, args.subsetSize)
        assertEquals(10, args.step)
        assertEquals(81, args.strainWindow) // the VSG of 9 points at step 10
        assertEquals("the sweep's own code", -3, args.stopCode)
        assertEquals(false, args.sweep?.lineCutHorizontal)
        assertEquals(listOf(SkippedNode(31, 10, 81, -3)), SkippedNode.decodeJson(args.sweep?.skippedJson))
        assertEquals(VsgLatticeActivity::class.java.name, args.toIntent(context).component?.className)
    }

    @Test
    fun `an incremental run records how often its reference moved`() {
        val incremental = spec.copy(refUpdateInterval = 5)

        assertEquals(true, incremental.incremental)
        assertEquals(5, incremental.recordSettings().refUpdateInterval)
        assertEquals("a fixed run records 0", 0, spec.recordSettings().refUpdateInterval)
    }

    @Test
    fun `a sweep solves one frame pair, so it never moves the reference`() {
        val plan = listOf(SweepStudy.Point(31, 10, 9))
        val sweep = RunSpec.Sweep(plan, listOf("a"), lineCutHorizontal = false, frameIndex = 0)
        val spec = RunSpec.sweep(sweep, Roi.fromXywh(resolved)!!, mask, use6x6 = false, debugDir = null)
            .copy(refUpdateInterval = 2)

        assertEquals(false, spec.incremental)
        assertEquals(0, spec.recordSettings().refUpdateInterval)
    }
}
