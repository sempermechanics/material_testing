package com.indicvision.semper.viewer

import android.net.Uri
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import com.indicvision.semper.ui.viewer.ShareCenter
import com.indicvision.semper.ui.viewer.ViewerArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The viewer's "save to Files" export, end to end from a real viewer: the CSV
 * it writes to the picked document, and the per-frame pitch every sweep export
 * renders with. The PDF kinds need the platform's PdfDocument and are covered
 * on a device (PdfReportDeviceTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareCenterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 3
        const val GRID = 4
        const val STEP = 4
        const val TIMEOUT_MS = 10_000L
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        for (f in 0 until FRAMES) {
            val points = GRID * GRID
            val buffer = ByteBuffer.allocate(points * DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
            for (i in 0 until points) {
                buffer.putFloat(((i % GRID) * STEP).toFloat())
                buffer.putFloat(((i / GRID) * STEP).toFloat())
                buffer.putFloat(f.toFloat()).putFloat(0f)
                buffer.putFloat(f * 0.001f).putFloat(0f).putFloat(0f)
                buffer.putFloat(0.01f)
            }
            File(batchDir, "frame_%03d.dat".format(f)).writeBytes(buffer.array())
        }
    }

    private fun viewer(frameNames: List<String> = emptyList()): ResultViewerActivity {
        val intent = ViewerArgs.ofFrames(
            batchDir.absolutePath,
            GRID * STEP,
            GRID * STEP,
            STEP,
            frameNames = frameNames,
            startFrame = 0,
        ).toIntent(ApplicationProvider.getApplicationContext())
        val activity = Robolectric.buildActivity(ResultViewerActivity::class.java, intent).setup().get()
        idleUntil(activity) { activity.buildShareSnapshot() != null }
        return activity
    }

    /** The export builds off the main thread and hands back to it, so pump both. */
    private fun idleUntil(activity: ResultViewerActivity, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            shadowOf(activity.mainLooper).idle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "timed out waiting on the viewer" }
            Thread.sleep(20)
        }
    }

    /** The in-app pill CrispToast adds over the content, or null when none is up. */
    private fun pillText(activity: ResultViewerActivity): String? =
        activity.findViewById<ViewGroup>(android.R.id.content)
            .findViewById<TextView>(R.id.tvToast)?.text?.toString()

    @Test
    fun `save to Files writes the whole batch as one csv`() {
        val activity = viewer()
        val dest = File(temp.root, "picked.csv")
        ShareCenter(activity).writeKindToUri("csv", Uri.fromFile(dest))
        idleUntil(activity) { ShadowToast.getLatestToast() != null }

        assertEquals(activity.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
        val lines = dest.readLines()
        val header = lines.indexOfFirst { it.startsWith("image,") }
        assertTrue("one point header", header >= 0 && lines.count { it.startsWith("image,") } == 1)
        assertTrue("field stats all sit above the points", lines.drop(header).none { it.startsWith("# ") })
        for (f in 1..FRAMES) {
            assertEquals(GRID * GRID, lines.count { it.startsWith("Frame_$f,") })
        }
    }

    /** The share CSV's point rows, keyed by their image column. */
    private fun csvRowsByImage(activity: ResultViewerActivity): Map<String, Int> {
        val dest = File(temp.root, "picked_${System.nanoTime()}.csv")
        ShareCenter(activity).writeKindToUri("csv", Uri.fromFile(dest))
        idleUntil(activity) { ShadowToast.getLatestToast() != null }
        val lines = dest.readLines()
        val header = lines.indexOfFirst { it.startsWith("image,") }
        return lines.drop(header + 1).filter { it.isNotBlank() }.groupingBy { it.substringBefore(',') }.eachCount()
    }

    @Test
    fun `past a skipped frame every frame keeps its own name`() {
        // The batch skipped frame 2 (b.png): frame_001 was never written.
        File(batchDir, "frame_001.dat").delete()
        val activity = viewer(listOf("a.png", "b.png", "c.png"))

        assertEquals("c.png", activity.frameDisplayName(1))
        val snapshot = activity.buildShareSnapshot()!!
        assertEquals(2, snapshot.plannedAt(1))
        assertEquals("c.png", snapshot.nameAt(1))
        // Same rows as the cloud bundle's CSV (SessionUploadBundlerTest).
        assertEquals(mapOf("a.png" to GRID * GRID, "c.png" to GRID * GRID), csvRowsByImage(activity))
    }

    @Test
    fun `an unnamed frame past a skipped one is numbered as planned`() {
        File(batchDir, "frame_001.dat").delete()
        val activity = viewer()

        assertEquals("Frame 3", activity.frameDisplayName(1))
        assertEquals(mapOf("Frame_1" to GRID * GRID, "Frame_3" to GRID * GRID), csvRowsByImage(activity))
    }

    @Test
    fun `an unknown kind reports failure instead of saving`() {
        val activity = viewer()
        val dest = File(temp.root, "picked.bin")
        ShareCenter(activity).writeKindToUri("bogus", Uri.fromFile(dest))
        idleUntil(activity) { pillText(activity) != null }

        assertEquals(activity.getString(R.string.share_failed), pillText(activity))
        assertTrue(!dest.exists() || dest.length() == 0L)
    }

    @Test
    fun `a sweep renders each frame at its own pitch`() {
        val base = viewer().buildShareSnapshot()!!
        val sweep = base.copy(stepPerFrame = intArrayOf(3, 5))
        assertEquals(3, sweep.stepAt(0))
        assertEquals(5, sweep.stepAt(1))
        assertEquals("past the sweep's list, the shared step", sweep.step, sweep.stepAt(2))
        assertEquals(STEP, base.stepAt(1))
    }
}
