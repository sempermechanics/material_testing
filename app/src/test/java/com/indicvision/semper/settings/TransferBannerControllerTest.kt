package com.indicvision.semper.settings

import android.view.View
import android.widget.TextView
import com.indicvision.semper.R
import com.indicvision.semper.ui.common.TransferBannerController
import com.indicvision.semper.ui.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Banner chrome under a real themed Activity (Material indicators need a theme).
 * SettingsActivity already binds one controller; we exercise a second on a
 * freshly inflated copy of the banner layout hosted in the same activity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferBannerControllerTest {

    private lateinit var root: View
    private lateinit var controller: TransferBannerController

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        root = activity.layoutInflater.inflate(R.layout.view_transfer_banner, null)
        controller = TransferBannerController(root)
    }

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
}
