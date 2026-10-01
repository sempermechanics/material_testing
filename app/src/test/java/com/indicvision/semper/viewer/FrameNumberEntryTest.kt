package com.indicvision.semper.viewer

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.DicResult
import com.indicvision.semper.R
import com.indicvision.semper.ui.viewer.ResultViewerActivity
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

/**
 * Typing a frame number is the only way to reach frame 118 of 150 without 117
 * taps, so what matters is that a good number lands there and a bad one moves
 * nothing at all.
 */
// Pinned like the other Robolectric tests: 4.14 tops out below our targetSdk.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FrameNumberEntryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var batchDir: File

    private companion object {
        const val FRAMES = 6
        const val GRID = 4
        const val STEP = 4
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
                buffer.putFloat(f.toFloat()) // u — differs per frame
                buffer.putFloat(0f)
                buffer.putFloat(f * 0.001f)
                buffer.putFloat(0f)
                buffer.putFloat(0f)
                buffer.putFloat(0.01f)
            }
            File(batchDir, "frame_%03d.dat".format(f)).writeBytes(buffer.array())
        }
    }

    private fun viewer(): ResultViewerActivity {
        // startFrame opens on a frame rather than the summary, which is what
        // the frame field is about.
        val intent = ViewerArgs.ofFrames(batchDir.absolutePath, GRID * STEP, GRID * STEP, STEP, startFrame = 0)
            .toIntent(ApplicationProvider.getApplicationContext())
        return Robolectric.buildActivity(ResultViewerActivity::class.java, intent).setup().get().also {
            shadowOf(it.mainLooper).idle()
        }
    }

    private fun ResultViewerActivity.field(): EditText = findViewById(R.id.etFrameNumber)

    private fun ResultViewerActivity.jumpTo(text: String) {
        field().setText(text)
        field().onEditorAction(EditorInfo.IME_ACTION_GO)
        shadowOf(mainLooper).idle()
    }

    @Test
    fun `the field shows which frame is open`() {
        val activity = viewer()

        assertEquals("1", activity.field().text.toString())
        assertEquals(
            activity.getString(R.string.frame_total_fmt, FRAMES),
            activity.findViewById<android.widget.TextView>(R.id.tvFrameTotal).text.toString(),
        )
    }

    @Test
    fun `a valid number jumps straight to that frame`() {
        val activity = viewer()

        activity.jumpTo("5")

        assertEquals(4, activity.currentFrameIndex)
        assertEquals("5", activity.field().text.toString())
    }

    @Test
    fun `a number past the end moves nothing and restores itself`() {
        val activity = viewer()

        activity.jumpTo("999")

        assertEquals(0, activity.currentFrameIndex)
        assertEquals("1", activity.field().text.toString())
    }

    @Test
    fun `zero is not a frame`() {
        val activity = viewer()

        activity.jumpTo("0")

        assertEquals(0, activity.currentFrameIndex)
        assertEquals("1", activity.field().text.toString())
    }

    @Test
    fun `an empty field restores the current frame rather than jumping`() {
        val activity = viewer()
        activity.jumpTo("3")

        activity.jumpTo("")

        assertEquals(2, activity.currentFrameIndex)
        assertEquals("3", activity.field().text.toString())
    }

    @Test
    fun `the scrubber stays up while a number is typed, then hides after the jump`() {
        val activity = viewer()
        val scrubber = activity.findViewById<View>(R.id.layoutScrubber)
        activity.bumpChrome()
        activity.field().requestFocus()

        // Well past the 2.5 s auto-hide: hiding would take the field's focus
        // and close the keyboard mid-number.
        shadowOf(activity.mainLooper).idleFor(Duration.ofSeconds(10))
        assertEquals(View.VISIBLE, scrubber.visibility)
        assertTrue(activity.field().hasFocus())

        activity.jumpTo("4")
        shadowOf(activity.mainLooper).idleFor(Duration.ofSeconds(10))

        assertEquals(3, activity.currentFrameIndex)
        assertEquals(View.INVISIBLE, scrubber.visibility)
    }

    @Test
    fun `stepping with Next writes the new number back`() {
        val activity = viewer()

        activity.findViewById<View>(R.id.btnNextFrame).performClick()
        shadowOf(activity.mainLooper).idle()

        assertEquals(1, activity.currentFrameIndex)
        assertEquals("2", activity.field().text.toString())
    }
}
