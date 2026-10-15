package com.sempermechanics.semper.results

import android.app.Application
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.field.ValueRange
import com.sempermechanics.semper.fixtures.DeformedFrameFixture
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.ONLY_DEFORMED
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.ONLY_REFERENCE
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.PHOTO_COLOR
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.REFERENCE_COLOR
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.assertColor
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.blend
import com.sempermechanics.semper.report.ReportBuilder
import com.sempermechanics.semper.report.ReportData
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.share.ViewerReportFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The PDF report's field images draw a frame the way the viewer draws it
 * (ADR-011, TD-139): on the frame's own photo with the viewer's displaced map
 * ([VisualizationEngine.generateFrameHeatmap]), else on the reference with the
 * reference-position map. Through [ViewerReportFactory.buildReportData], the
 * path the share sheet's PDF takes. Native graphics; the field images are
 * RGB_565 after `compressForPdf`, hence the wider tolerance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportDeformedFrameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val data = DeformedFrameFixture.field()

    /** The report's own composite alpha (`ReportBuilder.buildReport`). */
    private val reportAlpha = 180

    /** RGB_565 keeps 5–6 bits a channel. */
    private val tolerance = 9

    private fun report(framePhoto: File): ReportData {
        val reference = DeformedFrameFixture.writePhoto(File(tmp.root, "reference.png"), REFERENCE_COLOR)
        return checkNotNull(
            ViewerReportFactory.buildReportData(DeformedFrameFixture.source(reference, framePhoto), 0, data),
        )
    }

    private fun exxImage(report: ReportData) = report.fieldResults.single { it.fieldKey == "Exx" }.bakedHeatmap

    private fun viewerMapPixel(displaced: Boolean, at: Pair<Int, Int>): Int {
        val map = VisualizationEngine.generateFrameHeatmap(
            displaced,
            data,
            DeformedFrameFixture.W,
            DeformedFrameFixture.H,
            DicResult.IDX_EXX,
            DeformedFrameFixture.STEP,
            maxLongEdge = VisualizationEngine.REPORT_MAX_EDGE,
        ).bitmap
        val pixel = map.getPixel(at.first, at.second)
        map.recycle()
        assertEquals("the viewer's map covers $at", 0xFF, Color.alpha(pixel))
        return pixel
    }

    @Test
    fun `a frame with its own photo has its field images on it, the map displaced`() {
        val photo = DeformedFrameFixture.writePhoto(File(tmp.root, "f0.png"), PHOTO_COLOR)
        val image = exxImage(report(photo))
        assertEquals(DeformedFrameFixture.W, image.width)

        val (ax, ay) = ONLY_DEFORMED
        assertColor(
            "under the displaced map",
            blend(viewerMapPixel(displaced = true, ONLY_DEFORMED), PHOTO_COLOR, reportAlpha),
            image.getPixel(ax, ay),
            tolerance,
        )
        val (bx, by) = ONLY_REFERENCE
        assertColor("where the points no longer are, the photo alone", PHOTO_COLOR, image.getPixel(bx, by), tolerance)
    }

    @Test
    fun `on the frame's photo the MAX and MIN markers sit where their points moved to`() {
        // MAX at (100, 100) moved by (-50, 0); MIN at (60, 130) moved by (-50, 0).
        val points = FloatArray(2 * DicResult.STRIDE)
        points[DicResult.IDX_X] = 100f
        points[DicResult.IDX_Y] = 100f
        points[DicResult.STRIDE + DicResult.IDX_X] = 60f
        points[DicResult.STRIDE + DicResult.IDX_Y] = 130f
        for (p in 0 until 2) points[p * DicResult.STRIDE + DicResult.IDX_U] = -50f
        fun markerAt(displaced: Boolean, x: Int, y: Int): Boolean {
            val canvasBitmap = createBitmap(DeformedFrameFixture.W, DeformedFrameFixture.H)
            ReportBuilder.bakeAnnotationsToCanvas(
                Canvas(canvasBitmap),
                DeformedFrameFixture.W,
                DeformedFrameFixture.H,
                ValueRange(0f, 1f),
                ReportBuilder.FieldExtrema(maxIdx = 0, minIdx = DicResult.STRIDE),
                points,
                ReportBuilder.FieldAnnotation(typeString = "U", unit = "px", displaced = displaced),
            )
            return Color.alpha(canvasBitmap.getPixel(x, y)) != 0
        }
        assertTrue("MAX on the moved point", markerAt(displaced = true, 50, 100))
        assertFalse("not at the reference", markerAt(displaced = true, 100, 100))
        assertTrue("MIN on the moved point", markerAt(displaced = true, 10, 130))
        // On the reference, the markers stay at the reference positions.
        assertTrue(markerAt(displaced = false, 100, 100))
        assertFalse(markerAt(displaced = false, 50, 100))
    }

    @Test
    fun `a frame whose photo is gone keeps the reference and the reference-position map`() {
        val image = exxImage(report(File(tmp.root, "reclaimed.png")))

        val (bx, by) = ONLY_REFERENCE
        assertColor(
            "under the reference-position map",
            blend(viewerMapPixel(displaced = false, ONLY_REFERENCE), REFERENCE_COLOR, reportAlpha),
            image.getPixel(bx, by),
            tolerance,
        )
        val (ax, ay) = ONLY_DEFORMED
        assertColor("beyond the reference grid, the reference", REFERENCE_COLOR, image.getPixel(ax, ay), tolerance)
    }
}
