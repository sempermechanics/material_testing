package com.indicvision.semper.data.account

import android.content.Context
import com.indicvision.semper.data.LicenseConfigWorker
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.Authed
import com.indicvision.semper.data.net.CloudApi
import com.indicvision.semper.data.net.HttpFailure
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenSource
import com.indicvision.semper.data.net.authed
import timber.log.Timber

/**
 * Floating-seat lease: release on sign-out, renew while the process is up.
 *
 * The backend treats a fresh [IndicApi.checkoutLease] as the heartbeat — there
 * is no separate heartbeat route. Config refresh (revoke / expiry) is a
 * separate [IndicApi.getConfig] call; see [LicenseConfigWorker].
 */
object SeatLease {

    /**
     * True when this account currently holds a floating seat that should be
     * returned to the pool on sign-out or renewed while the app is open.
     */
    fun holdsFloatingSeat(context: Context): Boolean =
        LicenseEntitlements.needsSeat(context) && LicenseEntitlements.isLicensed(context)

    /**
     * Best-effort release before tokens are cleared. Never throws; a quiet
     * failure leaves the seat until the lease TTL, which the product already
     * tolerates.
     */
    suspend fun releaseBestEffort(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        seatCall(holdsFloatingSeat(context), api, tokens, "release") {
            AppRemoteConfig.apply(context, releaseLease(it))
        }
    }

    /**
     * Renew a held floating seat. Same best-effort contract as release — a
     * failure just means the next cycle (or a lapsed TTL) will demote.
     */
    suspend fun heartbeatBestEffort(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        seatCall(holdsFloatingSeat(context), api, tokens, "heartbeat") {
            AppRemoteConfig.apply(context, checkoutLease(it))
        }
    }

    /**
     * Fetch `/v1/config`, apply it, and re-checkout when the account still
     * looks licensed on a floating seat. Used by the background worker so an
     * idle phone learns a remote revoke without an open screen.
     */
    suspend fun refreshConfigAndSeatBestEffort(
        context: Context,
        api: CloudApi = IndicApi.get(context),
        tokens: TokenSource = TokenProvider,
    ) {
        val fetched = when (val outcome = api.authed(tokens) { getConfig(it) }) {
            is Authed.Ok -> Result.success(outcome.value)
            is Authed.Failed -> Result.failure(outcome.failure.cause)
            Authed.Disabled, Authed.NoToken -> return
        }
        if (AppRemoteConfig.record(context, fetched) && holdsFloatingSeat(context)) {
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
        val outcome = api.authed(tokens, call)
        if (outcome is Authed.Failed) logFailure(what, outcome.failure)
        return outcome is Authed.Ok
    }

    /** An unexpected throw is a bug, not a dropped connection, so it is logged louder. */
    private fun logFailure(what: String, failure: HttpFailure) {
        if (failure.kind == HttpFailure.Kind.UNEXPECTED) {
            Timber.e(failure.cause, "Floating-seat %s failed unexpectedly", what)
        } else {
            Timber.w(failure.cause, "Floating-seat %s failed (%s)", what, failure.kind)
        }
    }
}
