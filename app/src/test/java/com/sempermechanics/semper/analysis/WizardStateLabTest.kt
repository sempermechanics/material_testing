package com.sempermechanics.semper.analysis

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.mechanical.BeamEdgeTaps
import com.sempermechanics.semper.data.mechanical.CaptureStamp
import com.sempermechanics.semper.data.mechanical.LoadCsvParse
import com.sempermechanics.semper.data.mechanical.LoadMapping
import com.sempermechanics.semper.data.mechanical.MachineLoadCsv
import com.sempermechanics.semper.data.mechanical.SpecimenGeometry
import com.sempermechanics.semper.data.mechanical.TestType
import com.sempermechanics.semper.data.mechanical.TypedLoads
import com.sempermechanics.semper.data.prefs.WizardDraft
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.DraftRestore
import com.sempermechanics.semper.ui.analysis.wizard.WizardState
import com.sempermechanics.semper.ui.analysis.wizard.defCaptureStamps
import com.sempermechanics.semper.ui.analysis.wizard.defTimesAfterRefMs
import com.sempermechanics.semper.ui.analysis.wizard.editLab
import com.sempermechanics.semper.ui.analysis.wizard.lab
import com.sempermechanics.semper.ui.analysis.wizard.saveWizardState
import com.sempermechanics.semper.ui.analysis.wizard.setLoadLog
import com.sempermechanics.semper.ui.analysis.wizard.typedLoadsKg
import com.sempermechanics.semper.ui.analysis.wizard.withDefTimesAfterRefMs
import com.sempermechanics.semper.ui.analysis.wizard.withTypedLoadsKg
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The lab test's wizard inputs across a process death (ADR-005): the test
 * type, dimensions and taps through the saved-state Bundle; frame times,
 * capture times, typed loads and the load log through the [WizardDraft].
 * The rest of the wizard's state is `WizardStateTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WizardStateLabTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var frames: File
    private lateinit var draft: WizardDraft

    @Before
    fun setUp() {
        frames = File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply { mkdirs() }
        draft = WizardDraft(ctx)
        draft.clear()
    }

    @After
    fun tearDown() {
        frames.deleteRecursively()
        draft.clear()
    }

    /** A wizard with a reference and two photos, the first with an EXIF capture time. */
    private fun editedWizard(): AnalysisViewModel = AnalysisViewModel().apply {
        val paths = listOf("f1.png", "f2.png").map { File(frames, it).apply { writeBytes(byteArrayOf(1)) }.path }
        edit {
            it.copy(
                refBytes = REF,
                refSize = ImageSize(400, 300),
                refName = "ref.png",
                deformedFrames = listOf(
                    DeformedFrame(
                        paths[0],
                        "IMG_1.png",
                        100L,
                        ImageSize(400, 300),
                        captureTimeMs = PHOTO_T0 + 1_250L,
                        captureSubSec = true,
                    ),
                    DeformedFrame(paths[1], "IMG_2.png", Long.MAX_VALUE),
                ),
                lab = it.lab.copy(refCapture = CaptureStamp(PHOTO_T0, subSeconds = false)),
            )
        }
    }

    /** What the system hands back after the kill, with the draft the stop wrote. */
    private fun afterProcessDeath(before: AnalysisViewModel): AnalysisViewModel {
        draft.writeReference(before.inputs.value.refBytes)
        draft.writeMask(before.inputs.value.roiMaskBytes)
        draft.writeFrames(WizardState.encodeFrames(WizardState.frames(before)))
        val saved = before.saveWizardState()
        return AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }
    }

    @Test
    fun `capture times come back with the frames`() {
        val before = editedWizard()
        val after = afterProcessDeath(before)

        assertEquals(CaptureStamp(PHOTO_T0, subSeconds = false), after.lab.refCapture)
        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertEquals(listOf(CaptureStamp(PHOTO_T0 + 1_250L, subSeconds = true), null), after.defCaptureStamps)
    }

    @Test
    fun `capture times an older app saved come back with their precision unknown`() {
        val before = editedWizard()
        // An older app wrote no precision, and no frames fingerprint either.
        val oldFrames = WizardState.frames(before).copy(captureSubSec = emptyList())
        draft.writeReference(before.inputs.value.refBytes)
        draft.writeMask(before.inputs.value.roiMaskBytes)
        draft.writeFrames(WizardState.encodeFrames(oldFrames))
        val saved = before.saveWizardState().apply {
            remove("refCaptureSubSec")
            remove("framesFingerprint")
        }
        val after = AnalysisViewModel(SavedStateHandle(mapOf(WizardState.KEY to saved))).also { it.attachDraft(draft) }

        assertEquals(CaptureStamp(PHOTO_T0, subSeconds = null), after.lab.refCapture)
        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertEquals(listOf(CaptureStamp(PHOTO_T0 + 1_250L, subSeconds = null), null), after.defCaptureStamps)
    }

    @Test
    fun `a lost draft drops the capture times`() {
        val before = editedWizard()
        val after = afterProcessDeath(before)
        File(before.defFilePaths[1]).delete()

        assertEquals(DraftRestore.LOST, runBlocking { after.restoreDraft() })
        assertNull(after.lab.refCapture)
        assertTrue(after.defCaptureStamps.isEmpty())
    }

    @Test
    fun `a bending wizard keeps its dimensions, taps, frame times and load log`() {
        val geometry = SpecimenGeometry(
            spanMm = 80f,
            widthMm = 10f,
            thicknessMm = 4f,
            loadPoint = BeamEdgeTaps(topX = 200f, topY = 100f, bottomX = 200f, bottomY = 140f),
        )
        val before = editedWizard().apply {
            editLab {
                it.copy(
                    testType = TestType.BENDING,
                    crossSectionMm2 = 12.5f,
                    loadAxisX = false,
                    geometry = geometry,
                    // A clip sampled from 3 s in: the frames sit at 3.5 s and 4 s.
                    refVideoTimeMs = 3_000L,
                )
            }
            edit { it.withDefTimesAfterRefMs(listOf(500L, 1_000L)) }
            setLoadLog((MachineLoadCsv.parse(LOAD_LOG) as LoadCsvParse.Ok).csv, "run1.csv", LOAD_LOG)
            editLab { it.copy(loadLogStartS = 0.5f, typedLoadsEntry = TypedLoads.Entry.INCREMENTAL) }
        }
        draft.writeLoadLog(LOAD_LOG)
        val after = afterProcessDeath(before)

        assertEquals(TestType.BENDING, after.lab.testType)
        assertEquals(12.5f, after.lab.crossSectionMm2, 0f)
        assertFalse(after.lab.loadAxisX)
        assertEquals(geometry, after.lab.geometry)
        assertEquals("run1.csv", after.lab.loadCsvName)
        assertEquals(0.5f, after.lab.loadLogStartS, 0f)
        assertEquals(TypedLoads.Entry.INCREMENTAL, after.lab.typedLoadsEntry)
        assertEquals(3_000L, after.lab.refVideoTimeMs)

        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertEquals(listOf(500L, 1_000L), after.defTimesAfterRefMs)
        assertEquals(listOf(3_500L, 4_000L), after.inputs.value.deformedFrames.map { it.timeMs })
        assertEquals(before.lab.parsedLoadCsv, after.lab.parsedLoadCsv)
        assertEquals(2, after.lab.machineLoads?.matchedFrames)
    }

    @Test
    fun `typed hanger loads survive a process death`() {
        val before = editedWizard().apply {
            editLab { it.copy(testType = TestType.BENDING) }
            edit { it.withTypedLoadsKg(listOf(0.5f, null)) }
        }
        val after = afterProcessDeath(before)

        assertEquals(DraftRestore.RESTORED, runBlocking { after.restoreDraft() })
        assertEquals(listOf(0.5f, null), after.typedLoadsKg)
        assertEquals(LoadMapping.TYPED_KG, after.lab.machineLoads?.mapping)
        assertEquals(1, after.lab.machineLoads?.matchedFrames)
    }

    @Test
    fun `a frame list saved before typed loads existed still restores`() {
        val old = """{"paths":["a.png"],"names":["A.png"],"dates":[1],"widths":[4],"heights":[3]}"""
        val frames = Json { ignoreUnknownKeys = true }.decodeFromString(WizardState.Frames.serializer(), old)

        assertTrue(frames.typedLoadsKg.isEmpty())
        assertEquals(listOf("a.png"), frames.paths)
    }

    @Test
    fun `a load log missing from the draft loses the draft`() {
        val before = editedWizard().apply {
            setLoadLog((MachineLoadCsv.parse(LOAD_LOG) as LoadCsvParse.Ok).csv, "run1.csv", LOAD_LOG)
        }
        val after = afterProcessDeath(before)

        assertEquals(DraftRestore.LOST, runBlocking { after.restoreDraft() })
        assertNull(after.lab.parsedLoadCsv)
        assertEquals("", after.lab.loadCsvName)
    }

    private companion object {
        val REF = ByteArray(64) { it.toByte() }

        /** 2026-09-26 14:30:00 UTC. */
        const val PHOTO_T0 = 1_790_433_000_000L

        val LOAD_LOG = """
            Time (s),Load (N)
            0.0,0.0
            0.5,120.5
            1.0,241.0
            1.5,361.5
        """.trimIndent()
    }
}
