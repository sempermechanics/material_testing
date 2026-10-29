package com.sempermechanics.semper.ui.viewer.inspect

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.FieldHistogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * At font scale 2.0 the Details histogram cut the y tick's leading digit
 * ("2216" read "216"), drew it into the "points" title and cut the x labels in
 * half (readiness AE1-104). Its gutters now come from the measured text, and the
 * view grows with the text, so every label is whole at any font scale.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class FieldHistogramFontScaleTest {

    private class Label(val text: String, val box: RectF)

    /** Keeps each label's drawn box, from its anchor, alignment and font metrics. */
    private class BoxCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val labels = mutableListOf<Label>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            val w = paint.measureText(text)
            val left = when (paint.textAlign) {
                Paint.Align.RIGHT -> x - w
                Paint.Align.CENTER -> x - w / 2
                else -> x
            }
            val fm = paint.fontMetrics
            labels += Label(text, RectF(left, y + fm.ascent, left + w, y + fm.descent))
        }
    }

    private fun drawAt(scale: Float): Pair<List<Label>, Pair<Int, Int>> {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(app.resources.configuration).apply { fontScale = scale }
        val context = ContextThemeWrapper(app.createConfigurationContext(config), R.style.Theme_Semper)
        val view = FieldHistogramView(context)
        view.setHistogram(
            FieldHistogram(min = 0.49503f, max = 0.5047f, counts = intArrayOf(40, 2216, 900, 12), n = 3168),
            "px",
        )
        val density = context.resources.displayMetrics.density
        val w = (SHEET_W_DP * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(PARENT_H_PX, View.MeasureSpec.AT_MOST),
        )
        val h = view.measuredHeight
        view.layout(0, 0, w, h)
        val canvas = BoxCanvas(Bitmap.createBitmap(w, h.coerceAtLeast(1), Bitmap.Config.ARGB_8888))
        view.draw(canvas)
        return canvas.labels to (w to h)
    }

    @Test
    fun `every label is whole and clear of the y title at font scale 1 and 2`() {
        for (scale in listOf(1f, 2f)) {
            val (labels, size) = drawAt(scale)
            val (w, h) = size
            assertEquals("fs $scale labels ${labels.map { it.text }}", 5, labels.size)
            for (label in labels) {
                val b = label.box
                assertTrue("fs $scale '${label.text}' $b outside ${w}x$h", b.left >= 0f && b.right <= w)
                assertTrue("fs $scale '${label.text}' $b outside ${w}x$h", b.top >= 0f && b.bottom <= h)
            }
            val title = labels.single { it.text == TITLE }
            val peak = labels.single { it.text == "2216" }
            assertTrue("fs $scale peak ${peak.box} runs into the title ${title.box}", peak.box.top >= title.box.bottom)
        }
    }

    @Test
    fun `the view wraps the plot and grows with the font scale`() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val density = app.resources.displayMetrics.density
        val (_, small) = drawAt(1f)
        val (_, large) = drawAt(2f)
        assertTrue("fs 1 height ${small.second / density} dp", small.second <= MAX_FS1_DP * density)
        assertTrue("fs 2 height ${large.second} not above fs 1 ${small.second}", large.second > small.second)
    }

    private companion object {
        const val TITLE = "points"

        /** The Details sheet's content width on a 360 dp phone. */
        const val SHEET_W_DP = 328

        /** The scrolling sheet offers far more height than the plot needs. */
        const val PARENT_H_PX = 4000

        /** About the old fixed 150 dp at font scale 1.0. */
        const val MAX_FS1_DP = 170
    }
}
