package com.sempermechanics.semper.ui.analysis.run

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.WorkTags
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.fixtures.CleanAppState
import com.sempermechanics.semper.fixtures.QuotaBackendOn
import com.sempermechanics.semper.fixtures.sessionRecord
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.frames.FrameImportHelper
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudy
import com.sempermechanics.semper.ui.analysis.sweep.SweepStudyRunner
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.ui.analysis.wizard.RunAdmission
import com.sempermechanics.semper.ui.analysis.wizard.RunSession
import com.sempermechanics.semper.ui.analysis.wizard.SweepSession
import com.sempermechanics.semper.ui.analysis.wizard.admitRun
import com.sempermechanics.semper.ui.analysis.wizard.endSweep
import com.sempermechanics.semper.ui.analysis.wizard.openRunSession
import com.sempermechanics.semper.ui.analysis.wizard.repointDeformedPaths
import com.sempermechanics.semper.util.Digests
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * Cancel keeps nothing of a run (owner decision, 2026-10-15). A new run that
 * is cancelled leaves no row, no folder and no upload, and costs no quota; a
 * cancelled re-run leaves the saved analysis byte for byte as it was; a
 * cancelled sweep saves nothing. A stop that is not a cancel still keeps its
 * frames. The JVM has no engine, so the batch loop's own steps are driven
 * here as it takes them: [openRunSession] at its start, [keepDeformedFrame]
 * and a staged `.dat` per frame it reaches, then [discardCancelledBatch] or
 * [RunSession.commit] at its end (the instrumented `EnginePipelineSmokeTest`
 * runs the real loop). A sweep is handed its runner's result ([endSweep]).
 */
