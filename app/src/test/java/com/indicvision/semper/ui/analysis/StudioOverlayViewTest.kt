package com.indicvision.semper.ui.analysis

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The ROI editor's coordinate mapping: a 200×100 preview letterboxed into a
 * 400×400 view (image drawn at y 100..300, two view px per preview px) stands
 * for a 2000×1000 photo, so one view px is five image px. Every ROI the engine
 * gets goes through this mapping, and the mask it writes marks which pixels
 * are correlated — an off-by-a-scale here is a wrong analysis, not a glitch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StudioOverlayViewTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var image: ImageView
    private lateinit var overlay: StudioOverlayView
    private val reported = mutableListOf<RectF>()
    private var clock = 0L

    @Before
    fun setUp() {
        image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(BitmapDrawable(context.resources, Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)))
        }
        layout(image, 400, 400)
        overlay = StudioOverlayView(context).apply {
            realImageWidth = 2000
            realImageHeight = 1000
            onRoiChangedListener = { reported += RectF(it) }
        }
        layout(overlay, 400, 400)
        overlay.imageView = image
        clock = SystemClock.uptimeMillis()
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
    }

    private fun touch(action: Int, x: Float, y: Float) {
        overlay.onTouchEvent(MotionEvent.obtain(clock, clock, action, x, y, 0))
    }

    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float) {
        touch(MotionEvent.ACTION_DOWN, x0, y0)
        touch(MotionEvent.ACTION_MOVE, (x0 + x1) / 2f, (y0 + y1) / 2f)
        touch(MotionEvent.ACTION_MOVE, x1, y1)
        touch(MotionEvent.ACTION_UP, x1, y1)
    }

    private fun assertRect(expected: RectF, actual: RectF) {
        assertEquals("left of $actual", expected.left, actual.left, EPS)
        assertEquals("top of $actual", expected.top, actual.top, EPS)
        assertEquals("right of $actual", expected.right, actual.right, EPS)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, EPS)
    }

    // ── Typed (manual) ROI ───────────────────────────────────────────────────

    @Test
    fun `a typed ROI round-trips to the same image pixels`() {
        assertTrue(overlay.applyImageRoi(100, 200, 500, 300))

        assertTrue(overlay.hasValidRoi)
        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
        assertRect(RectF(100f, 200f, 600f, 500f), reported.last())
    }

    @Test
    fun `a typed ROI running off the image is clipped to it`() {
        assertTrue(overlay.applyImageRoi(1900, 900, 500, 500))
        assertRect(RectF(1900f, 900f, 2000f, 1000f), overlay.getRelativeRoi())
    }

    @Test
    fun `a typed ROI with no size is refused`() {
        assertFalse(overlay.applyImageRoi(10, 10, 0, 50))
        assertFalse(overlay.applyImageRoi(10, 10, 50, -1))
        assertFalse(overlay.hasValidRoi)
    }

    @Test
    fun `a typed ROI before the image is laid out is refused`() {
        val early = StudioOverlayView(context).apply {
            realImageWidth = 2000
            realImageHeight = 1000
        }
        assertFalse(early.applyImageRoi(0, 0, 100, 100))
    }

    // ── Drawn ROI ────────────────────────────────────────────────────────────

    @Test
    fun `a drawn ROI is reported in image pixels`() {
        drag(50f, 150f, 250f, 250f)

        assertTrue(overlay.hasValidRoi)
        // View (50,150)-(250,250), image top at y=100, five image px per view px.
        assertRect(RectF(250f, 250f, 1250f, 750f), overlay.getRelativeRoi())
    }

    @Test
    fun `drawing outside the letterboxed image is clamped to it`() {
        drag(-20f, 0f, 500f, 500f)
        assertRect(RectF(0f, 0f, 2000f, 1000f), overlay.getRelativeRoi())
    }

    @Test
    fun `a drag under fifty view px each way leaves no ROI`() {
        drag(100f, 150f, 140f, 190f)
        assertFalse(overlay.hasValidRoi)
    }

    @Test
    fun `dragging inside the ROI moves it, but never off the image`() {
        overlay.applyImageRoi(0, 0, 500, 500) // view (0,100)-(100,200)
        drag(50f, 150f, 1_050f, 150f)
        assertRect(RectF(1500f, 0f, 2000f, 500f), overlay.getRelativeRoi())
    }

    @Test
    fun `a corner drag resizes the ROI down to the fifty px minimum`() {
        overlay.applyImageRoi(0, 0, 500, 500) // bottom-right handle at view (100, 200)
        drag(100f, 200f, 10f, 110f)
        assertRect(RectF(0f, 0f, 250f, 250f), overlay.getRelativeRoi())
    }

    @Test
    fun `in square mode a corner drag keeps the ROI square`() {
        overlay.currentMode = StudioOverlayView.RoiMode.SQUARE
        overlay.applyImageRoi(0, 0, 500, 500)
        drag(100f, 200f, 200f, 220f) // wider than tall

        val roi = overlay.getRelativeRoi()
        assertRect(RectF(0f, 0f, 1000f, 1000f), roi)
        assertEquals(roi.width(), roi.height(), EPS)
    }

    // ── Erase holes ──────────────────────────────────────────────────────────

    @Test
    fun `erase mode punches a hole and keeps the crop`() {
        overlay.applyImageRoi(0, 0, 1000, 1000)
        overlay.isSubtractMode = true
        drag(20f, 120f, 120f, 170f)

        assertTrue(overlay.hasValidRoi)
        assertEquals(1, overlay.holes.size)
        assertRect(RectF(100f, 100f, 600f, 350f), overlay.lastHoleRelative())
        assertRect(RectF(0f, 0f, 1000f, 1000f), overlay.getRelativeRoi())
    }

    @Test
    fun `a typed hole maps like a typed ROI`() {
        assertTrue(overlay.applyImageHole(300, 400, 200, 100))
        assertRect(RectF(300f, 400f, 500f, 500f), overlay.lastHoleRelative())
    }

    @Test
    fun `a fresh crop drag starts over, dropping earlier holes`() {
        overlay.applyImageHole(300, 400, 200, 100)
        drag(50f, 150f, 250f, 250f)

        assertTrue(overlay.holes.isEmpty())
        assertTrue(overlay.lastHoleRelative().isEmpty)
    }

    @Test
    fun `reset clears everything and reports an empty ROI`() {
        overlay.applyImageRoi(0, 0, 500, 500)
        overlay.applyImageHole(100, 100, 100, 100)
        overlay.reset()

        assertFalse(overlay.hasValidRoi)
        assertTrue(overlay.holes.isEmpty())
        assertTrue(reported.last().isEmpty)
    }

    // ── Layout changes and restore ───────────────────────────────────────────

    @Test
    fun `a new letterbox keeps the ROI and holes on the same image pixels`() {
        overlay.applyImageRoi(100, 200, 500, 300)
        overlay.applyImageHole(300, 400, 200, 100)

        layout(image, 400, 200) // toolbar or IME resize: image now fills y 0..200
        overlay.updateImageBounds()

        assertRect(RectF(100f, 200f, 600f, 500f), overlay.getRelativeRoi())
        assertRect(RectF(300f, 400f, 500f, 500f), overlay.lastHoleRelative())
    }

    @Test
    fun `a saved ROI restored before layout is applied once the image is ready`() {
        val restored = StudioOverlayView(context).apply {
            realImageWidth = 2000
            realImageHeight = 1000
        }
        restored.restoreRelativeRoi(RectF(100f, 200f, 600f, 500f))
        assertFalse("nothing to map onto yet", restored.hasValidRoi)

        layout(restored, 400, 400)
        restored.imageView = image

        assertTrue(restored.hasValidRoi)
        assertRect(RectF(100f, 200f, 600f, 500f), restored.getRelativeRoi())
    }

    // ── Mask ─────────────────────────────────────────────────────────────────

    /** Mask at preview resolution (200×100, half a view px per image px) so every byte is checkable. */
    private fun smallMask(configure: StudioOverlayView.() -> Unit): Pair<ByteArray, Int> {
        overlay.realImageWidth = 200
        overlay.realImageHeight = 100
        overlay.configure()
        val bytes = overlay.generateMaskBytes()
        return bytes to bytes.size / 100
    }

    private fun ByteArray.at(stride: Int, x: Int, y: Int): Int = this[y * stride + x].toInt() and 0xFF

    @Test
    fun `the mask covers the whole photo and marks the crop as correlated`() {
        val (mask, stride) = smallMask { applyImageRoi(20, 10, 100, 50) }

        assertTrue("one row per image row, at least one byte per pixel", stride >= 200)
        assertEquals(255, mask.at(stride, 20, 10))
        assertEquals(255, mask.at(stride, 119, 59))
    }

    @Test
    fun `outside the crop stays correlated so edge subsets keep their points`() {
        // The ROI rect bounds the grid; the engine drops any point whose subset
        // touches a void pixel, so a void background would eat the crop's edge (TD-74).
        val (mask, stride) = smallMask { applyImageRoi(20, 10, 100, 50) }

        assertEquals(255, mask.at(stride, 0, 0))
        assertEquals(255, mask.at(stride, 19, 30))
        assertEquals(255, mask.at(stride, 120, 30))
        assertEquals(255, mask.at(stride, 60, 60))
        assertEquals(255, mask.at(stride, 199, 99))
    }

    @Test
    fun `a hole voids its pixels inside the crop`() {
        val (mask, stride) = smallMask {
            applyImageRoi(0, 0, 200, 100)
            applyImageHole(50, 25, 20, 10)
        }

        assertEquals(0, mask.at(stride, 55, 30))
        assertEquals(255, mask.at(stride, 49, 30))
        assertEquals(255, mask.at(stride, 70, 30))
    }

    @Test
    fun `holes with no crop mean the full image minus the holes`() {
        val (mask, stride) = smallMask { applyImageHole(0, 0, 10, 10) }

        assertEquals(0, mask.at(stride, 5, 5))
        assertEquals(255, mask.at(stride, 150, 80))
    }

    private companion object {
        const val EPS = 1e-2f
    }
}
