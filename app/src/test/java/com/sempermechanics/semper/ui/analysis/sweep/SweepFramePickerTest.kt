package com.sempermechanics.semper.ui.analysis.sweep

import android.text.TextUtils
import android.text.method.SingleLineTransformationMethod
import android.view.View
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
     * The field cut 'speckle_02.png' to 'speckle_02.pn' and, at font scale 2.0,
     * wrapped it to a clipped second line (readiness AE1-108): an editable field
     * never ellipsizes. It is display-only (a tap opens the frame dialog), so it
     * drops its key listener and reads as one line cut in the middle.
     */
    @Test
    fun `the frame name is one line cut in the middle`() {
        picker.bind()
        val name = bed.activity.findViewById<android.widget.EditText>(R.id.ddSweepFrame)

        assertNull("typed into", name.keyListener)
        assertEquals(1, name.maxLines)
        assertEquals(TextUtils.TruncateAt.MIDDLE, name.ellipsize)
        assertTrue("wraps", name.transformationMethod is SingleLineTransformationMethod)
    }

    /**
     * On a phone the field's text is editable, so it is laid out by a
     * DynamicLayout, which only ever cuts at the end: android:ellipsize="middle"
     * left 'speck' clipped under the "2 / 2" suffix at font scale 2.0
     * (readiness AE1-108). The picker cuts the name itself to the room the
     * field leaves for text, keeping the frame number and extension.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // the legacy TextUtils.ellipsize shadow truncates with no ellipsis
    fun `a name wider than the field is cut in the middle to fit`() {
        val long = "speckle_pattern_specimen_02.png"
        val frames = listOf("/c/0000_a.png", "/c/0001_b.png").map { DeformedFrame(path = it, name = long) }
        bed.viewModel.edit { it.copy(deformedFrames = frames) }
        picker.bind()
        picker.show(1)
        val name = bed.activity.findViewById<android.widget.EditText>(R.id.ddSweepFrame)
        val room = (name.paint.measureText(long) / 2).toInt()
        val width = name.totalPaddingLeft + name.totalPaddingRight + room
        name.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        name.layout(0, 0, width, name.measuredHeight)

        name.viewTreeObserver.dispatchOnPreDraw()

        val shown = name.text.toString()
        assertTrue("not cut: '$shown'", shown.contains('…'))
        assertTrue("'$shown'", shown.startsWith("spe") && shown.endsWith("02.png"))
        assertTrue("'$shown' overflows", name.paint.measureText(shown) <= room)
        assertEquals("the frame's full name stays the label", long, picker.frameLabel(1))
        assertEquals("TalkBack reads the full name", long, name.createAccessibilityNodeInfo().text.toString())
    }
}
