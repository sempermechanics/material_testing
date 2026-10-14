package com.sempermechanics.semper.ui.viewer.share

import android.app.Application
import android.graphics.Color
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.fixtures.DeformedFrameFixture
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.ONLY_DEFORMED
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.ONLY_REFERENCE
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.PHOTO_COLOR
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.REFERENCE_COLOR
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.assertColor
import com.sempermechanics.semper.fixtures.DeformedFrameFixture.blend
import com.sempermechanics.semper.report.VisualizationEngine
import com.sempermechanics.semper.ui.viewer.share.ShareExportBuilder.Companion.HEATMAP_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The field PNG export draws a frame the way the viewer draws it (ADR-011,
 * TD-139): on the frame's own photo with the map at the displaced positions,
 * the map being [VisualizationEngine.generateFrameHeatmap], the viewer's own
 * render; on the reference with the reference-position map when the photo is
 * gone. Native graphics, so the composite is really drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FieldImageExportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val data = DeformedFrameFixture.field()

    private fun export(framePhoto: File): FieldImageExport {
        val reference = DeformedFrameFixture.writePhoto(File(tmp.root, "reference.png"), REFERENCE_COLOR)
        val snapshot = ShareCenter.Snapshot(
            data = data,
            batchFiles = emptyList(),
            baseName = "spec",
            frameIndex = 0,
            dataIndex = DicResult.IDX_EXX,
            typeString = "Exx",
            summary = null,
            summaryBounds = emptyMap(),
            reportSource = DeformedFrameFixture.source(reference, framePhoto),
        )
        return FieldImageExport(snapshot, tmp.newFolder())
    }

    /** The viewer's map of the frame ([com.sempermechanics.semper.ui.viewer.ViewerScaleController]), uncapped here. */
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
    fun `a frame with its own photo is exported on it with the displaced map`() {
        val photo = DeformedFrameFixture.writePhoto(File(tmp.root, "f0.png"), PHOTO_COLOR)
        val out = export(photo).renderAnnotated(data, DicResult.IDX_EXX, "Exx", 0)

        val (ax, ay) = ONLY_DEFORMED
        assertColor(
            "under the displaced map",
            blend(viewerMapPixel(displaced = true, ONLY_DEFORMED), PHOTO_COLOR, HEATMAP_ALPHA),
            out.getPixel(ax, ay),
        )
        val (bx, by) = ONLY_REFERENCE
        assertColor("where the points no longer are, the photo alone", PHOTO_COLOR, out.getPixel(bx, by))
        out.recycle()
    }

    @Test
    fun `a frame whose photo is gone is exported on the reference, as the viewer falls back`() {
        val out = export(File(tmp.root, "reclaimed.png")).renderAnnotated(data, DicResult.IDX_EXX, "Exx", 0)

        val (bx, by) = ONLY_REFERENCE
        assertColor(
            "under the reference-position map",
            blend(viewerMapPixel(displaced = false, ONLY_REFERENCE), REFERENCE_COLOR, HEATMAP_ALPHA),
            out.getPixel(bx, by),
        )
        val (ax, ay) = ONLY_DEFORMED
        assertColor("beyond the reference grid, the reference alone", REFERENCE_COLOR, out.getPixel(ax, ay))
        out.recycle()
    }

    @Test
    fun `every field of a frame shares its one photo`() {
        val photo = DeformedFrameFixture.writePhoto(File(tmp.root, "f0.png"), PHOTO_COLOR)
        val images = export(photo)
        val cache = FieldImageExport.FrameBaseCache()
        val first = images.renderAnnotated(data, DicResult.IDX_EXX, "Exx", 0, cache)
        val second = images.renderAnnotated(data, DicResult.IDX_EYY, "Eyy", 0, cache)
        assertEquals(1, cache.bases.size)
        val base = cache.bases.values.single()
        assertEquals(true, base.displaced)
        val (bx, by) = ONLY_REFERENCE
        assertColor("Exx", PHOTO_COLOR, first.getPixel(bx, by))
        assertColor("Eyy", PHOTO_COLOR, second.getPixel(bx, by))
        images.recycleBaseCache(cache)
        assertEquals(true, base.bitmap.isRecycled)
        first.recycle()
        second.recycle()
    }
}
