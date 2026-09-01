package com.indicvision.semper.capture

import com.indicvision.semper.ui.capture.CaptureGallerySave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The gallery copy is a convenience, so the only behaviour worth pinning is
 * the behaviour that protects the measurement: it must refuse to run the
 * volume down to nothing, and it must not mislabel what it wrote.
 */
class CaptureGallerySaveTest {

    @Test
    fun `a run that fits with headroom to spare is allowed`() {
        val need = 90L * 1024 * 1024
        assertTrue(CaptureGallerySave.hasRoom(need + CaptureGallerySave.HEADROOM_BYTES, need))
    }

    @Test
    fun `a run that would eat into the headroom is refused`() {
        val need = 90L * 1024 * 1024
        assertFalse(CaptureGallerySave.hasRoom(need + CaptureGallerySave.HEADROOM_BYTES - 1, need))
    }

    @Test
    fun `a run larger than the volume is refused rather than overflowing`() {
        // The subtraction must not wrap: a 4 GB run on a phone with 1 GB left.
        assertFalse(CaptureGallerySave.hasRoom(1L * 1024 * 1024 * 1024, 4L * 1024 * 1024 * 1024))
        assertFalse(CaptureGallerySave.hasRoom(0, Long.MAX_VALUE / 2))
    }

    @Test
    fun `frames are png and the test shot is jpeg`() {
        assertEquals("image/png", CaptureGallerySave.mimeOf(File("frame_000.png")))
        assertEquals("image/png", CaptureGallerySave.mimeOf(File("reference.png")))
        assertEquals("image/jpeg", CaptureGallerySave.mimeOf(File("test.jpg")))
        assertEquals("image/jpeg", CaptureGallerySave.mimeOf(File("TEST.JPG")))
    }

    @Test
    fun `folder name is semper slash compact date hyphen time`() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse("20260828-090800")!!
        assertEquals("semper/20260828-090800", CaptureGallerySave.folderName(stamp))
    }
}
