package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.data.integrity.ManifestJson
import com.sempermechanics.semper.data.integrity.RecordIntegrity
import com.sempermechanics.semper.data.integrity.RecordVerifier
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.QuotaBackendOn
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.RunKind
import com.sempermechanics.semper.ui.analysis.wizard.RunSession
import com.sempermechanics.semper.ui.analysis.wizard.SweepSession
import com.sempermechanics.semper.ui.analysis.wizard.endSweep
import com.sempermechanics.semper.ui.analysis.wizard.openRunSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException

/**
 * A run's record manifest (ADR-028) arrives with its results and leaves with
 * them: a run that is not cancelled commits one beside its frames, a cancel
 * or a kill before the commit leaves none, and dropping a session's results
 * drops it first. Robolectric has no Android Keystore, so the manifest here is
 * unsigned ("Not signed"); [RecordManifestTest] covers signing, and the
 * instrumented `RecordManifestProcessDeathTest` runs the real batch loop.
 */
@RunWith(RobolectricTestRunner::class)
class RecordManifestRunTest {

    @get:Rule
    val clean = CleanAppState()

    @get:Rule
    val quota = QuotaBackendOn()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val vm = AnalysisViewModel()
    private val importDir by lazy {
        File(ctx.cacheDir, FrameImportHelper.COMMITTED_DIR_NAME).apply {
            deleteRecursively()
            mkdirs()
        }
    }
    private val spec = RunSpec.of(
        DicParams(subset = 21, step = 5, strainWindow = 21),
        Roi(0, 0, REF_SIDE, REF_SIDE),
        null,
        false,
        null,
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 5, maxFilesPerSession = 600, maxFrames = 150))
        AccountCache.setQuota(ctx, used = 0)
        vm.edit {
            it.copy(
                refSize = it.refSize.copy(width = REF_SIDE, height = REF_SIDE),
                refName = "ref.png",
                refBytes = ByteArray(REF_SIDE * REF_SIDE * 4),
            )
        }
    }

    @After
    fun tearDown() {
        importDir.deleteRecursively()
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun importFrames(count: Int): List<File> {
        val files = (0 until count).map { i ->
            File(importDir, "000${i}_def_$i.png").apply { writeBytes(byteArrayOf(i.toByte(), 1, 2, 3)) }
        }
        val picked = files.map { f -> DeformedFrame(f.absolutePath, f.name.substringAfter('_')) }
        vm.edit { it.copy(deformedFrames = picked) }
        return files
    }

    /** What the batch loop stages for frames 0 until [reached], and the summary's sidecar it stages after. */
    private fun stageUpTo(session: RunSession, reached: Int) {
        for (i in 0 until reached) SessionPaths.frameDat(session.staging, i).writeText("frame $i")
        File(session.staging, SessionLayout.FIELD_RANGES).writeText("ranges")
    }

    private fun manifestOf(dir: File) = SessionLayout(dir).recordManifest

    @Test
    fun `a committed run writes one manifest beside its frames, covering each of them`() {
        importFrames(2)
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        stageUpTo(session, reached = 2)

        session.commitRecord(ctx, spec.recordSettings())

        assertFalse("the staging went", session.staging.exists())
        val manifest = checkNotNull(ManifestJson.parse(manifestOf(session.dir).readText())).manifest
        assertEquals(listOf("field_ranges.bin", "frame_0000.dat", "frame_0001.dat"), manifest.files.map { it.name })
        assertEquals(spec.recordSettings().subset, manifest.runSpec.subset)
        val integrity = RecordVerifier.verify(session.dir)
        assertTrue("no Keystore under Robolectric: $integrity", integrity is RecordIntegrity.Unsigned)
    }

    @Test
    fun `a cancelled run leaves no manifest`() {
        importFrames(2)
        val launched = vm.defFilePaths
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        stageUpTo(session, reached = 1)

        vm.discardCancelledBatch(ctx, session, launched, launched)
        idle()

        assertFalse(manifestOf(session.dir).exists())
        assertFalse("nothing staged survives", session.staging.exists())
    }

    @Test
    fun `a run killed before its commit leaves its frames staged and no manifest`() {
        importFrames(2)
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        // The process dies here: the loop staged a frame, nothing else ran.
        stageUpTo(session, reached = 1)

        assertFalse(manifestOf(session.dir).exists())
        assertFalse(File(session.staging, SessionLayout.MANIFEST).exists())
        assertEquals(RecordIntegrity.NotRecorded, RecordVerifier.verify(session.dir))
    }

    @Test
    fun `dropping the results drops the manifest, and the next commit writes its own`() {
        importFrames(2)
        val first = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        stageUpTo(first, reached = 2)
        first.commitRecord(ctx, spec.recordSettings())
        val before = manifestOf(first.dir).readText()

        first.dropResults()

        assertFalse(manifestOf(first.dir).exists())
        assertFalse(SessionPaths.frameDat(first.dir, 0).exists())

        // The same folder run again (it saved nothing): one frame now.
        SessionPaths.frameDat(first.staging.apply { mkdirs() }, 0).writeText("again")
        first.commitRecord(ctx, spec.recordSettings())
        val again = checkNotNull(ManifestJson.parse(manifestOf(first.dir).readText())).manifest
        assertEquals(listOf("frame_0000.dat"), again.files.map { it.name })
        assertTrue(before != manifestOf(first.dir).readText())
        assertTrue(RecordVerifier.verify(first.dir) is RecordIntegrity.Unsigned)
    }

    @Test
    fun `a run that throws after its commit takes its manifest back out with its frames`() {
        importFrames(2)
        val launched = vm.defFilePaths
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        stageUpTo(session, reached = 2)
        session.commitRecord(ctx, spec.recordSettings())
        assertTrue(manifestOf(session.dir).isFile)

        assertThrows(IOException::class.java) {
            vm.undoCommittedBatch(ctx, session, launched, launched, IOException("after the commit"))
        }
        idle()

        assertFalse(manifestOf(session.dir).exists())
    }

    @Test
    fun `an edited frame of a committed run reads Changed`() {
        importFrames(2)
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.BATCH)
        stageUpTo(session, reached = 2)
        session.commitRecord(ctx, spec.recordSettings())

        SessionPaths.frameDat(session.dir, 1).writeText("frame 1 edited")

        assertEquals(listOf("frame_0001.dat"), (RecordVerifier.verify(session.dir) as RecordIntegrity.Changed).files)
    }

    /** A two-combination sweep the runner left in [session] with [code]. */
    private fun sweepRun(session: RunSession, code: Int): SweepSession {
        val plan = listOf(SweepStudy.Point(21, 5, 3), SweepStudy.Point(31, 5, 3))
        val sweepSpec = RunSpec.sweep(
            RunSpec.Sweep(plan, labels = listOf("a", "b"), lineCutHorizontal = true, frameIndex = 0),
            roi = Roi(0, 0, REF_SIDE, REF_SIDE),
            mask = null,
            use6x6 = false,
            debugDir = null,
        )
        val dats = plan.indices.map { SessionPaths.frameDat(session.staging, it).apply { writeText("node $it") } }
        val result = SweepStudyRunner.SweepResult(
            runs = plan.mapIndexed { i, p -> SweepStudyRunner.RunOutcome(p, dats[i], 400) },
            firstMetrics = null,
            engineErrorCode = code,
        )
        return SweepSession(session.id, session.dir, vm.snapshotRunInputs(), result, sweepSpec, 3_000)
    }

    @Test
    fun `a sweep that saves writes one manifest over its combinations`() {
        importFrames(1)
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.SWEEP)

        val outcome = vm.endSweep(ctx, session, sweepRun(session, code = 0), true)
        idle()

        assertTrue(outcome.saved)
        val manifest = checkNotNull(ManifestJson.parse(manifestOf(session.dir).readText())).manifest
        assertEquals(listOf("frame_0000.dat", "frame_0001.dat"), manifest.files.map { it.name })
        assertEquals("the plan's first combination", 21, manifest.runSpec.subset)
        assertTrue(RecordVerifier.verify(session.dir) is RecordIntegrity.Unsigned)
    }

    @Test
    fun `a cancelled sweep leaves no manifest`() {
        importFrames(1)
        val session = openRunSession(ctx, vm.inputs.value.working, RunKind.SWEEP)

        val outcome = vm.endSweep(ctx, session, sweepRun(session, RunStop.Cancelled.wireCode), true)
        idle()

        assertEquals(RunStop.Cancelled, outcome.stop)
        assertFalse(manifestOf(session.dir).exists())
    }

    private companion object {
        const val REF_SIDE = 4
    }
}
