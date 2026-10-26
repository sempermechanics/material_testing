package com.sempermechanics.semper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Specimen images keep their true pixels in the dark theme (readiness AE1V-011):
 * the app is force-dark, so every view that shows a photo or a thumbnail of one
 * opts out with android:forceDarkAllowed="false", as the viewer and the ROI
 * studio already do. Read from the layout XML: Robolectric does not model force
 * dark on a view.
 */
class SpecimenThumbsTest {

    private val layouts = File("src/main/res/layout")

    /** The opening tag of the view with [id] in [layout]. */
    private fun tagOf(layout: String, id: String): String {
        val xml = layouts.resolve("$layout.xml").readText()
        val at = xml.indexOf("android:id=\"@+id/$id\"")
        check(at >= 0) { "$id not in $layout" }
        return xml.substring(xml.lastIndexOf('<', at), xml.indexOf('>', at))
    }

    @Test
    fun `thumbnails opt out of force dark`() {
        val thumbs = listOf(
            "activity_static_analysis" to "imgRefThumb",
            "item_frame_order" to "imgFrameThumb",
            "item_media_tile" to "imgMediaThumb",
            "item_session" to "sessionThumb",
            "item_session" to "sessionResultThumb",
            "wizard_step_settings_content" to "roiThumb",
            "wizard_step_settings_content" to "imgSweepFrame",
            "dialog_sweep_frame_pick" to "imgSweepFramePreview",
            "sweep_frame_overlay" to "imgSweepFrameOverlay",
        )
        val darkened = thumbs.filterNot { (layout, id) ->
            "android:forceDarkAllowed=\"false\"" in tagOf(layout, id)
        }.map { (layout, id) -> "$layout/$id" }
        assertEquals(emptyList<String>(), darkened)
    }
}
