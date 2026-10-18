package com.sempermechanics.semper.ui.home

import com.sempermechanics.semper.ui.home.HomeLicenseNotice.Notice
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** Home's licence-expiry line: which one, and the grace end's date. */
class HomeLicenseNoticeTest {

    private val graceEnd = Instant.parse("2027-03-15T00:00:00Z").toEpochMilli()

    @Test
    fun `a countdown inside the warning window says how many days`() {
        assertEquals(Notice.ExpiresIn(9), HomeLicenseNotice.of(inGrace = false, days = 9, graceEndsAtMs = 0L))
        assertEquals(Notice.ExpiresIn(1), HomeLicenseNotice.of(inGrace = false, days = 1, graceEndsAtMs = graceEnd))
    }

    @Test
    fun `the last day and a passed day without grace`() {
        assertEquals(Notice.ExpiresToday, HomeLicenseNotice.of(inGrace = false, days = 0, graceEndsAtMs = 0L))
        assertEquals(Notice.Expired, HomeLicenseNotice.of(inGrace = false, days = -2, graceEndsAtMs = 0L))
    }

    @Test
    fun `grace names the day it ends when the backend sent one`() {
        val until = Notice.GraceUntil(graceEnd)
        assertEquals(until, HomeLicenseNotice.of(inGrace = true, days = -3, graceEndsAtMs = graceEnd))
        // Grace wins over the countdown, however the days read.
        assertEquals(until, HomeLicenseNotice.of(inGrace = true, days = 0, graceEndsAtMs = graceEnd))
        assertEquals(Notice.Grace, HomeLicenseNotice.of(inGrace = true, days = -3, graceEndsAtMs = 0L))
    }

    @Test
    fun `the grace end is the UTC day`() {
        assertEquals("Mar 15, 2027", HomeLicenseNotice.formatDay(graceEnd, Locale.US))
        // A minute before midnight UTC is still the 14th, wherever the phone is.
        assertEquals("Mar 14, 2027", HomeLicenseNotice.formatDay(graceEnd - 60_000L, Locale.US))
    }
}
