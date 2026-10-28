package com.sempermechanics.semper.ui.analysis.run

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.analysis.run.ConvergenceBinsView.Bin
import com.sempermechanics.semper.ui.analysis.run.ConvergenceBinsView.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The run overlay's convergence bins, from a run's progress and convergence snapshot. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ConvergenceBinsViewTest {

    private val nan = Float.NaN
    private lateinit var view: ConvergenceBinsView

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(AppCompatActivity::class.java)
            .also { it.get().setTheme(R.style.Theme_Semper) }
            .setup()
            .get()
        view = ConvergenceBinsView(activity)
    }

    @Test
    fun `finished frames are as tall as their convergence, the rest wait`() {
        view.setRun(planned = 5, frameIndex = 3, framePercent = 0f, convergence = floatArrayOf(96f, 94f, 91f, nan, nan))

        assertEquals(
            listOf(
                Bin(State.DONE, 96f),
                Bin(State.DONE, 94f),
                Bin(State.DONE, 91f),
                Bin(State.RUNNING, 0f),
                Bin(State.WAITING, 0f),
            ),
            view.bins,
        )
    }

    @Test
    fun `the frame in progress is as tall as its own percent`() {
        val values = floatArrayOf(96f, 94f, 91f, nan, nan)
        view.setRun(planned = 5, frameIndex = 3, framePercent = 79f, convergence = values)

        assertEquals(Bin(State.RUNNING, 79f), view.bins[3])
        assertEquals(State.WAITING, view.bins[4].state)
    }

    @Test
    fun `a frame's end tick finishes its bin`() {
        view.setRun(planned = 3, frameIndex = 1, framePercent = 100f, convergence = floatArrayOf(96f, 88f, nan))

        assertEquals(Bin(State.DONE, 88f), view.bins[1])
        assertEquals(State.WAITING, view.bins[2].state)
    }

    @Test
    fun `bins under the gate are low`() {
        view.setRun(planned = 3, frameIndex = 2, framePercent = 0f, convergence = floatArrayOf(96f, 41f, nan))

        assertFalse(view.bins[0].isLow)
        assertTrue(view.bins[1].isLow)
        assertFalse(Bin(State.DONE, 50f).isLow)
    }

    @Test
    fun `a frame that kept no points is finished with no height`() {
        view.setRun(planned = 3, frameIndex = 2, framePercent = 0f, convergence = floatArrayOf(96f, nan, nan))

        assertEquals(State.DONE, view.bins[1].state)
        assertTrue(view.bins[1].percent.isNaN())
        assertFalse(view.bins[1].isLow)
    }

    @Test
    fun `up to the bin limit each frame has its own bin`() {
        view.setRun(planned = ConvergenceBinsView.MAX_BINS, frameIndex = -1, framePercent = 0f, convergence = null)

        assertEquals(ConvergenceBinsView.MAX_BINS, view.bins.size)
        assertTrue(view.bins.all { it.state == State.WAITING })
    }

    @Test
    fun `over the bin limit neighbours merge and keep the lowest`() {
        val values = FloatArray(250) { 95f }.apply { this[7] = 30f }
        view.setRun(planned = 250, frameIndex = 250, framePercent = 100f, convergence = values)

        // 3 frames a bin: 84 bins, the last holding frame 250 alone.
        assertEquals(84, view.bins.size)
        assertEquals(Bin(State.DONE, 30f), view.bins[2])
        assertTrue(view.bins[2].isLow)
        assertEquals(Bin(State.DONE, 95f), view.bins[1])
        assertEquals(Bin(State.DONE, 95f), view.bins[83])
    }

    @Test
    fun `a merged bin in progress shows its progress and any low frame so far`() {
        val values = FloatArray(200) { nan }.apply {
            this[0] = 95f
            this[1] = 30f
            this[2] = 95f
        }
        // 2 frames a bin: frames 3 and 4 share bin 1, frame 4 is half done.
        view.setRun(planned = 200, frameIndex = 3, framePercent = 50f, convergence = values)

        assertEquals(Bin(State.DONE, 30f), view.bins[0])
        assertEquals(Bin(State.RUNNING, 75f), view.bins[1])
        assertEquals(State.WAITING, view.bins[2].state)

        // Frame 3 low while frame 4 is half done: the running bin shows it.
        values[2] = 20f
        view.setRun(planned = 200, frameIndex = 3, framePercent = 50f, convergence = values)
        assertEquals(Bin(State.RUNNING, 75f, lowPercent = 20f), view.bins[1])
    }

    @Test
    fun `the description counts the frames above the gate`() {
        val values = floatArrayOf(96f, 41f, 91f, nan, nan)
        view.setRun(planned = 5, frameIndex = 3, framePercent = 40f, convergence = values)

        assertEquals("Frame 4 of 5, 2 converged above 50%", view.contentDescription)

        view.clear()
        assertNull(view.contentDescription)
        assertEquals(0, view.bins.size)
    }

    @Test
    fun `it draws every kind of bin without failing`() {
        view.setRun(planned = 4, frameIndex = 2, framePercent = 30f, convergence = floatArrayOf(96f, 30f, nan, nan))
        view.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(80, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 300, 80)
        view.draw(Canvas(Bitmap.createBitmap(300, 80, Bitmap.Config.ARGB_8888)))
        assertEquals(4, view.bins.size)
    }

    @Test
    fun `up to twelve bins each finished one and the running one carry their value`() {
        val values = floatArrayOf(96.9f, 49.9f, nan, nan, nan)
        view.setRun(planned = 5, frameIndex = 3, framePercent = 62.5f, convergence = values)

        // A frame that kept no points has no value; one just under the gate never reads 50.
        assertEquals(listOf(96, 49, null, 62, null), ConvergenceBinsView.labelsFor(view.bins))
    }

    @Test
    fun `over twelve bins nothing is labelled`() {
        val values = FloatArray(13) { 95f }
        view.setRun(planned = 12, frameIndex = 12, framePercent = 100f, convergence = values)
        assertEquals(12, ConvergenceBinsView.labelsFor(view.bins).count { it != null })

        view.setRun(planned = 13, frameIndex = 13, framePercent = 100f, convergence = values)
        assertTrue(ConvergenceBinsView.labelsFor(view.bins).all { it == null })
    }

    @Test
    fun `a few bins are capped in width and centred, eight dp apart`() {
        val density = 2f
        val width = 300f * density
        val slots = ConvergenceBinsView.slotsFor(count = 5, width = width, density = density)

        assertEquals(ConvergenceBinsView.MAX_BIN_DP * density, slots.binWidth, 0.001f)
        assertEquals(ConvergenceBinsView.CAPPED_GAP_DP * density, slots.gap, 0.001f)
        val group = 5 * slots.binWidth + 4 * slots.gap
        assertEquals("as much room left of the group as right", width - group, 2 * slots.start, 0.001f)
    }

    @Test
    fun `capped bins close their gap rather than overflow`() {
        val density = 1f
        // 9 bins in 200 px: 19.6 px wide at the uncapped 3 px gap, but 9 × 18 + 8 × 8 = 226 px would overflow.
        val slots = ConvergenceBinsView.slotsFor(count = 9, width = 200f, density = density)

        assertEquals(18f, slots.binWidth, 0.001f)
        assertTrue(slots.gap in 3f..8f)
        assertEquals(200f, 2 * slots.start + 9 * slots.binWidth + 8 * slots.gap, 0.001f)
        assertTrue(slots.start >= 0f)
    }

    @Test
    fun `many bins fill the width with the narrow gap`() {
        val density = 1f
        val slots = ConvergenceBinsView.slotsFor(count = 100, width = 300f, density = density)

        assertEquals(0f, slots.start, 0f)
        assertEquals(1f, slots.gap, 0.001f)
        assertEquals(300f, 100 * slots.binWidth + 99 * slots.gap, 0.001f)
    }

    @Test
    fun `the axis labels carry their percent unit`() {
        // Readiness AE1-112: the y axis read '100' and '50' with no unit.
        assertEquals(listOf("100%", "50%"), ConvergenceBinsView.axisLabels())
    }
}
