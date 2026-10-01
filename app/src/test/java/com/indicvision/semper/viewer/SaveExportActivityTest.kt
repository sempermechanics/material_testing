package com.indicvision.semper.viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.ui.viewer.SaveExportActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * The "Save to Files" proxy over a rotation: one picker, never two, and the
 * picked document still gets the file whichever instance the answer reaches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveExportActivityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val CSV = "image,x,y\nFrame_1,0,0\n"
    }

    private fun staged(): File = temp.newFile("export.csv").apply { writeText(CSV) }

    private fun intent(file: File): Intent =
        SaveExportActivity.intent(ApplicationProvider.getApplicationContext(), file, "text/csv")

    private fun idleUntil(activity: Activity, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            shadowOf(activity.mainLooper).idle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "timed out waiting on the save" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `a rotation while the picker is open opens no second picker`() {
        val controller = Robolectric.buildActivity(SaveExportActivity::class.java, intent(staged())).setup()
        val picker = shadowOf(controller.get()).nextStartedActivityForResult
        assertNotNull(picker)
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, picker.intent.action)

        val rebuilt = controller.recreate().get()

        assertNull("a second picker", shadowOf(rebuilt).nextStartedActivityForResult)
        assertTrue("still waiting on the first picker", !rebuilt.isFinishing)
    }

    @Test
    fun `the first picker's answer reaches the recreated proxy and saves`() {
        val controller = Robolectric.buildActivity(SaveExportActivity::class.java, intent(staged())).setup()
        val picker = shadowOf(controller.get()).nextStartedActivityForResult
        val rebuilt = controller.recreate().get()
        val dest = File(temp.root, "picked.csv")

        rebuilt.activityResultRegistry.dispatchResult(
            picker.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(dest)),
        )
        idleUntil(rebuilt) { rebuilt.isFinishing }

        assertEquals(CSV, dest.readText())
        assertEquals(rebuilt.getString(R.string.save_success), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a rotation mid-copy finishes the copy instead of leaving the proxy up`() {
        val controller = Robolectric.buildActivity(SaveExportActivity::class.java, intent(staged())).setup()
        val first = controller.get()
        val picker = shadowOf(first).nextStartedActivityForResult
        val dest = File(temp.root, "picked.csv")
        first.activityResultRegistry.dispatchResult(
            picker.requestCode,
            Activity.RESULT_OK,
            Intent().setData(Uri.fromFile(dest)),
        )

        // Before the copy's result is back on the main thread.
        val rebuilt = controller.recreate().get()
        idleUntil(rebuilt) { rebuilt.isFinishing }

        assertNull("no new picker", shadowOf(rebuilt).nextStartedActivityForResult)
        assertEquals(CSV, dest.readText())
    }
}
