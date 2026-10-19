package com.sempermechanics.semper.analysis

import androidx.exifinterface.media.ExifInterface
import com.sempermechanics.semper.data.mechanical.CaptureStamp
import com.sempermechanics.semper.ui.analysis.load.PhotoCaptureTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * EXIF capture times, which give a photo batch the frame times a timed load
 * log is matched against. Dates are EXIF's own `yyyy:MM:dd HH:mm:ss`, and
 * `SubSecTime` is the digits after the decimal point.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoCaptureTimeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val t0 = PhotoCaptureTime.parse("2026:09:26 14:30:00", null)!!

    @Test
    fun `whole seconds apart are a thousand ms apart`() {
        assertEquals(1_000L, PhotoCaptureTime.parse("2026:09:26 14:30:01", null)!! - t0)
        assertEquals(86_400_000L, PhotoCaptureTime.parse("2026:09:27 14:30:00", null)!! - t0)
    }

    @Test
    fun `sub-seconds are the digits after the decimal point`() {
        assertEquals(500L, PhotoCaptureTime.parse("2026:09:26 14:30:00", "5")!! - t0)
        assertEquals(120L, PhotoCaptureTime.parse("2026:09:26 14:30:00", "12")!! - t0)
        assertEquals(123L, PhotoCaptureTime.parse("2026:09:26 14:30:00", "123")!! - t0)
        assertEquals(123L, PhotoCaptureTime.parse("2026:09:26 14:30:00", "123456")!! - t0)
        assertEquals(7L, PhotoCaptureTime.parse("2026:09:26 14:30:00", "007 ")!! - t0)
    }

    @Test
    fun `unreadable sub-seconds count as none`() {
        assertEquals(0L, PhotoCaptureTime.parse("2026:09:26 14:30:00", "")!! - t0)
        assertEquals(0L, PhotoCaptureTime.parse("2026:09:26 14:30:00", "  ")!! - t0)
        assertEquals(0L, PhotoCaptureTime.parse("2026:09:26 14:30:00", "x1")!! - t0)
    }

    @Test
    fun `a missing or blanked date is no time`() {
        assertNull(PhotoCaptureTime.parse(null, "123"))
        assertNull(PhotoCaptureTime.parse("", null))
        // What a camera without a set clock writes.
        assertNull(PhotoCaptureTime.parse("0000:00:00 00:00:00", null))
        assertNull(PhotoCaptureTime.parse("    :  :     :  :  ", null))
        assertNull(PhotoCaptureTime.parse("2026-09-26T14:30:00", null))
    }

    @Test
    fun `a stamp says whether SubSecTimeOriginal gave the sub-seconds`() {
        val date = "2026:09:26 14:30:00"
        assertEquals(CaptureStamp(t0 + 250L, subSeconds = true), PhotoCaptureTime.stamp(date, "25"))
        // A camera that writes sub-seconds and lands on .000 is still precise.
        assertEquals(CaptureStamp(t0, subSeconds = true), PhotoCaptureTime.stamp(date, "000"))
        // None, blank or not digits: the time is good only to the second.
        assertEquals(CaptureStamp(t0, subSeconds = false), PhotoCaptureTime.stamp(date, null))
        assertEquals(CaptureStamp(t0, subSeconds = false), PhotoCaptureTime.stamp(date, " "))
        assertEquals(CaptureStamp(t0, subSeconds = false), PhotoCaptureTime.stamp(date, "x1"))
        assertNull(PhotoCaptureTime.stamp(null, "250"))
    }

    @Test
    fun `the time is read from DateTimeOriginal, not the edit time`() {
        val jpeg = folder.newFile("frame.jpg").apply { writeBytes(MINIMAL_JPEG) }
        ExifInterface(jpeg).apply {
            setAttribute(ExifInterface.TAG_DATETIME, "2030:01:01 00:00:00")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:26 14:30:02")
            setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, "250")
            saveAttributes()
        }

        assertEquals(CaptureStamp(t0 + 2_250L, subSeconds = true), PhotoCaptureTime.read(jpeg))
        assertEquals(CaptureStamp(t0 + 2_250L, subSeconds = true), jpeg.inputStream().use { PhotoCaptureTime.read(it) })
    }

    @Test
    fun `a photo without SubSecTimeOriginal reads as whole seconds`() {
        val jpeg = folder.newFile("whole.jpg").apply { writeBytes(MINIMAL_JPEG) }
        ExifInterface(jpeg).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:09:26 14:30:02")
            saveAttributes()
        }

        assertEquals(CaptureStamp(t0 + 2_000L, subSeconds = false), PhotoCaptureTime.read(jpeg))
    }

    @Test
    fun `a photo with only an edit time, or no EXIF, has no capture time`() {
        val edited = folder.newFile("edited.jpg").apply { writeBytes(MINIMAL_JPEG) }
        ExifInterface(edited).apply {
            setAttribute(ExifInterface.TAG_DATETIME, "2026:09:26 14:30:02")
            saveAttributes()
        }
        val notAnImage = folder.newFile("frame.bin").apply { writeText("not an image") }

        assertNull(PhotoCaptureTime.read(edited))
        assertNull(PhotoCaptureTime.read(notAnImage))
    }

    private companion object {
        /** A 1×1 grey baseline JPEG (Pillow, quality 50), the smallest file ExifInterface will rewrite. */
        val MINIMAL_JPEG: ByteArray = Base64.getDecoder().decode(
            listOf(
                "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDABALDA4MChAODQ4SERATGCgaGBYWGDEjJR0oOjM9PDkzODdASFxOQERX",
                "RTc4UG1RV19iZ2hnPk1xeXBkeFxlZ2P/wAALCAABAAEBAREA/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcI",
                "CQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcY",
                "GRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKj",
                "pKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/9oACAEBAAA/ACv/",
                "2Q==",
            ).joinToString(""),
        )
    }
}
