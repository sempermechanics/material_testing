package com.sempermechanics.semper.ui.analysis.frames

import android.app.Application
import android.net.Uri
import android.widget.TextView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sempermechanics.semper.R
import com.sempermechanics.semper.imaging.video.ExtractionRequest
import com.sempermechanics.semper.imaging.video.VideoFrameExtractor
import com.sempermechanics.semper.imaging.video.VideoMeta
import com.sempermechanics.semper.ui.analysis.WizardTestBed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sampling sheet says what the clip reported and what a sampling will
 * deliver, and Extract hands over that sampling, sample times included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class VideoSamplingSheetTest {

    private val meta = VideoMeta(
        durationMs = 4_000L,
        fps = 29.97,
        fpsKnown = true,
        width = 1920,
        height = 1080,
        rotationDegrees = 90,
    )

    @Test
    fun `the sheet shows the clip and the sampling, and Extract hands over the planned times`() {
        val bed = WizardTestBed()
        var request: ExtractionRequest? = null
        val sheet = VideoSamplingSheet(bed.activity, onExtract = { request = it })
            .show(Uri.parse("content://v/1"), meta, syncTimesUs = emptyList())
        bed.idle()

        val info = sheet.findViewById<TextView>(R.id.tvVideoInfo)!!.text.toString()
        assertEquals("1920×1080   ·   30 fps   ·   ${VideoFrameExtractor.formatClock(4_000L)}", info)
        assertEquals("10 fps", sheet.findViewById<TextView>(R.id.tvFpsValue)!!.text.toString())
        val segment = "${VideoFrameExtractor.formatClock(0)} – ${VideoFrameExtractor.formatClock(4_000L)}"
        assertEquals(segment, sheet.findViewById<TextView>(R.id.tvSegmentValue)!!.text.toString())
        // Fewer than two key frames: only the frame rate is offered.
        assertFalse(sheet.findViewById<MaterialButtonToggleGroup>(R.id.rgExtractMode)!!.isShown)

        val estimate = sheet.findViewById<TextView>(R.id.tvEstimate)!!.text.toString()
        assertTrue(estimate, estimate.startsWith("≈ 40 frames: 1 reference + 39 deformed"))

        sheet.findViewById<android.view.View>(R.id.btnExtractFrames)!!.performClick()
        val sent = request!!
        assertEquals(10.0, sent.fpsExtract, 0.0)
        assertEquals(0L, sent.startMs)
        assertEquals(90, sent.rotationDegrees)
        assertEquals(false, sent.preferKeyframes)
        assertTrue(sent.maxFrames > 0)
        // The count the sheet promised is the count it asks for.
        assertEquals(40, sent.timesMs.size)
    }

    @Test
    fun `key frames are offered when the clip marks two or more, and Extract sends their times`() {
        val bed = WizardTestBed()
        var request: ExtractionRequest? = null
        val sync = listOf(0L, 1_000_000L, 2_000_000L, 3_000_000L)
        val sheet = VideoSamplingSheet(bed.activity, onExtract = { request = it })
            .show(Uri.parse("content://v/1"), meta, syncTimesUs = sync)
        bed.idle()

        val toggle = sheet.findViewById<MaterialButtonToggleGroup>(R.id.rgExtractMode)!!
        assertTrue(toggle.isShown)
        toggle.check(R.id.btnModeKeyframes)
        sheet.findViewById<android.view.View>(R.id.btnExtractFrames)!!.performClick()

        val sent = request!!
        assertEquals(true, sent.preferKeyframes)
        assertEquals(listOf(0.0, 1000.0, 2000.0, 3000.0), sent.timesMs)
    }
}
