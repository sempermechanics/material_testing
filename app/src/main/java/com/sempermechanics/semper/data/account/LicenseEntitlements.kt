package com.sempermechanics.semper.data.account

import android.content.Context
import com.sempermechanics.semper.data.account.entitlement.EntitlementPolicy
import com.sempermechanics.semper.data.account.entitlement.EntitlementStore
import com.sempermechanics.semper.data.account.entitlement.TokenBound
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.policy.AppPolicy

/**
 * Client view of the account's demo / licensed entitlements.
 *
 * The backend is the source of truth after [AppRemoteConfig.apply]. Until then
 * the app fails closed to demo: 25 saved analyses, no cloud backup/restore,
 * no share.
 *
 * Two more conditions narrow what the cached config allows, never widen it:
 *
 * - **The signed entitlement token** ([EntitlementStore]), when this build
 *   pins a signing key and the backend sends one. Offline, a licence then
 *   lasts until the token's `graceUntil`; past it, on a clock rollback, or
 *   when the stored record was altered, entitlements drop to demo. Nothing
 *   local is locked: analysis carries on within demo's limits, and every
 *   saved analysis stays open, viewable and exportable. Without pins or a
 *   token, or with a token that does not verify here, this is exactly the
 *   behaviour before tokens.
 * - **Device and fleet policy** ([AppPolicy]): the backend's kill switches
 *   and the EMM's managed configuration.
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

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    fun mode(context: Context): String =
        if (serverSaysLicensed(context) && tokenBound(context).licensed) MODE_LICENSED else MODE_DEMO

    fun isLicensed(context: Context): Boolean = mode(context) == MODE_LICENSED

    /**
     * What the cached `/v1/config` says, before the token's bound. The seat
     * gates read this: a seat is the server's to grant or lapse, and an
     * offline token running out must not stop the heartbeat of a seat the
     * server still counts as held.
     */
    fun serverSaysLicensed(context: Context): Boolean = AppRemoteConfig.mode(context) == MODE_LICENSED

    fun cloudBackupEnabled(context: Context): Boolean = backupLicensed(context) && AppPolicy.backupAllowed(context)

    /**
     * Whether the licence includes cloud backup, before device and fleet
     * policy. Apart from [cloudBackupEnabled] because the screens tell the
     * two apart: a feature the licence lacks is shown locked, one policy
     * holds back is hidden or paused (`FeatureLocks`).
     */
    fun backupLicensed(context: Context): Boolean =
        isLicensed(context) && AppRemoteConfig.cloudBackupEnabled(context) && tokenBound(context).backup

    /**
     * Whether a backup may be pulled down now (restore, Save to Files from
     * the cloud): [cloudBackupEnabled], unless the restore kill switch is on
     * or this build must update first.
     */
    fun restoreEnabled(context: Context): Boolean = cloudBackupEnabled(context) && AppPolicy.restoreAllowed(context)

    fun shareEnabled(context: Context): Boolean = shareLicensed(context) && AppPolicy.shareAllowed(context)

    /** Whether the licence includes sharing exports, before the share kill switch ([backupLicensed]'s twin). */
    fun shareLicensed(context: Context): Boolean =
        isLicensed(context) && AppRemoteConfig.shareEnabled(context) && tokenBound(context).share

    /** The most the entitlement token allows; unbounded when tokens are not in use. */
    private fun tokenBound(context: Context): TokenBound =
        EntitlementPolicy.bound(EntitlementStore.status(context), DEMO_MAX_ANALYSES)

    /**
     * `""`, `"individual"`, or `"institution"`. Display/support metadata — an
     * individual and an institution seat resolve to identical entitlements
     * once [isLicensed], so nothing gates on *this* value.
     *
     * What can differ on an institution license is [needsSeat]: a floating one
     * entitles only the members currently holding a seat.
     */
    fun licenseKind(context: Context): String = AppRemoteConfig.licenseKind(context)

    /**
     * Key prefix shown for support (never the full key). Not proof of a
     * licence: a backend older than the licensed-only prefix sends one for a
     * held key that is not active, so check [isLicensed] before showing it.
     */
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
     * institution member is [MODE_LICENSED], so `SessionQuota.blocked` and
     * `analysisCap` only fire for them at the licensed ceiling. Without this
     * a member with no seat would sail past every existing check.
     *
     * Reads [serverSaysLicensed] as the answer rather than the cached lease
     * date, and not [isLicensed]. The backend already folds the lease into
     * `mode` — it resolves demo the moment a seat lapses — so trusting a
     * local timestamp instead would just be a second, staler opinion of the
     * same thing. One exception: a floating seat's token ends with the lease
     * it was issued under, so a token past its `graceUntil` or `exp` means
     * the lease ran out unrenewed, and this screen (which checks out a seat
     * and fetches a fresh token) is the way back, not demo's limits
     * ([EntitlementPolicy.seatRequired]). A token lapsed for another reason,
     * or no token, leaves the server's answer alone.
     */
    fun isSeatRequiredToStart(context: Context): Boolean =
        needsSeat(context) &&
            EntitlementPolicy.seatRequired(serverSaysLicensed(context), EntitlementStore.status(context))

    /** How often to renew a held seat while work is in progress, in minutes. */
    fun seatHeartbeatMinutes(context: Context): Int =
        AppRemoteConfig.leaseHeartbeatMinutes(context)

    /**
     * Licensed with no ceiling on file yet. Once `/v1/config` reports one, a
     * licensed account is held to it like demo is: the server refuses the
     * upload past it, so letting the run start only to bounce at 409 — and
     * then telling the user on "Re-check" that the limit had cleared — was
     * the app contradicting itself.
     */
    fun hasUnlimitedAnalysis(context: Context): Boolean =
        isLicensed(context) && !AppRemoteConfig.isKnown(context)

    /**
     * Past the license's expiry but still fully entitled — a renewal is
     * overdue. Nothing is withdrawn during grace; the backend decides when
     * entitlement actually ends and says so by flipping [mode].
     */
    fun isInGrace(context: Context): Boolean = isLicensed(context) && AppRemoteConfig.isInGrace(context)

    /**
     * Calendar days until the license's last day, in UTC, or null when there
     * is nothing to warn about: a perpetual license, a demo account, no expiry on file, or
     * a cache too old to trust.
     *
     * Never a gate. A cached expiry can be arbitrarily stale — a renewal may
     * have landed while the device was offline — so this only ever decides
     * whether to show a notice. [mode] remains the only thing that changes
     * what the app will do.
     *
     * Counted in UTC days because a licence ends at 23:59:59Z on its chosen
     * day, the day the consoles show. Rounding the hours up made three hours
     * left read "expires tomorrow" and 25 hours "in 2 days".
     */
    fun daysUntilExpiry(context: Context, now: Long = System.currentTimeMillis()): Long? {
        val expiresAt = AppRemoteConfig.licenseExpiresAtMs(context)
        val worthWarningAbout = isLicensed(context) &&
            expiresAt != AppRemoteConfig.NO_INSTANT &&
            !AppRemoteConfig.isStale(context, STALE_CACHE_MS, now)
        return if (!worthWarningAbout) {
            null
        } else {
            Math.floorDiv(expiresAt, MILLIS_PER_DAY) - Math.floorDiv(now, MILLIS_PER_DAY)
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
            isInGrace(context) -> days ?: 0L
            days == null -> null
            days <= EXPIRY_WARN_DAYS -> days
            else -> null
        }
    }

    /**
     * Local analysis cap: the backend's `maxSessions` once known. Before that,
     * demo is 25 and a licensed account is uncapped.
     */
    fun analysisCap(context: Context): Int {
        val remote = AppRemoteConfig.maxSessions(context)
        val cached = when {
            hasUnlimitedAnalysis(context) -> Int.MAX_VALUE
            remote > 0 -> remote
            else -> DEMO_MAX_ANALYSES
        }
        return EntitlementPolicy.cap(cached, tokenBound(context))
    }

    /**
     * The ceiling the quota line and the limit screen show: [analysisCap],
     * the cap actually enforced, once the backend has reported `maxSessions`;
     * 0 before that, which they read as "not known yet". With the token past
     * its grace this is demo's cap, not the server's `maxSessions`, so the
     * screens never show room the gate will refuse.
     */
    fun shownAnalysisCap(context: Context): Int =
        EntitlementPolicy.shownCap(AppRemoteConfig.maxSessions(context), tokenBound(context))
}
