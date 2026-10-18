package com.sempermechanics.semper.ui.account

import android.content.Context
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.account.SeatLease
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.Authed
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.data.net.authed
import timber.log.Timber

/** How one activation ended. */
sealed interface ActivationOutcome {
    /** The key was redeemed and the account's config is stored. */
    data object Activated : ActivationOutcome

    /** It was not; [error] says why. */
    data class Refused(val error: ActivationError) : ActivationOutcome
}

/**
 * Redeems a licence key on this account (`POST /v1/licenses/activate`), then
 * refreshes `/v1/config` the way a floating-seat checkout does
 * (`SeatRequiredActivity`): the activation answer carries the account's
 * config but no entitlement token, so the config is applied at once and
 * `/v1/config` is fetched for the token, and the periodic refresh is
 * scheduled.
 */
object LicenseActivation {

    suspend fun activate(
        context: Context,
        key: String,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): ActivationOutcome = when (val outcome = api.authed(tokens) { activateLicense(it, key) }) {
        is Authed.Ok -> {
            AppRemoteConfig.apply(context, outcome.value)
            SeatLease.refreshConfigBestEffort(context, api, tokens)
            LicenseConfigWorker.enqueue(context)
            ActivationOutcome.Activated
        }
        Authed.Disabled, Authed.NoToken -> ActivationOutcome.Refused(ActivationError.NETWORK)
        is Authed.Failed -> ActivationOutcome.Refused(refusal(outcome.failure))
    }

    /**
     * Whether to offer activation: the server has the account on demo, and it
     * is not a floating-seat member waiting for a seat (whose licence is
     * already on the account, so a key would be refused `already_licensed`).
     */
    fun isOffered(context: Context): Boolean =
        isOffered(LicenseEntitlements.serverSaysLicensed(context), LicenseEntitlements.needsSeat(context))

    /** [isOffered] on the values it reads. */
    fun isOffered(serverSaysLicensed: Boolean, needsSeat: Boolean): Boolean = !serverSaysLicensed && !needsSeat

    private fun refusal(failure: HttpFailure): ActivationError {
        val error = ActivationError.of(failure)
        // The typed key is never logged: it is the licence.
        if (error == ActivationError.UNKNOWN) {
            Timber.w(failure.cause, "Licence activation failed (%s)", failure.kind)
        } else {
            Timber.i("Licence activation refused: %s", error)
        }
        return error
    }
}
