package com.sempermechanics.semper.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.data.integrity.ManifestJson
import com.sempermechanics.semper.data.integrity.RecordIntegrity
import com.sempermechanics.semper.data.integrity.RecordVerifier
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.session.SessionLayout
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionQuota
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicParams
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.ImageSize
import com.sempermechanics.semper.field.Roi
import com.sempermechanics.semper.field.RunStop
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import com.sempermechanics.semper.ui.analysis.run.BatchRun
import com.sempermechanics.semper.ui.analysis.run.RunSpec
import com.sempermechanics.semper.ui.analysis.run.runBatchAnalysisBody
import com.sempermechanics.semper.ui.analysis.run.snapshotRunInputs
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisViewModel
import com.sempermechanics.semper.util.Digests
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Framework item 1.3's check (TD-247, ADR-028): kill a run partway, replay
 * it, and compare what is stored with the source data.
 *
 * The run is killed from its own progress callback with an [Error] the run
 * does not catch (it catches `Exception` only), so nothing after the throw
 * point runs: not the commit, not the undo, not the save. That is what a
 * process death at that tick leaves on disk. (A real `am kill` would take the
 * instrumentation down with it.) The killed run must leave no record
 * manifest anywhere. The replay is the same inputs run again, through the
 * real engine loop: it must commit a signed manifest that verifies, whose
 * hashes are the stored `.dat` bytes, whose `.dat` hold the known
 * translation, and whose first frame is bit-identical to the one the killed
 * run had staged (TD-65).
 */
@RunWith(AndroidJUnit4::class)
class RecordManifestProcessDeathTest {

    private companion object {
        const val W = 320
        const val H = 320
        const val STEP = 5
        const val SUBSET = 31
    }

    /** What the killed run's process death stands for. */
    private class SimulatedProcessDeath : Error("process killed")

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private var realQuotaApi = SessionQuota.api
    private val ids = mutableListOf<String>()
    private lateinit var inputs: File

