package com.indicvision.semper.ui.analysis

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A [VsgPlotView.Mark] (the yield point) is drawn in its colour with a legend,
 * and only when the axes reach it: it never widens them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VsgPlotViewMarkTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val curve = VsgPlotView.Series("curve", Color.BLUE, listOf(0f to 0f, 10f to 10f, 20f to 12f))

    private fun render(marks: List<VsgPlotView.Mark>): Bitmap = VsgPlotView(context).run {
        setData(listOf(curve), "strain", "stress", marks = marks)
        renderToBitmap(W, H)
    }

    private fun Bitmap.count(color: Int): Int {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.count { it == color }
    }

    @Test
    fun `a mark inside the axes is drawn in its colour`() {
        val marked = render(listOf(VsgPlotView.Mark(10f, 10f, "Yield 10 MPa", MARK)))

        assertEquals(0, render(emptyList()).count(MARK))
        // The diamond on the curve and the one in the legend.
        assertTrue(marked.count(MARK) > 2 * MIN_DIAMOND_PX)
    }

    @Test
    fun `a mark outside the axes draws nothing, not even its legend, and does not widen them`() {
        val plain = render(emptyList())

        val outside = render(listOf(VsgPlotView.Mark(100f, 100f, "Yield 100 MPa", MARK)))

        assertTrue(outside.sameAs(plain))
    }

    private companion object {
        const val W = 800
        const val H = 500
        const val MARK = 0xFF00C853.toInt()

        /** A 6 dp diamond is well over this many pixels at any density. */
        const val MIN_DIAMOND_PX = 20
    }
}