@RunWith(RobolectricTestRunner::class)
class CancelDiscardsRunTest {

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
        params = DicParams(subset = 21, step = 5, strainWindow = 15),
        roi = Roi(0, 0, REF_SIDE, REF_SIDE),
        mask = null,
        use6x6 = false,
        debugDir = null,
    )

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        // One analysis on this account: a run that consumed it would block the next.
        AppRemoteConfig.apply(ctx, AppConfigDto(maxSessions = 1, maxFilesPerSession = 600, maxFrames = 150))
        AccountCache.setQuota(ctx, used = 0)
        vm.realRefWidth = REF_SIDE
        vm.realRefHeight = REF_SIDE
        vm.refName = "ref.png"
        vm.refBytes = ByteArray(REF_SIDE * REF_SIDE * 4)
    }

    @After
    fun tearDown() {
        importDir.deleteRecursively()
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** [count] frames as an import commits them, in the wizard. */
    private fun importFrames(count: Int): List<File> {
        val files = (0 until count).map { i ->
            File(importDir, "000${i}_def_$i.png").apply { writeBytes(byteArrayOf(i.toByte(), 1, 2, 3)) }
        }
        vm.deformedFrames = files.map { DeformedFrame(it.absolutePath, it.name.substringAfter('_')) }
        return files
    }

    /**
     * What the batch loop does in [session] for frames 0 until [reached]:
     * keeps each frame's image ([keepDeformedFrame]) and writes its `.dat`
     * where the loop writes it. Returns where each image now is.
     */
    private fun solveUpTo(session: RunSession, launched: List<String>, reached: Int, tag: String): List<String> {
        val raw = SessionLayout(session.dir).rawDeformedDir.apply { mkdirs() }
        val resolved = launched.toMutableList()
        for (i in 0 until reached) {
            val source = File(launched[i])
            if (source.parentFile?.absolutePath != raw.absolutePath) {
                val name = source.name.substringAfter('_')
                resolved[i] = keepDeformedFrame(source, source.readBytes(), raw, name, i).absolutePath
            }
            SessionPaths.frameDat(session.staging, i).writeText("$tag frame $i")
        }
        return resolved
    }

    /** A saved single run that solved [solved] of the wizard's frames, as the loop and its save leave it. */
    private fun savedRun(solved: Int): RunSession {
        val session = vm.openRunSession(ctx, sweep = false)
        val resolved = solveUpTo(session, vm.defFilePaths, solved, "saved")
        session.commit()
        vm.repointDeformedPaths(resolved)
        val layout = SessionLayout(session.dir)
        layout.fieldRanges.writeText("saved ranges")
        layout.referencePng.writeText("saved reference")
        SessionPaths.resultThumb(session.dir).writeText("saved thumbnail")
        val row = sessionRecord(
            id = session.id,
            frameCount = solved,
            sessionDir = session.dir.absolutePath,
            refPath = layout.referencePng.absolutePath,
            defNames = resolved.map { File(it).name },
            syncState = SessionRecord.SyncState.SYNCED,
            cloudSessionId = "cloud-1",
        )
        assertTrue(SessionStore.upsert(ctx, row, allowOverLimit = true))
        vm.lastRefPath = layout.referencePng.absolutePath
        return session
    }

    /** Every file under [dir], by its path there, with its SHA-256. */
    private fun hashes(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to Digests.sha256Hex(it) }

    private fun uploadsQueuedFor(id: String) =
        WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(WorkTags.uploadName(id)).get().size

    @Test
    fun `a cancelled new run leaves no row, no folder and no upload, and costs no quota`() {
        val frames = importFrames(3)
        val bytes = frames.map { it.readBytes() }
        val launched = vm.defFilePaths
        val session = vm.openRunSession(ctx, sweep = false)
        assertTrue(session.fresh)
        vm.resetRunResult(session.dir.absolutePath, spec)
        val resolved = solveUpTo(session, launched, reached = 2, tag = "cancelled")
        assertFalse("the loop moved the frames it reached", frames[0].exists())

        vm.discardCancelledBatch(ctx, session, launched, resolved)
        idle()

        assertTrue("no Home row", SessionStore.list(ctx).isEmpty())
        assertFalse("no session folder", session.dir.exists())
        assertEquals("no upload", 0, uploadsQueuedFor(session.id))
        frames.forEachIndexed { i, file -> assertArrayEquals("frame $i is back", bytes[i], file.readBytes()) }
        assertEquals(launched, vm.defFilePaths)
        assertNull("the next run is a new session", vm.workingLocalId)
        assertNull("no result to open", vm.runResult.value.batchDirPath)
        assertEquals("the one analysis allowed is still free", RunAdmission.Admitted, vm.admitRun(ctx, 3, false))
    }

    @Test
    fun `a cancelled re-run leaves the saved analysis byte for byte as it was`() {
        val frames = importFrames(3)
        // Saved partial: frame 2 was never reached, so it is still in the import.
        val saved = savedRun(solved = 2)
        val files = hashes(saved.dir)
        val row = SessionStore.get(ctx, saved.id)
        val result = vm.runResult.value
        val launched = vm.defFilePaths
        val thumbTime = SessionPaths.resultThumb(saved.dir).lastModified()

        val rerun = vm.openRunSession(ctx, sweep = false)
        assertEquals(saved.id, rerun.id)
        assertFalse(rerun.fresh)
        vm.resetRunResult(rerun.dir.absolutePath, spec)
        val resolved = solveUpTo(rerun, launched, reached = 3, tag = "rerun")
        assertEquals("staged, not written over the saved frames", files, hashes(saved.dir).filterKeys { it in files })

        vm.discardCancelledBatch(ctx, rerun, launched, resolved)
        idle()

        assertEquals("every file of the saved analysis", files, hashes(saved.dir))
        assertEquals("its row", row, SessionStore.get(ctx, saved.id))
        assertEquals(1, SessionStore.list(ctx).size)
        assertEquals("no upload", 0, uploadsQueuedFor(saved.id))
        assertEquals("its thumbnail is not made stale", thumbTime, SessionPaths.resultThumb(saved.dir).lastModified())
        assertTrue("the frame the re-run moved in is back in the import", frames[2].isFile)
        assertEquals(launched, vm.defFilePaths)
        assertEquals(saved.id, vm.workingLocalId)
        assertEquals("the wizard's last result", result, vm.runResult.value)
    }

    @Test
    fun `a stop that is not a cancel replaces the saved frames with the ones it solved`() {
        importFrames(3)
        val saved = savedRun(solved = 3)
        val rerun = vm.openRunSession(ctx, sweep = false)
        // Low convergence on the second frame: the loop stops with two frames written.
        solveUpTo(rerun, vm.defFilePaths, reached = 2, tag = "partial")

        rerun.commit()
        val record = checkNotNull(SessionStore.get(ctx, saved.id))
            .copy(frameCount = 2, stopCode = RunStop.LowConvergence.wireCode)
        val after = afterSave(
            RunStop.LowConvergence,
            saveRunRecord(ctx, record, cloudEnabled = false, admitted = false),
        )

        assertEquals("partial frame 0", SessionPaths.frameDat(saved.dir, 0).readText())
        assertEquals("partial frame 1", SessionPaths.frameDat(saved.dir, 1).readText())
        assertFalse("the old run's third frame", SessionPaths.frameDat(saved.dir, 2).exists())
        assertFalse("the old run's field ranges", SessionLayout(saved.dir).fieldRanges.exists())
        assertFalse(rerun.staging.exists())
        assertEquals(RunStop.LowConvergence, after.stop)
        assertTrue(after.recordSaved)
        assertEquals(2, SessionStore.get(ctx, saved.id)?.frameCount)
    }

    @Test
    fun `a run leaves the previous results alone until it commits`() {
        importFrames(3)
        val saved = savedRun(solved = 3)
        val rerun = vm.openRunSession(ctx, sweep = false)

        assertEquals("saved frame 0", SessionPaths.frameDat(saved.dir, 0).readText())
        assertTrue(SessionLayout(saved.dir).fieldRanges.exists())
        assertTrue("an empty staging", rerun.staging.isDirectory && rerun.staging.list().isNullOrEmpty())
    }

    /** A two-combination sweep against frame 0 that the runner left in [session] with [code]. */
    private fun sweepRun(session: RunSession, code: Int, tag: String): SweepSession {
        val plan = listOf(SweepStudy.Point(21, 5, 3), SweepStudy.Point(31, 5, 3))
        val sweepSpec = RunSpec.sweep(
            RunSpec.Sweep(plan, labels = listOf("a", "b"), lineCutHorizontal = true, frameIndex = 0),
            roi = Roi(0, 0, REF_SIDE, REF_SIDE),
            mask = null,
            use6x6 = false,
            debugDir = null,
        )
        val dats = plan.indices.map { SessionPaths.frameDat(session.staging, it).apply { writeText("$tag $it") } }
        val result = SweepStudyRunner.SweepResult(
            runs = plan.mapIndexed { i, p -> SweepStudyRunner.RunOutcome(p, dats[i], 400) },
            firstMetrics = null,
            engineErrorCode = code,
        )
        vm.resetRunResult(session.dir.absolutePath, sweepSpec)
        return SweepSession(session.id, session.dir, vm.snapshotRunInputs(), result, sweepSpec, 3_000)
    }

    @Test
    fun `a cancelled sweep saves nothing, however many combinations it solved`() {
        val frames = importFrames(1)
        val session = vm.openRunSession(ctx, sweep = true)

        val outcome = vm.endSweep(ctx, session, sweepRun(session, RunStop.Cancelled.wireCode, "cancelled"), true)
        idle()

        assertEquals(RunStop.Cancelled, outcome.stop)
        assertEquals(0, outcome.totalFrames)
        assertFalse(outcome.saved)
        assertTrue("no Home row", SessionStore.list(ctx).isEmpty())
        assertFalse("no session folder", session.dir.exists())
        assertEquals("no upload", 0, uploadsQueuedFor(session.id))
        assertTrue("the sweep's frame stays in the import", frames[0].isFile)
        assertTrue(vm.sweepPlan.isEmpty())
        assertNull(vm.workingLocalId)
    }

    @Test
    fun `a cancelled sweep re-run leaves the saved sweep as it was`() {
        importFrames(1)
        val first = vm.openRunSession(ctx, sweep = true)
        val saved = vm.endSweep(ctx, first, sweepRun(first, code = 0, tag = "saved"), true)
        idle()
        assertTrue(saved.saved)
        assertTrue("committed into the session", SessionPaths.frameDat(first.dir, 1).isFile)
        val files = hashes(first.dir)
        val row = SessionStore.get(ctx, first.id)
        val plan = vm.sweepPlan
        val uploads = uploadsQueuedFor(first.id)

        val rerun = vm.openRunSession(ctx, sweep = true)
        assertEquals(first.id, rerun.id)
        val outcome = vm.endSweep(ctx, rerun, sweepRun(rerun, RunStop.Cancelled.wireCode, "rerun"), false)
        idle()

        assertEquals(RunStop.Cancelled, outcome.stop)
        assertEquals(files, hashes(first.dir))
        assertEquals(row, SessionStore.get(ctx, first.id))
        assertEquals(uploads, uploadsQueuedFor(first.id))
        assertEquals(plan, vm.sweepPlan)
        assertEquals(first.id, vm.workingLocalId)
    }

    @Test
    fun `a copied frame is deleted, a moved one goes back, and one that cannot stays where it is`() {
        val raw = File(ctx.cacheDir, "raw-test").apply {
            deleteRecursively()
            mkdirs()
        }
        val kept = File(importDir, "0000_kept.png").apply { writeText("other session's") }
        val copy = File(raw, "kept.png").apply { writeText("other session's") }
        val movedFrom = File(importDir, "0001_moved.png")
        val moved = File(raw, "moved.png").apply { writeText("moved") }
        val blocker = File(importDir, "blocker").apply { writeText("a file, not a folder") }
        val strandedFrom = File(blocker, "0002_stranded.png")
        val stranded = File(raw, "stranded.png").apply { writeText("stranded") }
        val untouched = File(importDir, "0003_never_reached.png").apply { writeText("never reached") }
        val launched = listOf(kept, movedFrom, strandedFrom, untouched).map { it.absolutePath }

        val now = returnMovedFrames(
            launched,
            listOf(copy.absolutePath, moved.absolutePath, stranded.absolutePath, untouched.absolutePath),
        )

        assertFalse("the copy", copy.exists())
        assertEquals("other session's", kept.readText())
        assertEquals("moved", movedFrom.readText())
        assertFalse(moved.exists())
        assertEquals("stranded", stranded.readText())
        assertEquals(listOf(launched[0], launched[1], stranded.absolutePath, launched[3]), now)
        raw.deleteRecursively()
    }

    @Test
    fun `a new run whose frame cannot go back keeps its folder and points the wizard there`() {
        val blocker = File(importDir, "blocker").apply { writeText("a file, not a folder") }
        val launched = listOf(File(blocker, "0000_a.png").absolutePath)
        vm.deformedFrames = listOf(DeformedFrame(launched[0], "a.png"))
        val session = vm.openRunSession(ctx, sweep = false)
        val moved = File(SessionLayout(session.dir).rawDeformedDir.apply { mkdirs() }, "a.png").apply { writeText("a") }

        vm.discardCancelledBatch(ctx, session, launched, listOf(moved.absolutePath))
        idle()

        assertEquals("a", moved.readText())
        assertTrue(session.dir.isDirectory)
        assertEquals(listOf(moved.absolutePath), vm.defFilePaths)
        assertTrue(SessionStore.list(ctx).isEmpty())
    }

    @Test
    fun `Cancel names the saved analysis only for a re-run of one`() = runBlocking {
        assertEquals("a new run", R.string.cancel_run_body, cancelRunBody(ctx, vm, sweep = false))
        vm.workingLocalId = "working"
        vm.workingIsSweep = false
        assertEquals("a working session with no row", R.string.cancel_run_body, cancelRunBody(ctx, vm, false))
        val dir = SessionStore.dirFor(ctx, "working").absolutePath
        assertTrue(SessionStore.upsert(ctx, sessionRecord(id = "working", sessionDir = dir), allowOverLimit = true))
        assertEquals("a re-run of a saved one", R.string.cancel_rerun_body, cancelRunBody(ctx, vm, sweep = false))
        assertEquals("a sweep after it is a new session", R.string.cancel_run_body, cancelRunBody(ctx, vm, true))
    }

    @Test
    fun `only a folder no row names is deleted as unsaved`() {
        val savedDir = SessionStore.dirFor(ctx, "saved")
        assertTrue(SessionStore.upsert(ctx, sessionRecord(id = "saved", sessionDir = savedDir.absolutePath), true))
        val unsavedDir = SessionStore.dirFor(ctx, "unsaved")

        assertFalse(SessionStore.deleteUnsavedDir(ctx, "saved"))
        assertTrue(savedDir.isDirectory)
        assertTrue(SessionStore.deleteUnsavedDir(ctx, "unsaved"))
        assertFalse(unsavedDir.exists())
        assertFalse("never the sessions root", SessionStore.deleteUnsavedDir(ctx, ""))
        assertFalse(SessionStore.deleteUnsavedDir(ctx, ".."))
        assertTrue(savedDir.isDirectory)
    }

    private companion object {
        const val REF_SIDE = 4
    }
}
