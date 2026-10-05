package com.indicvision.semper.viewer

import android.content.Context
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.ui.viewer.TouchImageView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A horizontal swipe at rest scale scrubs one frame. A flick is seen twice on
 * its ACTION_UP — by the fling detector and by the swipe-distance check — and
 * used to scrub two frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TouchImageViewScrubTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun restingView(scrubs: MutableList<Int>): TouchImageView = TouchImageView(context).apply {
        setImageBitmap(Bitmap.createBitmap(IMAGE_W, IMAGE_H, Bitmap.Config.ARGB_8888))
        setTrueImageDimensions(IMAGE_W, IMAGE_H)
        measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_SIZE, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, VIEW_SIZE, VIEW_SIZE)
        onScrubListener = { scrubs += it }
    }

    /** A one-finger swipe from [fromX] to [toX] along the middle row, [durationMs] long. */
    private fun swipe(view: View, fromX: Float, toX: Float, durationMs: Long) {
        val y = VIEW_SIZE / 2f
        val down = START_MS
        send(view, MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, fromX, y, 0))
        for (i in 1..STEPS) {
            val x = fromX + (toX - fromX) * i / STEPS
            send(view, MotionEvent.obtain(down, down + durationMs * i / STEPS, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        send(view, MotionEvent.obtain(down, down + durationMs, MotionEvent.ACTION_UP, toX, y, 0))
    }

    private fun send(view: View, event: MotionEvent) {
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    @Test
    fun aFlickScrubsOneFrame() {
        val scrubs = mutableListOf<Int>()
        swipe(restingView(scrubs), fromX = 800f, toX = 200f, durationMs = 60)

        assertEquals(listOf(1), scrubs)
    }

    @Test
    fun aSlowSwipeScrubsOneFrame() {
        val scrubs = mutableListOf<Int>()
        swipe(restingView(scrubs), fromX = 800f, toX = 200f, durationMs = 3_000)

        assertEquals(listOf(1), scrubs)
    }

    @Test
    fun aFlickToTheRightGoesBack() {
        val scrubs = mutableListOf<Int>()
        swipe(restingView(scrubs), fromX = 200f, toX = 800f, durationMs = 60)

        assertEquals(listOf(-1), scrubs)
    }

    private companion object {
        const val IMAGE_W = 200
        const val IMAGE_H = 100
        const val VIEW_SIZE = 1000
        const val STEPS = 10
        const val START_MS = 1_000L
    }
}
