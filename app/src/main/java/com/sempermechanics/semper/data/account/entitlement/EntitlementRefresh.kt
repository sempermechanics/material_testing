package com.sempermechanics.semper.data.account.entitlement

/** How one `/v1/config` fetch went. */
enum class ConfigFetch {
    /** The backend answered and the answer was recorded. */
    ANSWERED,

    /** The call was made and failed (offline, a timeout, an error answer). */
    FAILED,

    /** No call was made: no backend in this build, or nobody signed in. */
    NOT_MADE,
}

/** What the one-off refresh worker reports after an attempt. */
enum class RefreshVerdict {
    /** Finished: answered, nothing to ask, or out of attempts (the periodic refresh carries on). */
    DONE,

    /** Try again after WorkManager's backoff. */
    RETRY,
}

/**
 * When the app asks `/v1/config` for a fresh token outside the periodic
 * refresh, and how long it keeps trying. The glue is `LicenseConfigWorker`
 * (the unique one-off) and `SeatLease` (after a seat checkout).
 */
object EntitlementRefresh {

    /**
     * Attempts the one-off makes before it leaves the rest to the periodic
     * refresh: with WorkManager's exponential backoff from 30 s, retries come
     * 30 s, 1, 2, 4 and 8 min apart, about 15 minutes in all.
     */
    const val MAX_ATTEMPTS = 6

    private const val MS_PER_SECOND = 1000L

    /**
     * Whether the stored token wants a fresh one [nowMs] (the device's best
     * estimate of the server's clock): its `refreshAfter` has passed, or it
     * has lapsed. With no token in play the periodic refresh is enough.
     */
    fun refreshDue(status: TokenStatus, nowMs: Long): Boolean = when (status) {
        TokenStatus.NotUsed -> false
        is TokenStatus.Lapsed -> true
        is TokenStatus.Valid -> nowMs >= status.claims.refreshAfter * MS_PER_SECOND
    }

    /**
     * Whether a seat checkout or heartbeat that went through should queue the
     * retrying refresh: only when its own `/v1/config` fetch was made and
     * failed. The checkout answer carries no token, and a floating seat's
     * token ends with the lease it was issued under.
     */
    fun retryAfterCheckout(fetch: ConfigFetch): Boolean = fetch == ConfigFetch.FAILED

    /** After attempt number [runAttemptCount] (WorkManager's, from 0) went [fetch]. */
    fun afterAttempt(fetch: ConfigFetch, runAttemptCount: Int): RefreshVerdict {
        val retry = fetch == ConfigFetch.FAILED && runAttemptCount + 1 < MAX_ATTEMPTS
        return if (retry) RefreshVerdict.RETRY else RefreshVerdict.DONE
    }
}
