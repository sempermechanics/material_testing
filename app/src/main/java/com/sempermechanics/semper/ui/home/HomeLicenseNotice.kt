package com.sempermechanics.semper.ui.home

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Which licence-expiry line Home shows ([HomeQuotaCard]). Informational, not a
 * call to action: the app only consumes the licence the account holds, so the
 * line says what happens and until when, never "renew" or "buy", and names no
 * address to write to for one.
 */
internal object HomeLicenseNotice {

    /** No grace end on file (`AppRemoteConfig.NO_INSTANT`). */
    private const val NO_INSTANT = 0L

    sealed interface Notice {
        /** A timed licence ends in [days] days (1 or more). */
        data class ExpiresIn(val days: Int) : Notice

        /** It ends today. */
        data object ExpiresToday : Notice

        /** Past its day, and the cache cannot say whether grace applies. */
        data object Expired : Notice

        /** Past its day, still fully entitled until [endsAtMs]. */
        data class GraceUntil(val endsAtMs: Long) : Notice

        /** In grace, with no end on file (a backend that predates the field). */
        data object Grace : Notice
    }

    /**
     * The line for a notice of [days] (`LicenseEntitlements.expiryNoticeDays`:
     * zero or less once the day has passed), [inGrace] as the backend says,
     * and the grace end [graceEndsAtMs] on file.
     */
    fun of(inGrace: Boolean, days: Long, graceEndsAtMs: Long): Notice = when {
        inGrace && graceEndsAtMs > NO_INSTANT -> Notice.GraceUntil(graceEndsAtMs)
        inGrace -> Notice.Grace
        days < 0L -> Notice.Expired
        days == 0L -> Notice.ExpiresToday
        else -> Notice.ExpiresIn(days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * [epochMs] as a date in [locale], read in UTC: a licence and its grace
     * end at the UTC day the consoles show, as the countdown counts them
     * (`LicenseEntitlements.daysUntilExpiry`).
     */
    fun formatDay(epochMs: Long, locale: Locale = Locale.getDefault()): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMs))
}
