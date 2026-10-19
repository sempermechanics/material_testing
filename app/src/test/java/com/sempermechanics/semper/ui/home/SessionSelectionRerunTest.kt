package com.sempermechanics.semper.ui.home

import android.os.Looper
import android.view.View
import com.sempermechanics.semper.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf

/**
 * Home's Re-run (owner decision, 2026-10-15): a new analysis from one saved
 * row's photos and settings, offered in the selection bar for exactly one
 * row. A row whose frames are not on this phone says why and starts nothing.
 */
class SessionSelectionRerunTest : SessionSelectionFixture() {

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `Re-run is offered for exactly one selection`() {
        list(a, b)
        assertEquals("nothing selected", View.GONE, rerun.visibility)

        controller.startSelection(a)
        assertEquals(View.VISIBLE, rerun.visibility)

        controller.toggleSelection(b)
        assertEquals("two selected", View.GONE, rerun.visibility)
        controller.rerunSelected()
        assertTrue("nothing re-run for two rows", reruns.isEmpty())

        controller.toggleSelection(a)
        assertEquals(View.VISIBLE, rerun.visibility)
    }

    @Test
    fun `Re-run hands the one row to Home and ends the selection`() {
        list(a, b)
        controller.startSelection(b)

        rerun.performClick()

        assertEquals(listOf("b"), reruns)
        assertFalse(controller.inSelectionMode)
    }

    @Test
    fun `a backed-up row that is not on this phone says to restore it first`() {
        val cloudOnly = record("c1", local = false, cloud = true)
        list(cloudOnly)
        controller.startSelection(cloudOnly)

        rerun.performClick()
        idle()

        assertTrue(reruns.isEmpty())
        assertEquals(activity.getString(R.string.rerun_unavailable_title), dialogTitle())
        assertEquals(activity.getString(R.string.rerun_photos_in_cloud_body), dialogMessage())
        assertTrue("the selection stays", controller.inSelectionMode)
    }

    @Test
    fun `a row with no photos anywhere this account can restore says they are not here`() {
        restoreAllowed = false
        val cloudOnly = record("c2", local = false, cloud = true)
        list(cloudOnly)
        controller.startSelection(cloudOnly)

        rerun.performClick()
        idle()

        assertTrue(reruns.isEmpty())
        assertEquals(activity.getString(R.string.rerun_photos_missing_body), dialogMessage())
    }
}
