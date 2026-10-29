package com.sempermechanics.semper.ui.analysis.sweep

import android.view.View
import com.google.android.material.textfield.TextInputLayout
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import com.sempermechanics.semper.ui.analysis.frames.DeformedFrame
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sweep's frame picker labels each frame by the name it was picked as,
 * and "Frame n" when there is none. A restored draft pads a frame with no
 * recorded name with "", which used to show as an empty label.
 */
@RunWith(RobolectricTestRunner::class)
class SweepFramePickerTest {

    private val bed = WizardTestBed()
    private val picker = SweepFramePicker(bed.activity, bed.viewModel) {}

    @After
    fun closeBed() = bed.close()

    @Test
    fun `a frame is labelled by its picked name, else by its number`() {
        val frames = listOf(
            DeformedFrame(path = "/c/0000_a.png", name = "specimen.png"),
            DeformedFrame(path = "/c/0001_b.png", name = ""),
            DeformedFrame(path = "/c/0002_c.png", name = "dir/IMG_3.png"),
        )
        bed.viewModel.edit { it.copy(deformedFrames = frames) }

        assertEquals("specimen.png", picker.frameLabel(0))
        assertEquals("Frame 2", picker.frameLabel(1))
        assertEquals("IMG_3.png", picker.frameLabel(2))
        assertEquals("Frame 4", picker.frameLabel(3))
    }

    /**
     * The field cut 'speckle_02.png' to 'speckle_02.pn', then, at font scale
     * 2.0, to 'sp…g' beside its "2 / 3" (readiness AE1-108). It is display-only
     * (a tap opens the frame dialog), and the whole name now shows: it wraps,
     * and at a large font the field has a line of its own under the thumbnail.
     */
    @Test
    fun `the frame name is display-only and never cut`() {
        picker.bind()
        val name = bed.activity.findViewById<android.widget.EditText>(R.id.ddSweepFrame)

        assertNull("typed into", name.keyListener)
        assertNull("cut", name.ellipsize)
        assertTrue("one line only", name.maxLines > 1)
    }
}

/**
 * The "Frame to sweep" field on a 360 dp phone (the readiness emulator's 1080 x
 * 2400 at 480 dpi) with real fonts: the whole picked name and its "2 / 3" are
 * laid out, unclipped and uncut, at font scale 1 and 2 (readiness AE1-108).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-port-xxhdpi")
class SweepFrameNameFontScaleTest {

    private var bed: WizardTestBed? = null

    @After
    fun close() {
        bed?.close()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun assertWholeNameAt(fontScale: Float, label: String, maxLines: Int) {
        RuntimeEnvironment.setFontScale(fontScale)
        val bed = WizardTestBed().also { bed = it }
        val frames = listOf("/c/0000_a.png", "/c/0001_b.png", "/c/0002_c.png")
            .map { DeformedFrame(path = it, name = label) }
        bed.viewModel.edit { it.copy(deformedFrames = frames) }
        val picker = SweepFramePicker(bed.activity, bed.viewModel) {}
        picker.bind()
        picker.show(1)
        val block = bed.activity.findViewById<View>(R.id.sweepFrameBlock)
        val density = bed.activity.resources.displayMetrics.density
        // The settings page's 16 dp side margins leave 328 dp of a 360 dp phone.
        val width = (CONTENT_DP * density).toInt()
        // Lay out until no pre-draw pass asks for another (the field may move once).
        var passes = 0
        do {
            block.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            block.layout(0, 0, width, block.measuredHeight)
            passes++
        } while (block.viewTreeObserver.dispatchOnPreDraw() && passes < MAX_PASSES)

        val name = bed.activity.findViewById<android.widget.EditText>(R.id.ddSweepFrame)
        val field = bed.activity.findViewById<TextInputLayout>(R.id.tilSweepFrame)
        val at = "fs $fontScale"
        assertEquals("$at shown name", label, name.text.toString())
        val layout = name.layout
        val lines = layout.lineCount
        assertEquals("$at ellipsized", 0, (0 until lines).sumOf { layout.getEllipsisCount(it) })
        assertEquals("$at laid-out text", label, layout.text.toString())
        val room = name.width - name.totalPaddingLeft - name.totalPaddingRight
        val widest = (0 until lines).maxOf { layout.getLineWidth(it) }
        assertTrue("$at name line ${widest}px in ${room}px", widest <= room)
        val textHeight = layout.height + name.totalPaddingTop + name.totalPaddingBottom
        assertTrue("$at name is ${name.height}px high, its text ${textHeight}px", name.height >= textHeight)
        val suffix = field.suffixTextView
        val suffixText = suffix.text.toString()
        assertEquals("$at suffix", "2 / 3", suffixText)
        assertTrue(
            "$at suffix '$suffixText' is ${suffix.width}px of ${suffix.paint.measureText(suffixText)}px",
            suffix.width >= suffix.paint.measureText(suffixText),
        )
        assertTrue("$at field ends at ${field.rightIn(block)} past ${block.width}", field.rightIn(block) <= block.width)
        // The field is wide enough to read the name in a line or two, not a column of letters,
        // and beside the thumbnail it never wraps: a name that would gets the field its own line.
        assertTrue("$at '$label' needs $lines lines", lines <= maxLines)
        if ((field.parent as View).id == R.id.sweepFrameRow) assertEquals("$at wraps beside the thumbnail", 1, lines)
    }

    private fun View.rightIn(ancestor: View): Int {
        var x = right
        var p = parent as? View
        while (p != null && p !== ancestor) {
            x += p.left
            p = p.parent as? View
        }
        return x
    }

    @Test
    fun `the whole name shows at font scale 2`() = assertWholeNameAt(2f, "speckle_02.png", maxLines = 2)

    @Test
    fun `the whole name shows at font scale 1`() = assertWholeNameAt(1f, "speckle_02.png", maxLines = 1)

    @Test
    fun `a long camera name shows whole at font scale 2`() =
        assertWholeNameAt(2f, "IMG_20261028_143502.png", maxLines = 3)

    @Test
    fun `a long camera name shows whole at font scale 1`() =
        assertWholeNameAt(1f, "IMG_20261028_143502.png", maxLines = 2)

    private companion object {
        const val CONTENT_DP = 328
        const val MAX_PASSES = 4
    }
}
