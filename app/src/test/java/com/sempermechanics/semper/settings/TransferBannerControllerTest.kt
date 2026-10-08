package com.sempermechanics.semper.settings

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.dynamicanimation.animation.SpringFrames
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.common.transfer.TransferBannerController
import com.sempermechanics.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController

/**
 * Banner chrome under a real themed Activity (Material indicators need a theme).
 * SettingsActivity already binds one controller; we exercise a second on a
 * freshly inflated copy of the banner layout hosted in the same activity.
 */
@RunWith(RobolectricTestRunner::class)
class TransferBannerControllerTest {

    /**
     * Every Settings a test opens, destroyed after it: a live one left behind
     * would still observe the account-deletion and sign-out runs of later tests.
     */
    private val built = mutableListOf<ActivityController<SettingsActivity>>()

    /**
     * The banner here is never in a window, so nothing ends its bar's spring,
     * and one still moving would hold its Settings for the rest of the run
     * (TD-200).
     */
    @After
    fun destroySettings() {
        built.forEach { runCatching { it.pause().stop().destroy() } }
        SpringFrames.endAll()
    }

    private lateinit var activity: SettingsActivity
    private lateinit var root: View
    private lateinit var controller: TransferBannerController

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().also { built += it }.get()
        root = activity.layoutInflater.inflate(R.layout.view_transfer_banner, null)
        controller = TransferBannerController(root)
    }

    private val bar get() = root.findViewById<LinearProgressIndicator>(R.id.transferProgress)

    @Test
    fun `single transfer shows progress without pager chrome`() {
        controller.upsert(
            TransferBannerController.Transfer(id = "a", title = "Export my data", percent = 40),
        )
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.btnTransferPrev).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.btnTransferNext).visibility)
        assertEquals(View.GONE, root.findViewById<View>(R.id.tvTransferPage).visibility)
    }

    @Test
    fun `two transfers show arrows and page indicator`() {
        controller.upsert(TransferBannerController.Transfer(id = "a", title = "A", percent = 10))
        controller.upsert(TransferBannerController.Transfer(id = "b", title = "B", percent = 20))
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btnTransferPrev).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.btnTransferNext).visibility)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.tvTransferPage).visibility)
        assertTrue(root.findViewById<TextView>(R.id.tvTransferPage).text.contains("2"))
    }

    @Test
    fun `cancel removes only the targeted transfer`() {
        var cancelled = false
        controller.upsert(
            TransferBannerController.Transfer(
                id = "a",
                title = "A",
                percent = 10,
                onCancel = {
                    cancelled = true
                    controller.remove("a")
                },
            ),
        )
        controller.upsert(TransferBannerController.Transfer(id = "b", title = "B", percent = 50))
        // Newest page is focused; move to first then cancel.
        root.findViewById<View>(R.id.btnTransferPrev).performClick()
        root.findViewById<View>(R.id.btnTransferCancel).performClick()
        assertTrue(cancelled)
        assertEquals(1, controller.size())
        assertFalse(controller.contains("a"))
        assertTrue(controller.contains("b"))
    }

    @Test
    fun `progress from a worker thread updates on the main thread`() {
        controller.upsert(TransferBannerController.Transfer(id = "a", title = "A", percent = 1))
        val worker = Thread {
            controller.updateProgress("a", 50, "halfway")
        }
        worker.start()
        worker.join()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(
            "halfway",
            root.findViewById<TextView>(R.id.tvTransferStatus).text.toString(),
        )
    }

    @Test
    fun `a banner out of any window keeps its spring until the test ends it`() {
        controller.upsert(TransferBannerController.Transfer(id = "a", title = "A", percent = 40))
        val drawable = checkNotNull(bar.progressDrawable)
        assertTrue(SpringFrames.running(drawable))

        SpringFrames.step()
        assertTrue("nothing asked this spring to end", SpringFrames.running(drawable))

        SpringFrames.endAll()
        assertFalse(SpringFrames.running(drawable))
        assertTrue("the handler would not ask for frames again", SpringFrames.idle)
    }

    @Test
    fun `a banner taken out of its window ends its spring on the next frames`() {
        val host = activity.findViewById<ViewGroup>(android.R.id.content)
        host.addView(root)
        controller.upsert(TransferBannerController.Transfer(id = "a", title = "A", percent = 40))
        val drawable = checkNotNull(bar.progressDrawable)
        assertTrue("the bar springs while shown", SpringFrames.running(drawable))

        // Detaching jumps a view's drawables to their current state, which
        // asks Material's spring to end on its next frame.
        host.removeView(root)
        SpringFrames.step()
        assertFalse("a spring outlived the banner", SpringFrames.running(drawable))
    }
}