    @Before
    fun setUp() {
        realQuotaApi = SessionQuota.api
        SessionQuota.api = { context ->
            object : CloudApi by SemperApi.get(context) {
                override val enabled: Boolean = false
            }
        }
        inputs = File(ctx.cacheDir, "record-manifest-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        SessionQuota.api = realQuotaApi
        ids.forEach {
            SessionStore.forget(ctx, it)
            SessionStore.dirFor(ctx, it).deleteRecursively()
        }
        inputs.deleteRecursively()
    }

    private fun makeReference(): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(128, 128, 128))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rng = Random(12345)
        repeat(1500) {
            val g = rng.nextInt(0, 256)
            paint.color = Color.rgb(g, g, g)
            canvas.drawCircle(rng.nextFloat() * W, rng.nextFloat() * H, 1.5f + rng.nextFloat() * 2.5f, paint)
        }
        return bmp
    }

    private fun shifted(src: Bitmap, dx: Float, dy: Float): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(128, 128, 128))
        canvas.drawBitmap(src, Matrix().apply { setTranslate(dx, dy) }, Paint(Paint.FILTER_BITMAP_FLAG))
        return bmp
    }

    private fun Bitmap.png(): ByteArray = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    /** A wizard holding the reference and [shifts] as its deformed frames, written under [tag]. */
    private fun wizard(ref: Bitmap, shifts: List<Pair<Float, Float>>, tag: String): AnalysisViewModel {
        val frames = shifts.mapIndexed { i, (dx, dy) ->
            val file = File(inputs, "${tag}_def_$i.png")
            file.writeBytes(shifted(ref, dx, dy).png())
            DeformedFrame(file.absolutePath, "def_$i.png")
        }
        return AnalysisViewModel().apply {
            edit {
                it.copy(refBytes = ref.png(), refSize = ImageSize(W, H), refName = "ref.png", deformedFrames = frames)
            }
            cancelRequested = false
        }
    }

    /** The sessions' folders that hold a record manifest, anywhere under them. */
    private fun manifestsUnder(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.name == SessionLayout.MANIFEST }.toList()

    /** Median |u − dx| and |v − dy| over the accepted points of [data] are under [tol] px. */
    private fun assertTranslation(data: FloatArray, dx: Float, dy: Float, tol: Float) {
        val du = ArrayList<Float>()
        val dv = ArrayList<Float>()
        var i = 0
        while (i < data.size) {
            if (DicResult.isAcceptedPoint(data[i + DicResult.IDX_ZNSSD])) {
                du += abs(data[i + DicResult.IDX_U] - dx)
                dv += abs(data[i + DicResult.IDX_V] - dy)
            }
            i += DicResult.STRIDE
        }
        assertTrue("too few accepted points: ${du.size}", du.size > 100)
        assertTrue("median |u − $dx| = ${du.sorted()[du.size / 2]}", du.sorted()[du.size / 2] < tol)
        assertTrue("median |v − $dy| = ${dv.sorted()[dv.size / 2]}", dv.sorted()[dv.size / 2] < tol)
    }

    @Test
    fun aRunKilledPartwayLeavesNoManifestAndItsReplayVerifiesAgainstTheSource() {
        val ref = makeReference()
        val shifts = listOf(1f to 0f, 2f to 1f, 3f to 2f)
        val params = DicParams(subset = SUBSET, step = STEP, strainWindow = 15)
        val spec = RunSpec.of(params, Roi(0, 0, W, H), null, false, null)
        val sessionsRoot = checkNotNull(SessionStore.dirFor(ctx, "probe").parentFile)
        SessionStore.dirFor(ctx, "probe").delete()
        val foldersBefore = sessionsRoot.list().orEmpty().toSet()

        // Killed as frame 1 starts: frame 0's .dat is staged, nothing committed.
        // The loop's own frame-start tick (it carries the convergence so far),
        // never the engine's intra-frame one, which JNI calls: an Error thrown
        // through native code would be a crash, not a kill.
        val killed = wizard(ref, shifts, "killed")
        val killedRun = BatchRun(spec, ctx.cacheDir, System.currentTimeMillis(), Job(), killed.snapshotRunInputs())
        try {
            killed.runBatchAnalysisBody(ctx, killedRun) { tick ->
                if (tick.frameIndex == 1 && tick.perFrameConvergence != null && tick.framePercent == 0f) {
                    throw SimulatedProcessDeath()
                }
            }
            throw AssertionError("the run was not killed")
        } catch (_: SimulatedProcessDeath) {
            // The process is gone; only the disk is left.
        }
        val killedFolders = sessionsRoot.list().orEmpty().toSet() - foldersBefore
        ids += killedFolders
        val killedDir = File(sessionsRoot, killedFolders.single())
        assertTrue("no manifest anywhere a killed run touched", manifestsUnder(killedDir).isEmpty())
        assertEquals(RecordIntegrity.NotRecorded, RecordVerifier.verify(killedDir))
        assertFalse("no frame was committed", SessionPaths.frameDat(killedDir, 0).exists())
        val stagedFrame0 = SessionPaths.frameDat(SessionPaths.runStaging(killedDir), 0)
        assertTrue("the frame it solved is still staged", stagedFrame0.isFile)

        // The replay: the same inputs, run to the end.
        val replay = wizard(ref, shifts, "replay")
        val outcome = replay.runBatchAnalysisBody(
            ctx,
            BatchRun(spec, ctx.cacheDir, System.currentTimeMillis(), Job(), replay.snapshotRunInputs()),
        ) {}
        ids += checkNotNull(outcome.sessionId)
        assertEquals(RunStop.Finished, outcome.stop)
        assertTrue(outcome.saved)
        val dir = File(outcome.batchDirPath)

        assertTrue("signed with the Keystore device key", RecordVerifier.verify(dir) is RecordIntegrity.Verified)
        val manifest = checkNotNull(ManifestJson.parse(SessionLayout(dir).recordManifest.readText())).manifest
        assertNotNull(manifest.signature)
        assertEquals(spec.recordSettings().subset, manifest.runSpec.subset)
        assertEquals(
            "every frame and the summary's sidecar",
            shifts.indices.map { SessionPaths.frameDat(dir, it).name }.toSet() + SessionLayout.FIELD_RANGES,
            manifest.files.map { it.name }.toSet(),
        )
        manifest.files.forEach { entry ->
            val stored = File(dir, entry.name)
            assertEquals(entry.name, stored.length(), entry.size)
            assertEquals(entry.name, Digests.sha256Hex(stored), entry.sha256)
        }
        shifts.forEachIndexed { i, (dx, dy) ->
            val data = checkNotNull(DicResult.decodeDatFile(SessionPaths.frameDat(dir, i))) { "frame $i" }
            assertTranslation(data, dx, dy, tol = 0.25f)
        }
        assertArrayEquals(
            "the replay's frame 0 is the killed run's, bit for bit",
            stagedFrame0.readBytes(),
            SessionPaths.frameDat(dir, 0).readBytes(),
        )
    }
}
