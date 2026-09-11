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
@Suppress("TooManyFunctions")
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
     * `""`, `"individual"`, or `"institution"`. Display/support metadata — an
     * individual and an institution seat resolve to identical entitlements
     * once [isLicensed], so nothing gates on *this* value.
     *
     * What can differ on an institution license is [needsSeat]: a floating one
     * entitles only the members currently holding a seat.
     */
    fun licenseKind(context: Context): String = AppRemoteConfig.licenseKind(context)

    /** Key prefix shown for support (never the full key). Empty on demo. */
    fun licensePrefix(context: Context): String = AppRemoteConfig.licensePrefix(context)

    /**
     * Whether this account has to hold a floating seat to work.
     *
     * True only for a floating institution license. On an assigned license,
     * and against any backend that predates floating seats, this is false and
     * nothing changes.
     */
    fun needsSeat(context: Context): Boolean =
        AppRemoteConfig.licenseSeating(context) == AppRemoteConfig.SEATING_FLOATING

    /**
     * Whether starting new work needs a seat this account does not have.
     *
     * A **parallel** gate to the quota one, not a widening of it: an
     * institution member is [MODE_LICENSED], so `isSessionLimitReached` and
     * `analysisCap` never fire for them. Without this they would sail past
     * every existing check.
     *
     * Reads [isLicensed] as the answer rather than the cached lease date. The
     * backend already folds the lease into `mode` — it resolves demo the
     * moment a seat lapses — so trusting a local timestamp instead would just
     * be a second, staler opinion of the same thing.
     */
    fun seatRequiredToStart(context: Context): Boolean =
        needsSeat(context) && !isLicensed(context)

    /** How often to renew a held seat while work is in progress, in minutes. */
    fun seatHeartbeatMinutes(context: Context): Int =
        AppRemoteConfig.leaseHeartbeatMinutes(context)

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
        val expiresAt = AppRemoteConfig.licenseExpiresAtMillis(context)
        val worthWarningAbout = isLicensed(context) &&
            expiresAt != AppRemoteConfig.NO_INSTANT &&
            !AppRemoteConfig.isStale(context, STALE_CACHE_MS, now)
        return if (!worthWarningAbout) {
            null
        } else {
            ceil((expiresAt - now).toDouble() / MILLIS_PER_DAY).toLong()
        }
    }

    /**
     * Whether Home should show an expiry notice, and for how many days.
     *
     * Returns null when there is nothing to say. In grace the count is zero or
     * negative, which the caller renders as "renewal overdue" rather than a
     * countdown.
     */
    fun expiryNoticeDays(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val days = daysUntilExpiry(context, now)
        return when {
            // In grace the expiry has passed, so a stale cache — which makes
            // `days` null — still warrants the overdue notice.
            inGrace(context) -> days ?: 0L
            days == null -> null
            days <= EXPIRY_WARN_DAYS -> days
            else -> null
        }
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
