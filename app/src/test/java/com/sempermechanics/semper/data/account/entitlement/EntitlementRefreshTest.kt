package com.sempermechanics.semper.data.account.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TD-210 (`refreshAfter` asks for a fetch) and TD-212 (a failed fetch after a checkout is retried, bounded). */
class EntitlementRefreshTest {

    private val tokens = TestTokens()

    private fun valid(): TokenStatus.Valid =
        TokenStatus.Valid((tokens.verifier().verify(tokens.token(), tokens.expected) as Verification.Verified).claims)

    @Test
    fun `a token past refreshAfter asks for a fetch, one before it does not`() {
        val status = valid()
        val refreshAfterMs = status.claims.refreshAfter * 1000
        assertEquals("a day after issue", (TestTokens.IAT + TestTokens.DAY_S) * 1000, refreshAfterMs)

        assertFalse(EntitlementRefresh.refreshDue(status, TestTokens.IAT * 1000))
        assertFalse(EntitlementRefresh.refreshDue(status, refreshAfterMs - 1))
        assertTrue(EntitlementRefresh.refreshDue(status, refreshAfterMs))
        assertTrue(EntitlementRefresh.refreshDue(status, refreshAfterMs + 3_600_000L))
    }

    @Test
    fun `a lapsed token always asks, no token never does`() {
        assertTrue(EntitlementRefresh.refreshDue(TokenStatus.Lapsed("GRACE_OVER"), 0L))
        assertTrue(EntitlementRefresh.refreshDue(TokenStatus.Lapsed("TAMPERED"), 0L))
        assertFalse(EntitlementRefresh.refreshDue(TokenStatus.NotUsed, Long.MAX_VALUE))
    }

    @Test
    fun `a time-travelled clock state decides refreshAfter, not the wall clock alone`() {
        // Uptime past refreshAfter with the wall clock held back: the effective
        // clock (EntitlementClock) is what the glue passes, and it is due.
        val serverMs = TestTokens.IAT * 1000
        val anchored = EntitlementClock.anchored(serverMs, DeviceTime(serverMs, 0L, 1))
        val now = DeviceTime(serverMs, 2 * DAY_MS, 1)
        val effective = EntitlementClock.effectiveNowMs(EntitlementClock.advance(anchored, now), now)

        assertTrue(EntitlementRefresh.refreshDue(valid(), effective))
    }

    @Test
    fun `only a fetch that was made and failed after a checkout queues the retry`() {
        assertTrue(EntitlementRefresh.retryAfterCheckout(ConfigFetch.FAILED))
        assertFalse(EntitlementRefresh.retryAfterCheckout(ConfigFetch.ANSWERED))
        assertFalse("no backend or nobody signed in", EntitlementRefresh.retryAfterCheckout(ConfigFetch.NOT_MADE))
    }

    @Test
    fun `the one-off retries a failed fetch a bounded number of times`() {
        val verdicts = (0 until EntitlementRefresh.MAX_ATTEMPTS).map {
            EntitlementRefresh.afterAttempt(ConfigFetch.FAILED, runAttemptCount = it)
        }
        assertEquals(List(EntitlementRefresh.MAX_ATTEMPTS - 1) { RefreshVerdict.RETRY } + RefreshVerdict.DONE, verdicts)
        assertEquals(RefreshVerdict.DONE, EntitlementRefresh.afterAttempt(ConfigFetch.FAILED, runAttemptCount = 99))
    }

    @Test
    fun `an answer, or nothing to ask, ends the one-off`() {
        assertEquals(RefreshVerdict.DONE, EntitlementRefresh.afterAttempt(ConfigFetch.ANSWERED, 0))
        assertEquals(RefreshVerdict.DONE, EntitlementRefresh.afterAttempt(ConfigFetch.NOT_MADE, 0))
    }

    @Test
    fun `a first sign-in on a phone asks config again once the device is registered`() {
        // The read beside /v1/me ran before the device had a key on the
        // server, so it could carry no token, answered or not.
        assertEquals(SignInRefresh.FETCH_AGAIN, EntitlementRefresh.afterSignIn(ConfigFetch.ANSWERED, true))
        assertEquals(SignInRefresh.FETCH_AGAIN, EntitlementRefresh.afterSignIn(ConfigFetch.FAILED, true))
    }

    @Test
    fun `a registered phone keeps an answer and queues the retry for a failed read`() {
        assertEquals(SignInRefresh.NONE, EntitlementRefresh.afterSignIn(ConfigFetch.ANSWERED, false))
        assertEquals(SignInRefresh.QUEUE, EntitlementRefresh.afterSignIn(ConfigFetch.FAILED, false))
    }

    @Test
    fun `no read made asks nothing more`() {
        assertEquals(SignInRefresh.NONE, EntitlementRefresh.afterSignIn(ConfigFetch.NOT_MADE, false))
        assertEquals(SignInRefresh.NONE, EntitlementRefresh.afterSignIn(ConfigFetch.NOT_MADE, true))
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
