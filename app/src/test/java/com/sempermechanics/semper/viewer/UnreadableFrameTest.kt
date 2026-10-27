package com.sempermechanics.semper.viewer

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import com.sempermechanics.semper.fixtures.launchViewer
import com.sempermechanics.semper.fixtures.viewerArgs
import com.sempermechanics.semper.fixtures.writeGridBatch
import com.sempermechanics.semper.ui.viewer.ResultViewerActivity
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * A frame whose `.dat` cannot be read used to leave the picture where it was
 * with the frame field already on the new number and nothing said
 * (readiness AE1-013). The viewer now names the frame it could not read and
 * the field goes back to the frame on screen.
 */
@RunWith(RobolectricTestRunner::class)
class UnreadableFrameTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 5
        const val GRID = 4
        const val STEP = 4
    }

    @Before
    fun writeBatch() {
        batchDir = temp.newFolder("batch")
        writeGridBatch(batchDir, FRAMES, GRID, STEP)
        // Frame 3 is cut short: its size is no whole number of points.
        File(batchDir, "frame_002.dat").writeBytes(ByteArray(7))
    }

    private fun viewer(): ResultViewerActivity =
        launchViewer(viewerArgs(batchDir, GRID, STEP)).also {
            idleUntil("the viewer") { it.frameSetLoaded }
            idleUntil("frame 1") { it.rawData != null }
        }

    private fun ResultViewerActivity.field(): EditText = findViewById(R.id.etFrameNumber)

    private fun ResultViewerActivity.crispToast(): String? =
        findViewById<ViewGroup>(android.R.id.content)
            .findViewWithTag<View>("semper_crisp_toast")
            ?.findViewById<TextView>(R.id.tvToast)
            ?.text
            ?.toString()

    @Test
    fun `an unreadable frame is named and the field goes back to the frame on screen`() {
        val activity = viewer()
        val shown = activity.rawData

        activity.field().setText("3")
        activity.field().onEditorAction(EditorInfo.IME_ACTION_GO)
        idleUntil("the unreadable-frame message") { activity.crispToast() != null }
        shadowOf(activity.mainLooper).idle()

        assertEquals(activity.getString(R.string.viewer_frame_unreadable_fmt, 3, 1), activity.crispToast())
        assertEquals(0, activity.viewModel.currentFrameIndex)
        assertEquals("1", activity.field().text.toString())
        assertEquals(shown, activity.rawData)
    }

    @Test
    fun `a readable frame after it still opens`() {
        val activity = viewer()

        activity.field().setText("4")
        activity.field().onEditorAction(EditorInfo.IME_ACTION_GO)
        idleUntil("frame 4") { activity.viewModel.currentFrameIndex == 3 && activity.field().text.toString() == "4" }

        assertEquals(null, activity.crispToast())
    }
}
