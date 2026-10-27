package com.sempermechanics.semper.data.account

import android.content.Context
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.account.entitlement.ConfigFetch
import com.sempermechanics.semper.data.account.entitlement.EntitlementRefresh
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.Authed
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.authed
import com.sempermechanics.semper.util.rethrowIfCallerCancelled
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

/**
 * Floating-seat lease: release on sign-out, renew while the process is up.
 *
 * The backend treats a fresh [SemperApi.checkoutLease] as the heartbeat — there
 * is no separate heartbeat route. Config refresh (revoke / expiry) is a
 * separate [SemperApi.getConfig] call; see [LicenseConfigWorker].
 */
object SeatLease {

    /**
     * True when this account currently holds a floating seat that should be
     * returned to the pool on sign-out or renewed while the app is open.
     */
    fun holdsFloatingSeat(context: Context): Boolean =
        LicenseEntitlements.needsSeat(context) && LicenseEntitlements.serverSaysLicensed(context)

    /**
     * Best-effort release before tokens are cleared. Never throws; a quiet
     * failure leaves the seat until the lease TTL, which the product already
     * tolerates.
     */
    suspend fun releaseBestEffort(
        context: Context,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        seatCall(holdsFloatingSeat(context), api, tokens, "release") {
            AppRemoteConfig.apply(context, releaseLease(it))
        }
    }

    /**
     * Renew a held floating seat. Same best-effort contract as release — a
     * failure just means the next cycle (or a lapsed TTL) will demote.
     *
     * A renewal is followed by a `/v1/config` fetch: the checkout answer
     * carries no entitlement token, and a floating seat's token ends with the
     * lease it was issued under, so without a fresh one the token would lapse
     * while the seat itself was renewed ([refreshConfigAfterCheckout]).
     */
    suspend fun heartbeatBestEffort(
        context: Context,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        val renewed = seatCall(holdsFloatingSeat(context), api, tokens, "heartbeat") {
            AppRemoteConfig.apply(context, checkoutLease(it))
        }
        if (renewed) refreshConfigAfterCheckout(context, api, tokens)
    }

    /**
     * After a seat checkout or heartbeat went through: fetch `/v1/config` for
     * the token that follows the new lease, and when that fetch fails, queue
     * the one-off refresh, which retries with backoff a bounded number of
     * times ([LicenseConfigWorker.refreshNow], TD-212). Without it the old
     * token lapses at the old lease's end and an online seat holder runs as
     * demo until the next periodic refresh.
     */
    suspend fun refreshConfigAfterCheckout(
        context: Context,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        if (EntitlementRefresh.retryAfterCheckout(fetchConfig(context, api, tokens))) {
            LicenseConfigWorker.refreshNow(context)
        }
    }

    /**
     * Fetch `/v1/config` and record it ([AppRemoteConfig.record]), the one
     * answer that carries the entitlement token: whether it answered, failed,
     * or was never made (no backend, nobody signed in).
     */
    suspend fun fetchConfig(
        context: Context,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): ConfigFetch {
        val fetched = configCall(api, tokens) ?: return ConfigFetch.NOT_MADE
        return if (AppRemoteConfig.record(context, fetched)) ConfigFetch.ANSWERED else ConfigFetch.FAILED
    }

    /** The `/v1/config` answer or failure, or null when no call was made (no backend, no token). */
    internal suspend fun configCall(api: CloudApi, tokens: TokenSource): Result<AppConfigDto>? =
        when (val outcome = bestEffort(api, tokens) { getConfig(it) }) {
            is Authed.Ok -> Result.success(outcome.value)
            is Authed.Failed -> Result.failure(outcome.failure.cause)
            Authed.Disabled, Authed.NoToken -> null
        }

    /**
     * Fetch `/v1/config`, apply it, and re-checkout when the account still
     * looks licensed on a floating seat. Used by the background worker so an
     * idle phone learns a remote revoke without an open screen.
     */
    suspend fun refreshConfigAndSeatBestEffort(
        context: Context,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        if (fetchConfig(context, api, tokens) == ConfigFetch.ANSWERED && holdsFloatingSeat(context)) {
            heartbeatBestEffort(context, api, tokens)
        }
    }

    /**
     * One seat [call] (the release or the checkout, applying the config it
     * answers), made only while [holdsSeat]. True when it went through; a
     * failure is logged and swallowed. [what] names the call in the log.
     */
    internal suspend fun seatCall(
        holdsSeat: Boolean,
        api: CloudApi,
        tokens: TokenSource,
        what: String,
        call: suspend CloudApi.(idToken: String) -> Unit,
    ): Boolean {
        if (!holdsSeat) return false
        val outcome = bestEffort(api, tokens, call)
        if (outcome is Authed.Failed) logFailure(what, outcome.failure)
        return outcome is Authed.Ok
    }

    /**
     * [authed], with nothing let through: a best-effort call that throws an
     * Error fails like any other, so sign-out always gets past the seat
     * release to clearing the session. Only the caller's own cancellation is
     * rethrown.
     */
    private suspend fun <T> bestEffort(
        api: CloudApi,
        tokens: TokenSource,
        call: suspend CloudApi.(idToken: String) -> T,
    ): Authed<T> = try {
        api.authed(tokens, call)
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        e.rethrowIfCallerCancelled()
        Authed.Failed(HttpFailure.classify(e))
    }

    /**
     * An unexpected throw is a bug, not a dropped connection, so it is logged
     * as an error (a Crashlytics non-fatal). A Task cancelled under a caller
     * still waiting is not a bug: a warning.
     */
    private fun logFailure(what: String, failure: HttpFailure) {
        if (failure.kind == HttpFailure.Kind.UNEXPECTED && failure.cause !is CancellationException) {
            Timber.e(failure.cause, "Floating-seat %s failed unexpectedly", what)
        } else {
            Timber.w(failure.cause, "Floating-seat %s failed (%s)", what, failure.kind)
        }
    }
}
