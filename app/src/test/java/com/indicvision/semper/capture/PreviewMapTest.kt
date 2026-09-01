package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.PreviewMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The focus tap is the one place a coordinate error costs a run without
 * failing: every wrong answer here is still a valid point inside the frame, so
 * the camera focuses somewhere the user did not tap and nothing reports it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewMapTest {

    private companion object {
        const val EPS = 0.5f
        const val NORM_EPS = 1e-3f

        /** A 4:3 sensor buffer inside a tall portrait view, turned upright. */
        const val VIEW_W = 1080
        const val VIEW_H = 2400
        const val BUF_W = 1280
        const val BUF_H = 960
    }

    private fun portraitMap(rotation: Int = 90) =
        PreviewMap.of(VIEW_W, VIEW_H, BUF_W, BUF_H, rotation)!!

    @Test
    fun `a tap comes back as the point the ring is drawn on`() {
        // The round trip is the whole contract: what the user taps is where the
        // marker goes, and where the marker is is what the camera focused on.
        val map = portraitMap()
        val centre = map.viewPointOf(0.5f, 0.5f)
        val back = map.uprightPointAt(centre.x, centre.y)
        assertNotNull(back)
        assertEquals(0.5f, back!!.x, NORM_EPS)
        assertEquals(0.5f, back.y, NORM_EPS)
    }

    @Test
    fun `an off-centre point round-trips too, not just the fixed point of the turn`() {
        val map = portraitMap()
        val point = map.viewPointOf(0.2f, 0.75f)
        val back = map.uprightPointAt(point.x, point.y)!!
        assertEquals(0.2f, back.x, NORM_EPS)
        assertEquals(0.75f, back.y, NORM_EPS)
    }

    @Test
    fun `the centre of the view is the centre of the picture`() {
        val point = portraitMap().viewPointOf(0.5f, 0.5f)
        assertEquals(VIEW_W / 2f, point.x, EPS)
        assertEquals(VIEW_H / 2f, point.y, EPS)
    }

    @Test
    fun `a tap on a letterbox bar is refused rather than clamped`() {
        // 1280x960 turned upright is 960 wide by 1280 tall, fit to the 1080
        // width: 1080x1440 centred in a 2400-tall view, so there are 480px
        // bars top and bottom. A tap in one is not a focus request.
        val map = portraitMap()
        assertNull("top bar", map.uprightPointAt(VIEW_W / 2f, 10f))
        assertNull("bottom bar", map.uprightPointAt(VIEW_W / 2f, VIEW_H - 10f))
    }

    @Test
    fun `a tap just inside the frame is accepted`() {
        val map = portraitMap()
        val top = map.uprightPointAt(VIEW_W / 2f, (VIEW_H - 1440) / 2f + 5f)
        assertNotNull("just below the top bar", top)
    }

    @Test
    fun `the turn changes the shape of the picture on screen, not which corner is which`() {
        // Upright (0,0) is the top-left of the *displayed content* whichever way
        // the buffer was turned — that is what turning it upright means. What
        // the turn changes is the content's shape, and therefore how much
        // letterbox there is, so that is what pins it.
        //
        // 1280x960 turned a quarter is 960x1280, width-limited to 1080x1440 and
        // centred in a 2400-tall view: bars of 480. Left alone it is 1080x810,
        // with bars of 795. A dropped rotation would show up here.
        val turned = portraitMap()
        assertCorners(turned, top = 480f, bottom = 1920f)

        val unturned = PreviewMap.of(VIEW_W, VIEW_H, BUF_W, BUF_H, 0)!!
        assertCorners(unturned, top = 795f, bottom = 1605f)
    }

    private fun assertCorners(map: PreviewMap, top: Float, bottom: Float) {
        val topLeft = map.viewPointOf(0f, 0f)
        val bottomRight = map.viewPointOf(1f, 1f)
        assertEquals("left", 0f, topLeft.x, EPS)
        assertEquals("top", top, topLeft.y, EPS)
        assertEquals("right", VIEW_W.toFloat(), bottomRight.x, EPS)
        assertEquals("bottom", bottom, bottomRight.y, EPS)
    }

    @Test
    fun `every quarter turn round-trips a tap`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val map = PreviewMap.of(VIEW_W, VIEW_H, BUF_W, BUF_H, rotation)!!
            val point = map.viewPointOf(0.3f, 0.6f)
            val back = map.uprightPointAt(point.x, point.y)
            assertNotNull("$rotation dropped the point", back)
            assertEquals("$rotation x", 0.3f, back!!.x, NORM_EPS)
            assertEquals("$rotation y", 0.6f, back.y, NORM_EPS)
        }
    }

    @Test
    fun `no size yet means no map, rather than a map that answers wrongly`() {
        assertNull(PreviewMap.of(0, VIEW_H, BUF_W, BUF_H, 90))
        assertNull(PreviewMap.of(VIEW_W, VIEW_H, BUF_W, 0, 90))
    }
}
