package com.sempermechanics.semper.data.account.phones

import android.content.Context
import com.sempermechanics.semper.data.net.AccountPhonesDto
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.Authed
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.PhonesApi
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import com.sempermechanics.semper.util.rethrowIfCallerCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one Activate tap did (ADR-018). */
sealed interface ActivateOutcome {
    /** This phone is licensed now ([already] if it was before), [devicesActive] of [maxDevices]. */
    data class Activated(val already: Boolean, val devicesActive: Int, val maxDevices: Int) : ActivateOutcome

    /** Every slot is taken, [active] of [max]: deactivate a phone first. */
    data class LimitReached(val active: Int, val max: Int) : ActivateOutcome

    /** The account holds no live licence for this app. */
    data object NoLicence : ActivateOutcome

    /** A floating licence: its seat is checked out, not activated. */
    data object Floating : ActivateOutcome

    /** Activate on a new licence, and none is waiting (it was revoked, or already applied). */
    data object NoPendingUpgrade : ActivateOutcome

    /** No call went out (signed out, no backend) or no answer came back. */
    data object Offline : ActivateOutcome

    /** The backend refused for another reason; [failure] says which. */
    data class Refused(val failure: HttpFailure) : ActivateOutcome
}

/** What one Deactivate tap did. */
sealed interface DeactivateOutcome {
    /** The phone is no longer licensed; the licence has [devicesActive] of [maxDevices] in use. */
    data class Deactivated(val devicesActive: Int, val maxDevices: Int) : DeactivateOutcome

    /** It was not active, or not this account's: the list was stale. */
    data object NotFound : DeactivateOutcome

    data object Offline : DeactivateOutcome

    data class Refused(val failure: HttpFailure) : DeactivateOutcome
}

/** The account's phones, or why they could not be listed. */
sealed interface PhonesOutcome {
    data class Listed(val phones: AccountPhonesDto) : PhonesOutcome

    data object Offline : PhonesOutcome

    data class Refused(val failure: HttpFailure) : PhonesOutcome
}

/**
 * Activate, list and Deactivate the account's phones on its licence for this
 * app (ADR-033). The licence reaches the account on the server; this only
 * says which of the holder's phones it licenses, so nothing here takes a key.
 *
 * Activate answers with the config this phone holds from now on, which is
 * stored as `/v1/config`'s is ([AppRemoteConfig.apply], the entitlement token
 * with it), so every gate that reads the licence opens at once: no restart and
 * no second refresh. Deactivating this phone fetches the config again, so it is
 * Demo at once too. Neither touches an analysis on the phone: a licence change
 * decides what may be started, never what is kept.
 */
object DeviceActivation {

    /**
     * Activate this phone, or with [upgrade] move the account to the new
     * licence issued to it and activate this phone there.
     */
    suspend fun activate(
        context: Context,
        upgrade: Boolean = false,
        api: PhonesApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): ActivateOutcome = withContext(Dispatchers.IO) {
        when (val call = api.authed(tokens) { activateDevice(it, upgrade) }) {
            is Authed.Ok -> {
                // Off the main thread: storing seals the entitlement record with
                // commit() and reads the Keystore, as a config fetch does.
                AppRemoteConfig.apply(context, call.value.config, authoritative = true)
                val done = call.value.activation
                val shown = call.value.config.activation
                ActivateOutcome.Activated(
                    already = done?.already ?: false,
                    devicesActive = done?.devicesActive ?: shown?.devicesActive ?: 0,
                    maxDevices = done?.maxDevices ?: shown?.maxDevices ?: 0,
                )
            }
            is Authed.Failed -> activateRefusal(call.failure)
            Authed.Disabled, Authed.NoToken -> ActivateOutcome.Offline
        }
    }

    /** The account's phones for this app, this one marked. */
    suspend fun list(
        context: Context,
        api: PhonesApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): PhonesOutcome = when (val call = api.authed(tokens) { listDevices(it) }) {
        is Authed.Ok -> PhonesOutcome.Listed(call.value)
        is Authed.Failed -> if (call.failure.kind == HttpFailure.Kind.OFFLINE) {
            PhonesOutcome.Offline
        } else {
            PhonesOutcome.Refused(call.failure)
        }
        Authed.Disabled, Authed.NoToken -> PhonesOutcome.Offline
    }

    /**
     * Deactivate [deviceId], one of the account's phones. When it is this
     * phone, the caller fetches the config again
     * ([com.sempermechanics.semper.data.account.SeatLease.fetchConfig]) so it is
     * Demo now rather than at its next refresh.
     */
    suspend fun deactivate(
        context: Context,
        deviceId: String,
        api: PhonesApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): DeactivateOutcome = withContext(Dispatchers.IO) {
        when (val call = api.authed(tokens) { deactivateDevice(it, deviceId) }) {
            is Authed.Ok -> DeactivateOutcome.Deactivated(call.value.devicesActive, call.value.maxDevices)
            is Authed.Failed -> when {
                ApiErrors.hasCode(call.failure.body, ApiErrors.ACTIVATION_NOT_FOUND) -> DeactivateOutcome.NotFound
                call.failure.kind == HttpFailure.Kind.OFFLINE -> DeactivateOutcome.Offline
                else -> DeactivateOutcome.Refused(call.failure)
            }
            Authed.Disabled, Authed.NoToken -> DeactivateOutcome.Offline
        }
    }

    /** Activate's refusals by code; anything unnamed is [ActivateOutcome.Refused]. */
    internal fun activateRefusal(failure: HttpFailure): ActivateOutcome {
        val detail = ApiErrors.detailOf(failure.body)
        return when {
            ApiErrors.isCode(detail, ApiErrors.DEVICE_LIMIT_REACHED) -> limitReached(detail)
            ApiErrors.isCode(detail, ApiErrors.NO_LICENSE) -> ActivateOutcome.NoLicence
            ApiErrors.isCode(detail, ApiErrors.SEATING_FLOATING) -> ActivateOutcome.Floating
            ApiErrors.isCode(detail, ApiErrors.NO_PENDING_UPGRADE) -> ActivateOutcome.NoPendingUpgrade
            failure.kind == HttpFailure.Kind.OFFLINE -> ActivateOutcome.Offline
            else -> ActivateOutcome.Refused(failure)
        }
    }

    /** `device_limit_reached: 2/2` as its count; 0 of 0 when the tail is missing or malformed. */
    internal fun limitReached(detail: String): ActivateOutcome.LimitReached {
        val numbers = detail.substringAfter(':', "").trim().split('/').map { it.trim().toIntOrNull() }
        val active = numbers.getOrNull(0) ?: 0
        val max = numbers.getOrNull(1) ?: 0
        return ActivateOutcome.LimitReached(active, max)
    }

    /**
     * [CloudApi]'s `authed` for [PhonesApi]: no call without a backend or a
     * token, a failure classified rather than thrown, cancellation kept.
     */
    private suspend fun <T> PhonesApi.authed(tokens: TokenSource, call: suspend PhonesApi.(String) -> T): Authed<T> {
        val token = if (enabled) tokens.usableIdToken() else null
        return when {
            !enabled -> Authed.Disabled
            token == null -> Authed.NoToken
            else -> try {
                Authed.Ok(call(token))
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                e.rethrowIfCallerCancelled()
                Authed.Failed(HttpFailure.classify(e))
            }
        }
    }
}
