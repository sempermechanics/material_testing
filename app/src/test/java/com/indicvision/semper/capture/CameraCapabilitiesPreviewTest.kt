package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CameraCapabilities
import com.indicvision.semper.ui.capture.CameraCapabilities.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraCapabilitiesPreviewTest {

    private companion object {
        const val MAX_LONG_EDGE = 1280
    }

    private fun info(sizes: List<Resolution>, previews: List<Resolution> = sizes) =
        CameraCapabilities.Info(
            cameraId = "0",
            yuvSizes = sizes,
            minFrameMs = emptyMap(),
            previewSizes = previews,
        )

    private fun previews(list: List<Resolution>) = info(emptyList(), list)

    private val phonePreviews = listOf(
        Resolution(1920, 1080),
        Resolution(1440, 1080),
        Resolution(1280, 720),
        Resolution(1024, 768),
        Resolution(800, 600),
        Resolution(640, 480),
    )

    @Test
    fun `nearest prefers the right shape over the closest pixel count`() {
        // 1600x1200 is nearer 1920x1440 in raw pixels, but 1920x1080 is a
        // different field of view. A run at a shape the user never chose is
        // the failure worth avoiding.
        val catalogue = info(listOf(Resolution(1920, 1080), Resolution(1600, 1200)))

        assertEquals(Resolution(1600, 1200), catalogue.nearest(1920, 1440))
    }

    @Test
    fun `nearest takes the closest size within the best shape`() {
        val catalogue = info(
            listOf(Resolution(4080, 3060), Resolution(2048, 1536), Resolution(640, 480)),
        )

        assertEquals(Resolution(2048, 1536), catalogue.nearest(2000, 1500))
    }

    @Test
    fun `nearest has nothing to offer for an empty catalogue`() {
        assertNull(info(emptyList()).nearest(1920, 1080))
    }

    @Test
    fun `matches the capture's shape, not just its size`() {
        // The bug this replaces: clamping 2048x1536 per-axis to 1280x720 gave
        // a 16:9 preview for a 4:3 capture, so the preview showed a field of
        // view the run would not produce.
        val chosen = previews(phonePreviews).previewSizeFor(Resolution(2048, 1536), MAX_LONG_EDGE)

        assertEquals(4f / 3f, chosen.aspect, 0.02f)
        assertEquals(Resolution(1024, 768), chosen)
    }

    @Test
    fun `takes the largest that fits under the cap`() {
        val chosen = info(phonePreviews).previewSizeFor(Resolution(1920, 1080), MAX_LONG_EDGE)

        assertEquals(Resolution(1280, 720), chosen)
    }

    @Test
    fun `falls back to the closest shape when nothing matches exactly`() {
        val odd = listOf(Resolution(1000, 500), Resolution(640, 480))
        val chosen = previews(odd).previewSizeFor(Resolution(2048, 1536), MAX_LONG_EDGE)

        assertEquals(Resolution(640, 480), chosen)
    }

    @Test
    fun `takes the smallest available when everything is over the cap`() {
        val big = listOf(Resolution(4000, 3000), Resolution(2048, 1536))
        val chosen = previews(big).previewSizeFor(Resolution(2048, 1536), MAX_LONG_EDGE)

        assertEquals(Resolution(2048, 1536), chosen)
    }

    @Test
    fun `a camera that lists no preview sizes falls back to the capture size`() {
        val capture = Resolution(1920, 1080)

        assertEquals(capture, previews(emptyList()).previewSizeFor(capture, MAX_LONG_EDGE))
    }
}
