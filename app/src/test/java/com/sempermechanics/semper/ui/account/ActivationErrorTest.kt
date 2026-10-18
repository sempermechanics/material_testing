package com.sempermechanics.semper.ui.account

import com.sempermechanics.semper.data.net.HttpFailure.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What the activation screen says for each refusal of
 * `POST /v1/licenses/activate`. The codes and their statuses are
 * `backend/app/errors.py` (`STATUS`); `activateLicense` throws a plain
 * `ApiException` for every one, so the code decides, not the kind.
 */
class ActivationErrorTest {

    private fun of(kind: Kind, detail: String = "") = ActivationError.of(kind, detail)

    @Test
    fun `each licence refusal has its own message`() {
        assertEquals(ActivationError.INVALID_KEY, of(Kind.NOT_FOUND, "license_not_found"))
        assertEquals(ActivationError.ALREADY_REDEEMED, of(Kind.CONFLICT, "license_already_redeemed"))
        assertEquals(ActivationError.ALREADY_LICENSED, of(Kind.CONFLICT, "already_licensed"))
        assertEquals(ActivationError.REVOKED, of(Kind.FORBIDDEN, "license_revoked"))
        assertEquals(ActivationError.EXPIRED, of(Kind.FORBIDDEN, "license_expired"))
        assertEquals(ActivationError.WRONG_ACCOUNT, of(Kind.FORBIDDEN, "license_email_mismatch"))
        assertEquals(ActivationError.OTHER_DEVICE, of(Kind.FORBIDDEN, "license_device_mismatch"))
        assertEquals(ActivationError.SEATS_FULL, of(Kind.CONFLICT, "license_seats_exhausted"))
        assertEquals(ActivationError.SEAT_DISABLED, of(Kind.CONFLICT, "license_seat_disabled"))
    }

    @Test
    fun `the account and device refusals of the bearer route are named too`() {
        assertEquals(ActivationError.DEVICE_IN_USE, of(Kind.CONFLICT, "device_in_use"))
        assertEquals(ActivationError.NOT_APPROVED, of(Kind.FORBIDDEN, "not_approved"))
        assertEquals(ActivationError.APP_CHECK, of(Kind.FORBIDDEN, "app_check_required"))
    }

    @Test
    fun `a throttle, a lost race and a server fault each say to try again`() {
        assertEquals(ActivationError.RATE_LIMITED, of(Kind.RATE_LIMITED, "rate_limited"))
        assertEquals(ActivationError.RATE_LIMITED, of(Kind.RATE_LIMITED, "Quota exceeded"))
        assertEquals(ActivationError.TRY_AGAIN, of(Kind.SERVER, "claim_contended"))
        assertEquals(ActivationError.TRY_AGAIN, of(Kind.SERVER, "device_lock_contended"))
        assertEquals(ActivationError.TRY_AGAIN, of(Kind.SERVER, "internal_error"))
        assertEquals(ActivationError.TRY_AGAIN, of(Kind.SERVER))
    }

    @Test
    fun `no answer is the network, whatever else is known`() {
        assertEquals(ActivationError.NETWORK, of(Kind.OFFLINE))
    }

    @Test
    fun `a code with a suffix still matches, and a code inside other text does not`() {
        assertEquals(ActivationError.REVOKED, of(Kind.FORBIDDEN, "license_revoked: since 2026-10-01"))
        assertEquals(ActivationError.UNKNOWN, of(Kind.FORBIDDEN, "not license_revoked"))
    }

    @Test
    fun `the typed device failures map by kind`() {
        assertEquals(ActivationError.DEVICE_IN_USE, of(Kind.DEVICE_IN_USE))
        assertEquals(ActivationError.NOT_APPROVED, of(Kind.NOT_APPROVED))
        assertEquals(ActivationError.SIGN_IN_AGAIN, of(Kind.UNAUTHORIZED, "invalid_token"))
    }

    @Test
    fun `anything else is unknown, never a guess`() {
        assertEquals(ActivationError.UNKNOWN, of(Kind.NOT_FOUND))
        assertEquals(ActivationError.UNKNOWN, of(Kind.REJECTED, "invalid_device_id"))
        assertEquals(ActivationError.UNKNOWN, of(Kind.CONFLICT, "something_new"))
        assertEquals(ActivationError.UNKNOWN, of(Kind.UNEXPECTED))
        assertEquals(ActivationError.UNKNOWN, of(Kind.DEVICE_CONFLICT))
    }

    @Test
    fun `every error has a message`() {
        ActivationError.entries.forEach { assertNotEquals("$it has no message", 0, it.messageRes) }
    }
}
