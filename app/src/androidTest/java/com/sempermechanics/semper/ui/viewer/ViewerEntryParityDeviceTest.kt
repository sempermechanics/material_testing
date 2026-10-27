package com.sempermechanics.semper.ui.viewer

import android.app.Activity
import android.graphics.Bitmap
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.session.SessionPaths
import com.sempermechanics.semper.data.session.SessionRecord
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.ui.analysis.VsgLatticeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The lattice → viewer hop must carry the sweep's arguments to the node
 * picked. A session opened straight from the run that made it and later from
 * Home used to be two builders kept equal only here (TD-90, ADR-003/004);
 * both now open its saved row through [ViewerArgs.of], which the JVM
 * `ViewerArgsTest` pins.
 */
@RunWith(AndroidJUnit4::class)
class ViewerEntryParityDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var dir: File
    private lateinit var record: SessionRecord

    @Before
    fun seed() {
        dir = SessionStore.dirFor(context, ID)
        val ref = File(dir, "reference.png")
        Bitmap.createBitmap(IMG_W, IMG_H, Bitmap.Config.ARGB_8888).apply {
            ref.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val floats = FloatArray(COLS * ROWS * DicResult.STRIDE)
        for (p in floats.indices step DicResult.STRIDE) {
            floats[p + DicResult.IDX_X] = (ROI_X + p / DicResult.STRIDE % COLS * STEP).toFloat()
            floats[p + DicResult.IDX_Y] = (ROI_Y + p / DicResult.STRIDE / COLS * STEP).toFloat()
            floats[p + DicResult.IDX_ZNSSD] = 0.01f
        }
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(floats)
        repeat(FRAMES) { SessionPaths.frameDat(dir, it).writeBytes(buf.array()) }

        val now = System.currentTimeMillis()
        record = SessionRecord(
            id = ID,
            name = "Entry parity",
            createdAt = now,
            updatedAt = now,
            frameCount = FRAMES,
            subset = SUBSET,
            step = STEP,
            strainWindow = STRAIN_WINDOW,
            imgW = IMG_W,
            imgH = IMG_H,
            roiX = ROI_X,
            roiY = ROI_Y,
            roiW = ROI_W,
            roiH = ROI_H,
            refPath = ref.absolutePath,
            refName = ref.name,
            sessionDir = dir.absolutePath,
            defNames = listOf("f1.png", "f2.png"),
            plannedFrameCount = FRAMES,
        )
        assertTrue(SessionStore.upsert(context, record, allowOverLimit = true))
    }

    @After
    fun remove() {
        SessionStore.delete(context, ID)
    }

    @Test
    fun aLatticeNodeOpensTheViewerOnThatCombination() {
        val sweep = ViewerSweepArgs(
            subsets = listOf(21, 31),
            steps = listOf(STEP, STEP),
            strainWindows = listOf(15, 21),
            lineCutHorizontal = false,
            skippedJson = "[]",
        )
        val lattice = ViewerArgs.of(record).copy(sweep = sweep, frameNames = listOf("a", "b"))
        val monitor = instrumentation.addMonitor(ResultViewerActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch<VsgLatticeActivity>(lattice.toIntent(context)).use { scenario ->
                scenario.onActivity { activity ->
                    // Focus opens on the first solved node (frame 0); step to frame 1.
                    activity.findViewById<View>(R.id.btnNextNode).performClick()
                    activity.findViewById<View>(R.id.btnView).performClick()
                }
                val viewer = instrumentation.waitForMonitorWithTimeout(monitor, TIMEOUT_MS)
                assertNotNull("the View button opened no viewer", viewer)
                val args = (viewer as ResultViewerActivity).args
                assertEquals(lattice.copy(startFrame = 1), args)
                (viewer as Activity).finish()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private companion object {
        const val ID = "test_entry_parity"
        const val IMG_W = 200
        const val IMG_H = 120
        const val ROI_X = 20
        const val ROI_Y = 20
        const val ROI_W = 160
        const val ROI_H = 80
        const val STEP = 4
        const val COLS = ROI_W / STEP
        const val ROWS = ROI_H / STEP
        const val SUBSET = 21
        const val STRAIN_WINDOW = 15
        const val FRAMES = 2
        const val TIMEOUT_MS = 10_000L
    }
}
