package com.indicvision.semper.data

import android.content.Context
import com.indicvision.semper.data.net.AppRemoteConfig
import kotlin.math.ceil

/**
 * Client view of the account's demo / licensed entitlements.
 *
 * The backend is the source of truth after [AppRemoteConfig.apply]. Until then
 * the app fails closed to demo: 25 saved analyses, no cloud backup/restore,
 * no share.
 */
object LicenseEntitlements {

    const val MODE_DEMO = "demo"
    const val MODE_LICENSED = "licensed"
    const val DEMO_MAX_ANALYSES = 25
    const val BLOCKED_ALPHA = 0.5f

    /** Start warning this many days before a timed license expires. */
    const val EXPIRY_WARN_DAYS = 14L

    /**
     * How old the cached config may be before an expiry notice is suppressed.
     * Comfortably above CloudSync's 5-minute reconcile throttle, which is the
     * fastest the cache can refresh once the quota is known — a threshold near
     * that interval would flap.
     */
    const val STALE_CACHE_MS = 7L * 24 * 60 * 60 * 1000

    private const val MILLIS_PER_DAY = 24.0 * 60 * 60 * 1000

    fun mode(context: Context): String {
        val stored = AppRemoteConfig.mode(context)
        return if (stored == MODE_LICENSED) MODE_LICENSED else MODE_DEMO
    }

    fun isLicensed(context: Context): Boolean = mode(context) == MODE_LICENSED

    fun cloudBackupEnabled(context: Context): Boolean =
        isLicensed(context) && AppRemoteConfig.cloudBackupEnabled(context)

    fun shareEnabled(context: Context): Boolean =
        isLicensed(context) && AppRemoteConfig.shareEnabled(context)

    /**
     * `""`, `"individual"`, or `"institution"`. Display/support metadata only —
     * an individual and an institution seat both resolve to [MODE_LICENSED]
     * with identical entitlements, so nothing above gates on this value.
     */
    fun licenseKind(context: Context): String = AppRemoteConfig.licenseKind(context)

    fun unlimitedAnalysis(context: Context): Boolean = isLicensed(context)

    /**
     * Past the license's expiry but still fully entitled — a renewal is
     * overdue. Nothing is withdrawn during grace; the backend decides when
     * entitlement actually ends and says so by flipping [mode].
     */
    fun inGrace(context: Context): Boolean = isLicensed(context) && AppRemoteConfig.inGrace(context)

    /**
     * Whole days until the license expires, or null when there is nothing to
     * warn about: a perpetual license, a demo account, no expiry on file, or
     * a cache too old to trust.
     *
     * Never a gate. A cached expiry can be arbitrarily stale — a renewal may
     * have landed while the device was offline — so this only ever decides
     * whether to show a notice. [mode] remains the only thing that changes
     * what the app will do.
     */
    fun daysUntilExpiry(context: Context, now: Long = System.currentTimeMillis()): Long? {
        if (!isLicensed(context)) return null
        val expiresAt = AppRemoteConfig.licenseExpiresAtMillis(context)
        if (expiresAt == AppRemoteConfig.NO_INSTANT) return null
        if (AppRemoteConfig.isStale(context, STALE_CACHE_MS, now)) return null
        return ceil((expiresAt - now).toDouble() / MILLIS_PER_DAY).toLong()
    }

    /**
     * Whether Home should show an expiry notice, and for how many days.
     *
     * Returns null when there is nothing to say. In grace the count is zero or
     * negative, which the caller renders as "renewal overdue" rather than a
     * countdown.
     */
    fun expiryNoticeDays(context: Context, now: Long = System.currentTimeMillis()): Long? {
        if (inGrace(context)) return daysUntilExpiry(context, now) ?: 0L
        val days = daysUntilExpiry(context, now) ?: return null
        return if (days <= EXPIRY_WARN_DAYS) days else null
    }

    /**
     * Local analysis cap. Demo is 25 even before config has been fetched.
     * A licensed account has no local analysis cap.
     */
    fun analysisCap(context: Context): Int {
        if (unlimitedAnalysis(context)) return Int.MAX_VALUE
        val remote = AppRemoteConfig.maxSessions(context)
        return if (remote > 0) remote else DEMO_MAX_ANALYSES
    }
}
