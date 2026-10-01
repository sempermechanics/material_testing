package com.indicvision.semper.results

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.indicvision.semper.report.ReportBuilder
import com.indicvision.semper.report.VisualizationEngine
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The colour bar baked into report and share PNGs must be the ramp the map is
 * drawn with, or a value read off the bar is wrong. Native graphics, so the
 * gradient is really rasterised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportColorBarTest {

    private companion object {
        const val SIZE = 400

        /** One ramp step is ~4 levels; gradient interpolation and dithering add a little. */
        const val TOLERANCE = 12
    }

    @Test
    fun `the bar shows the map's colour at every height`() {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        ReportBuilder.bakeAnnotationsToCanvas(
            Canvas(bitmap),
            SIZE,
            SIZE,
            minValRaw = 0f,
            maxValRaw = 1f,
            typeString = "U",
            unit = "px",
            maxIdx = -1,
            minIdx = -1,
            dataArray = FloatArray(0),
        )

        // The bar's geometry, as bakeAnnotationsToCanvas lays it out.
        val textSize = SIZE * 0.025f
        val padding = SIZE * 0.02f
        val barWidth = SIZE * 0.03f
        val barHeight = SIZE * 0.5f
        val barLeft = SIZE - padding - barWidth - textSize * 4.5f
        val barTop = (SIZE - barHeight) / 2f
        val barBottom = barTop + barHeight
        val x = (barLeft + barWidth / 2f).toInt()

        val ramp = VisualizationEngine.rampColors()
        val last = ramp.size - 1
        val misses = mutableListOf<String>()
        // Inside the 3 px outline.
        for (y in (barTop + 4).toInt()..(barBottom - 4).toInt()) {
            val t = (barBottom - (y + 0.5f)) / barHeight
            // What the map paints for a value at t of the scale.
            val expected = ramp[(t * last).toInt().coerceIn(0, last)]
            val actual = bitmap.getPixel(x, y)
            val worst = maxOf(
                abs(Color.red(actual) - Color.red(expected)),
                abs(Color.green(actual) - Color.green(expected)),
                abs(Color.blue(actual) - Color.blue(expected)),
            )
            if (worst > TOLERANCE) misses += "t=%.3f off by %d".format(t, worst)
        }
        assertTrue("bar differs from the map: ${misses.take(5)} (${misses.size} rows)", misses.isEmpty())
    }
}
