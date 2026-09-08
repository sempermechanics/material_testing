package com.indicvision.semper.capture

import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.ui.capture.CaptureEstimateText
import com.indicvision.semper.ui.capture.CapturePlanOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureEstimateTextTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `states the rate, the count and the spacing`() {
        val option = CapturePlanOptions.Option(fps = 2f, frames = 60, intervalMs = 500)
        val line = CaptureEstimateText.line(context, option, modeLabel = "stills")
        assertTrue(line, line.contains("2 fps"))
        assertTrue(line, line.contains("60"))
        assertTrue(line, line.contains("500 ms"))
        assertTrue(line, line.contains("stills"))
    }

    @Test
    fun `spacing switches to seconds once frames sit more than a second apart`() {
        val option = CapturePlanOptions.Option(fps = 0.1f, frames = 12, intervalMs = 10_000)
        val line = CaptureEstimateText.line(context, option, modeLabel = "stills")
        assertTrue(line, line.contains("10.0 s"))
    }

    @Test
    fun `whole rates read as whole numbers and fractional ones keep their point`() {
        assertEquals("2", CaptureEstimateText.fps(2f))
        assertEquals("30", CaptureEstimateText.fps(30f))
        assertEquals("0.5", CaptureEstimateText.fps(0.5f))
        assertEquals("0.1", CaptureEstimateText.fps(0.1f))
    }

    @Test
    fun `points at resolution when the camera is what limits the list`() {
        val note = CaptureEstimateText.ceilingNote(context, cappedBySetting = false, maxFramesSetting = 500)
        assertTrue(note, note.contains("resolution"))
        assertFalse(note, note.contains("Settings"))
    }

    @Test
    fun `points at Settings only when the setting is what limits the list`() {
        val note = CaptureEstimateText.ceilingNote(context, cappedBySetting = true, maxFramesSetting = 500)
        assertTrue(note, note.contains("Settings"))
        assertTrue(note, note.contains("500"))
    }

    @Test
    fun `never names a phone`() {
        val option = CapturePlanOptions.Option(fps = 2f, frames = 60, intervalMs = 500)
        val text = CaptureEstimateText.line(context, option, "stills") +
            CaptureEstimateText.ceilingNote(context, false, 500)
        assertFalse(text, text.contains("Pixel"))
    }
}
