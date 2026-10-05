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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sampling sheet says what the clip reported and what a sampling will
 * deliver, and Extract hands over that sampling.
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
    fun `the slider tops out at the source rate, and an unknown rate assumes 30`() {
        assertEquals(30, SamplingPlan(meta, emptyList(), maxFrames = 100).maxFps)
        assertEquals(30, SamplingPlan(meta.copy(fpsKnown = false, fps = 0.0), emptyList(), maxFrames = 100).maxFps)
        assertEquals(2, SamplingPlan(meta.copy(fps = 1.0), emptyList(), maxFrames = 100).maxFps)
        assertEquals(60, SamplingPlan(meta.copy(fps = 240.0), emptyList(), maxFrames = 100).maxFps)
    }

    @Test
    fun `the segment stops at the last frame's start`() {
        val sampling = SamplingPlan(meta, emptyList(), maxFrames = 100)
        val (start, end) = sampling.segmentMs(listOf(1f, 4f))
        assertEquals(1_000L, start)
        assertTrue(end < 4_000L)
    }

    @Test
    fun `the estimate never passes the cap`() {
        assertEquals(5, SamplingPlan(meta, emptyList(), maxFrames = 5).estimate(listOf(0f, 4f), fps = 30.0))
    }

    @Test
    fun `the sheet shows the clip and the sampling, and Extract hands it over`() {
        val bed = WizardTestBed()
        var request: ExtractionRequest? = null
        val sheet = VideoSamplingSheet(bed.activity, onExtract = { request = it })
            .show(Uri.parse("content://v/1"), meta)
        bed.idle()

        val info = sheet.findViewById<TextView>(R.id.tvVideoInfo)!!.text.toString()
        assertEquals("1920×1080   ·   30 fps   ·   ${VideoFrameExtractor.formatClock(4_000L)}", info)
        assertEquals("10 fps", sheet.findViewById<TextView>(R.id.tvFpsValue)!!.text.toString())
        val segment = "${VideoFrameExtractor.formatClock(0)} – ${VideoFrameExtractor.formatClock(4_000L)}"
        assertEquals(segment, sheet.findViewById<TextView>(R.id.tvSegmentValue)!!.text.toString())

        // Without two key frames the plan is the frame rate, and the choice is hidden.
        val modes = sheet.findViewById<MaterialButtonToggleGroup>(R.id.rgExtractMode)!!
        assertEquals(android.view.View.GONE, modes.visibility)
        val estimate = sheet.findViewById<TextView>(R.id.tvEstimate)!!.text.toString()
        assertTrue(estimate, estimate.startsWith("≈ 40 frames: 1 reference + 39 deformed"))

        sheet.findViewById<android.view.View>(R.id.btnExtractFrames)!!.performClick()
        val sent = request!!
        assertEquals(40, sent.timesMs.size)
        assertEquals(0.0, sent.timesMs.first(), 0.0)
        assertEquals(100.0, sent.timesMs[1], 1e-9)
        assertEquals(90, sent.rotationDegrees)
    }

    @Test
    fun `with key frames in the file, the key-frame plan extracts exactly those`() {
        val bed = WizardTestBed()
        var request: ExtractionRequest? = null
        val keyframesUs = listOf(0L, 1_000_000L, 2_000_000L, 3_000_000L)
        val sheet = VideoSamplingSheet(bed.activity, onExtract = { request = it })
            .show(Uri.parse("content://v/1"), meta, keyframesUs)
        bed.idle()

        sheet.findViewById<MaterialButtonToggleGroup>(R.id.rgExtractMode)!!.check(R.id.btnModeKeyframes)
        bed.idle()
        val estimate = sheet.findViewById<TextView>(R.id.tvEstimate)!!.text.toString()
        assertTrue(estimate, estimate.startsWith("4 key frames: 1 reference + 3 deformed"))

        sheet.findViewById<android.view.View>(R.id.btnExtractFrames)!!.performClick()
        assertEquals(listOf(0.0, 1_000.0, 2_000.0, 3_000.0), request!!.timesMs)
    }
}
