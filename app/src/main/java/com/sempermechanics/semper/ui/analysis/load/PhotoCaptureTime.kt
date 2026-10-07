package com.sempermechanics.semper.ui.analysis.load

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * When a photo was taken, from its EXIF, so a timed load log can be matched
 * to a photo batch by time as it is to a video's frames.
 *
 * Only `DateTimeOriginal` (+ `SubSecTimeOriginal`) counts: `DateTime` is the
 * last edit and a file's modified time is when it was copied, and either
 * would hand a frame some other moment's load. A photo without it has no
 * capture time, and a batch with any such photo falls back to row order.
 *
 * The camera's wall clock is read as UTC. Only differences between photos
 * are used, so the zone drops out, and no daylight-saving change can open a
 * gap mid-test. Without sub-seconds a time is good to the whole second.
 */
object PhotoCaptureTime {

    private const val MS_DIGITS = 3

    /** Milliseconds on the camera's clock, or null when [dateTime] is missing or not an EXIF date. */
    fun parse(dateTime: String?, subSec: String?): Long? {
        val text = dateTime?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val seconds = try {
            format.parse(text)?.time
        } catch (_: ParseException) {
            null
        }
        return seconds?.plus(fractionMs(subSec))
    }

    /**
     * `SubSecTime` is the digits after the decimal point: "5" is 500 ms,
     * "123" is 123 ms, "1234" is 123 ms. Anything else counts as none.
     */
    private fun fractionMs(subSec: String?): Long {
        val digits = subSec?.trim()?.takeWhile { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return 0L
        return digits.take(MS_DIGITS).padEnd(MS_DIGITS, '0').toLong()
    }

    /**
     * Each frame's time after the reference, in ms, or empty (no time match)
     * unless the reference and every frame have a capture time.
     */
    fun relativeTimesMs(referenceMs: Long?, framesMs: List<Long?>): List<Long> {
        val times = framesMs.filterNotNull()
        if (referenceMs == null || times.isEmpty() || times.size != framesMs.size) return emptyList()
        return times.map { it - referenceMs }
    }

    /** The capture time in [file]'s EXIF, or null when it has none or cannot be read. */
    fun read(file: File): Long? = runCatching { of(ExifInterface(file)) }.getOrNull()

    /** As [read], from a stream the caller closes; for RAW files whose import drops the EXIF. */
    fun read(stream: InputStream): Long? = runCatching { of(ExifInterface(stream)) }.getOrNull()

    private fun of(exif: ExifInterface): Long? = parse(
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
        exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL),
    )
}
