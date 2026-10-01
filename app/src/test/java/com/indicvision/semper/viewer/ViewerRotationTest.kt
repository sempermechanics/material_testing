package com.indicvision.semper.viewer

import android.content.DialogInterface
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.ui.viewer.ResultViewerActivity
import com.indicvision.semper.ui.viewer.ViewerArgs
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/** What the result viewer keeps when a rotation recreates it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewerRotationTest {

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
                buffer.putFloat(f + i * 0.1f).putFloat(0f)
                buffer.putFloat(f * 0.001f).putFloat(0f).putFloat(0f)
                buffer.putFloat(0.01f)
            }
            File(batchDir, "frame_%04d.dat".format(f)).writeBytes(buffer.array())
        }
    }

    private fun controller(): ActivityController<ResultViewerActivity> {
        val intent = ViewerArgs.ofFrames(batchDir.absolutePath, GRID * STEP, GRID * STEP, STEP, startFrame = 0)
            .toIntent(ApplicationProvider.getApplicationContext())
        return Robolectric.buildActivity(ResultViewerActivity::class.java, intent).setup()
    }

    private fun idleUntil(activity: ResultViewerActivity, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            shadowOf(activity.mainLooper).idle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "timed out waiting on the viewer" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `opening the viewer lists the batch off the main thread`() {
        val intent = ViewerArgs.ofFrames(batchDir.absolutePath, GRID * STEP, GRID * STEP, STEP, startFrame = 0)
            .toIntent(ApplicationProvider.getApplicationContext())
        val controller = Robolectric.buildActivity(ResultViewerActivity::class.java, intent)
        // Hold the disk work: whatever onCreate and the main looper do without it
        // must not include the listing.
        val held = mutableListOf<Runnable>()
        controller.get().frameSetDispatcher = Executor { held += it }.asCoroutineDispatcher()
        val activity = controller.setup().get()
        shadowOf(activity.mainLooper).idle()

        assertEquals(0, activity.frameCount())
        assertEquals(false, activity.frameSetLoaded)

        held.toList().forEach { it.run() }
        idleUntil(activity) { activity.frameSetLoaded }
        assertEquals(FRAMES, activity.frameCount())
        idleUntil(activity) { activity.rawData != null }
        assertEquals(0, activity.currentFrameIndex)
    }

    @Test
    fun `a custom colour scale survives a rotation`() {
        val controller = controller()
        val activity = controller.get()
        idleUntil(activity) { activity.rawData != null }

        activity.findViewById<View>(R.id.layoutColorScale).performClick()
        shadowOf(activity.mainLooper).idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<EditText>(R.id.etScaleMin)!!.setText("-2")
        dialog.findViewById<EditText>(R.id.etScaleMax)!!.setText("3")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(activity.mainLooper).idle()
        assertEquals(-2f to 3f, activity.customBoundsFor(DicResult.IDX_U))

        val rebuilt = controller.recreate().get()
        idleUntil(rebuilt) { rebuilt.rawData != null }

        assertEquals(-2f to 3f, rebuilt.customBoundsFor(DicResult.IDX_U))
    }
}
