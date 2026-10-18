package com.sempermechanics.semper.ui.account

import androidx.annotation.StringRes
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.HttpFailure.Kind

/**
 * Why a licence activation (`POST /v1/licenses/activate`) did not go through,
 * and what the user is told ([messageRes]). The failure domain of ADR-018 for
 * the activation screen.
 *
 * Nothing here is retried on its own: the screen stays up with the message,
 * and the user taps Activate again. [TRY_AGAIN], [RATE_LIMITED] and [NETWORK]
 * say so; the others need the key, the account or support to change first.
 */
enum class ActivationError(@StringRes val messageRes: Int) {
    /** `license_not_found`: no licence has this key. */
    INVALID_KEY(R.string.activate_error_invalid_key),

    /** `license_already_redeemed`: another account holds this individual key. */
    ALREADY_REDEEMED(R.string.activate_error_already_redeemed),

    /** `already_licensed`: this account already has a different licence. */
    ALREADY_LICENSED(R.string.activate_error_already_licensed),

    /** `license_revoked`. */
    REVOKED(R.string.activate_error_revoked),

    /** `license_expired`: past its expiry and grace. */
    EXPIRED(R.string.activate_error_expired),

    /** `license_email_mismatch`: issued to another address, or another institution's domain. */
    WRONG_ACCOUNT(R.string.activate_error_wrong_account),

    /** `license_device_mismatch`: the licence is bound to another phone. */
    OTHER_DEVICE(R.string.activate_error_other_device),

    /** `device_in_use`: this phone is bound to another account. */
    DEVICE_IN_USE(R.string.activate_error_device_in_use),

    /** `license_seats_exhausted`: the institution licence is full. */
    SEATS_FULL(R.string.activate_error_seats_full),

    /** `license_seat_disabled`: the institution put this member's seat on hold. */
    SEAT_DISABLED(R.string.activate_error_seat_disabled),

    /** `not_approved`: the account is not approved yet. */
    NOT_APPROVED(R.string.activate_error_not_approved),

    /** `app_check_required`: this build could not be attested. */
    APP_CHECK(R.string.error_app_check_required),

    /** 401: the sign-in was refused. */
    SIGN_IN_AGAIN(R.string.activate_error_sign_in_again),

    /** 429 (`rate_limited`), past the client's own retries. */
    RATE_LIMITED(R.string.activate_error_rate_limited),

    /** A lost race (`claim_contended`, `device_lock_contended`) or a 5xx: nothing was granted. */
    TRY_AGAIN(R.string.activate_error_try_again),

    /** No answer: offline, no backend in this build, or no usable sign-in token. */
    NETWORK(R.string.activate_error_network),

    /** Anything else. */
    UNKNOWN(R.string.activate_error_unknown),
    ;

    companion object {

        /** The backend's `detail` codes, matched before the failure's [Kind]. */
        private val BY_CODE: List<Pair<String, ActivationError>> = listOf(
            ApiErrors.LICENSE_NOT_FOUND to INVALID_KEY,
            ApiErrors.LICENSE_ALREADY_REDEEMED to ALREADY_REDEEMED,
            ApiErrors.ALREADY_LICENSED to ALREADY_LICENSED,
            ApiErrors.LICENSE_REVOKED to REVOKED,
            ApiErrors.LICENSE_EXPIRED to EXPIRED,
            ApiErrors.LICENSE_EMAIL_MISMATCH to WRONG_ACCOUNT,
            ApiErrors.LICENSE_DEVICE_MISMATCH to OTHER_DEVICE,
            ApiErrors.DEVICE_IN_USE to DEVICE_IN_USE,
            ApiErrors.LICENSE_SEATS_EXHAUSTED to SEATS_FULL,
            ApiErrors.LICENSE_SEAT_DISABLED to SEAT_DISABLED,
            ApiErrors.NOT_APPROVED to NOT_APPROVED,
            ApiErrors.APP_CHECK_REQUIRED to APP_CHECK,
            ApiErrors.RATE_LIMITED to RATE_LIMITED,
            ApiErrors.CLAIM_CONTENDED to TRY_AGAIN,
            ApiErrors.DEVICE_LOCK_CONTENDED to TRY_AGAIN,
        )

        /** What [failure] means for an activation. */
        fun of(failure: HttpFailure): ActivationError = of(failure.kind, ApiErrors.detailOf(failure.body))

        /**
         * The error for a failure of [kind] whose body's `detail` is [detail]
         * (empty when the backend did not answer). The code decides when it
         * is one the route returns: `device_in_use` arrives as a plain 409, a
         * licence refusal as a plain 403 or 409. Otherwise the kind does.
         */
        fun of(kind: Kind, detail: String): ActivationError =
            BY_CODE.firstOrNull { (code, _) -> ApiErrors.isCode(detail, code) }?.second ?: ofKind(kind)

        private fun ofKind(kind: Kind): ActivationError = when (kind) {
            Kind.OFFLINE -> NETWORK
            Kind.RATE_LIMITED -> RATE_LIMITED
            Kind.SERVER -> TRY_AGAIN
            Kind.DEVICE_IN_USE -> DEVICE_IN_USE
            Kind.NOT_APPROVED -> NOT_APPROVED
            Kind.UNAUTHORIZED -> SIGN_IN_AGAIN
            else -> UNKNOWN
        }
    }
}
