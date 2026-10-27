package com.sempermechanics.semper.ui.viewer.inspect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.widget.TextViewCompat
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.field.FieldHistogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Details sheet's histogram labels are set in the app's type scale, the caption
 * the sheet's other small text uses, not monospace (readiness AE1V-025). Their
 * decimals are TD-268's and stay as they are.
 */
@RunWith(RobolectricTestRunner::class)
class FieldHistogramViewTest {

    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Semper)

    private class Label(val text: String, val typeface: Typeface?, val size: Float)

    /** Keeps each label the view draws, with the type it is drawn in. */
    private class LabelCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val labels = mutableListOf<Label>()

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            labels += Label(text, paint.typeface, paint.textSize)
        }
    }

    private fun drawnLabels(): List<Label> {
        val view = FieldHistogramView(context)
        view.setHistogram(FieldHistogram(min = 0.5f, max = 2.25f, counts = intArrayOf(1, 4, 9, 3), n = 17), "%")
        val w = 320
        val h = 120
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val canvas = LabelCanvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
        view.draw(canvas)
        return canvas.labels
    }

    @Test
    fun `the histogram's labels are set in the caption, not monospace`() {
        val caption = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Semper_Caption)
        }.paint
        val labels = drawnLabels()

        // 0 and the peak on y, the y title, min and max on x.
        assertEquals(labels.map { it.text }.toString(), 5, labels.size)
        for (label in labels) {
            assertNotEquals("'${label.text}' is monospace", Typeface.MONOSPACE, label.typeface)
            assertEquals("'${label.text}' typeface", caption.typeface, label.typeface)
            val captionPx = context.resources.getDimension(R.dimen.text_caption)
            assertEquals("'${label.text}' size", captionPx, label.size, 0.01f)
        }
        // The decimals are unchanged (TD-268): the labels still read as before.
        assertTrue(labels.map { it.text }.toString(), labels.any { it.text.endsWith(" %") })
    }
}
