package com.sempermechanics.semper.data.account.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementPolicyTest {

    private val tokens = TestTokens()
    private val serverMs = TestTokens.IAT * 1000
    private val synced = DeviceTime(serverMs, 1_000L, 1)
    private val clock = EntitlementClock.anchored(serverMs, synced)

    private fun claims(token: String) =
        (tokens.verifier().verify(token, tokens.expected) as Verification.Verified).claims

    private fun sealed(token: String = tokens.token(), state: ClockState = clock) =
        StoredEntitlement.Sealed(SealedEntitlement.Record(token, state))

    private fun evaluate(stored: StoredEntitlement, now: DeviceTime = synced) =
        EntitlementPolicy.evaluate(
            stored,
            (stored as? StoredEntitlement.Sealed)?.let { tokens.verifier().verify(it.record.token, tokens.expected) },
            now,
        )

    @Test
    fun `no stored token is the legacy path, unbounded`() {
        val (status, state) = evaluate(StoredEntitlement.Nothing)
        assertEquals(TokenStatus.NotUsed, status)
        assertNull(state)
        assertEquals(EntitlementPolicy.UNBOUNDED, EntitlementPolicy.bound(status, DEMO))
    }

    @Test
    fun `a verified token inside grace grants what it names`() {
        val (status, _) = evaluate(sealed())
        assertTrue("$status", status is TokenStatus.Valid)
        assertEquals(TokenBound(true, true, true, 500), EntitlementPolicy.bound(status, DEMO))
    }

    @Test
    fun `past grace the bound is demo`() {
        val late = DeviceTime(serverMs + 8 * DAY_MS, 1_000L + 8 * DAY_MS, 1)
        val (status, state) = evaluate(sealed(), late)
        assertEquals(TokenStatus.Lapsed("GRACE_OVER"), status)
        assertEquals(8 * DAY_MS, state!!.elapsedSinceServerMs)
        assertEquals(TokenBound(false, false, false, DEMO), EntitlementPolicy.bound(status, DEMO))
    }

    @Test
    fun `a tampered store lapses to demo`() {
        val (status, _) = evaluate(StoredEntitlement.Tampered)
        assertEquals(TokenStatus.Lapsed("TAMPERED"), status)
        assertEquals(TokenBound(false, false, false, DEMO), EntitlementPolicy.bound(status, DEMO))
    }

    @Test
    fun `a token that does not verify here is no token, never demo`() {
        // A signing key this build does not pin (mis-provisioned, or rotated
        // before its pin shipped) must not demote a licensed fleet.
        val foreign = tokens.token(key = TestTokens.p256())
        val (status, state) = evaluate(sealed(foreign))
        assertEquals(TokenStatus.NotUsed, status)
        assertEquals(clock, state)
        assertEquals(EntitlementPolicy.UNBOUNDED, EntitlementPolicy.bound(status, DEMO))
        assertEquals(TokenStatus.NotUsed, EntitlementPolicy.evaluate(sealed(), null, synced).first)
        val keyUnreadable = Verification.Rejected(RejectReason.WRONG_DEVICE_KEY)
        assertEquals(TokenStatus.NotUsed, EntitlementPolicy.evaluate(sealed(), keyUnreadable, synced).first)
    }

    @Test
    fun `a demo token bounds to demo with its own ceiling`() {
        val status = TokenStatus.Valid(claims(tokens.token(claims = tokens.claims(mode = "demo", maxAnalyses = 25))))
        assertEquals(TokenBound(false, false, false, 25), EntitlementPolicy.bound(status, DEMO))
        val noCeiling = TokenStatus.Valid(claims(tokens.token(claims = tokens.claims(mode = "demo", maxAnalyses = 0))))
        assertEquals(DEMO, EntitlementPolicy.bound(noCeiling, DEMO).maxAnalyses)
    }

    @Test
    fun `features the token withholds stay off`() {
        val withheld = tokens.claims(share = false, backup = false, maxAnalyses = 0)
        val status = TokenStatus.Valid(claims(tokens.token(claims = withheld)))
        assertEquals(TokenBound(true, false, false, null), EntitlementPolicy.bound(status, DEMO))
    }

    @Test
    fun `the cap only ever narrows`() {
        assertEquals(999, EntitlementPolicy.cap(999, EntitlementPolicy.UNBOUNDED))
        assertEquals(25, EntitlementPolicy.cap(999, TokenBound(false, false, false, 25)))
        assertEquals(10, EntitlementPolicy.cap(10, TokenBound(true, true, true, 500)))
        assertEquals(Int.MAX_VALUE, EntitlementPolicy.cap(Int.MAX_VALUE, TokenBound(true, true, true, null)))
    }

    @Test
    fun `with the token lapsed the shown ceiling is the demo cap that is enforced (TD-263)`() {
        val late = DeviceTime(serverMs + 8 * DAY_MS, 1_000L + 8 * DAY_MS, 1)
        val bound = EntitlementPolicy.bound(evaluate(sealed(), late).first, DEMO)
        val serverMax = 999

        val enforced = EntitlementPolicy.cap(serverMax, bound)
        assertEquals(DEMO, enforced)
        assertEquals(enforced, EntitlementPolicy.shownCap(serverMax, bound))
    }

    @Test
    fun `the shown ceiling is the enforced one, or 0 while the server has reported none`() {
        val bounds = listOf(
            EntitlementPolicy.UNBOUNDED,
            TokenBound(false, false, false, DEMO),
            TokenBound(true, true, true, 500),
            TokenBound(true, true, true, null),
        )
        bounds.forEach { bound ->
            listOf(5, 25, 999).forEach { serverMax ->
                val enforced = EntitlementPolicy.cap(serverMax, bound)
                assertEquals("$bound $serverMax", enforced, EntitlementPolicy.shownCap(serverMax, bound))
            }
            assertEquals(0, EntitlementPolicy.shownCap(0, bound))
        }
    }

    @Test
    fun `a floating seat whose token ran past its lease needs the seat screen (TD-266)`() {
        val pastLease = DeviceTime(serverMs + 8 * DAY_MS, 1_000L + 8 * DAY_MS, 1)
        val graceOver = evaluate(sealed(), pastLease).first
        assertEquals(TokenStatus.Lapsed("GRACE_OVER"), graceOver)
        assertTrue(EntitlementPolicy.seatRequired(serverLicensed = true, graceOver))
        assertTrue(EntitlementPolicy.seatRequired(serverLicensed = true, TokenStatus.Lapsed("EXPIRED")))
    }

    @Test
    fun `the seat screen otherwise follows the server alone`() {
        // No token (no pins, none sent, or one that does not verify): as before tokens.
        assertFalse(EntitlementPolicy.seatRequired(serverLicensed = true, TokenStatus.NotUsed))
        assertTrue(EntitlementPolicy.seatRequired(serverLicensed = false, TokenStatus.NotUsed))
        val (valid, _) = evaluate(sealed())
        assertFalse(EntitlementPolicy.seatRequired(serverLicensed = true, valid))
        assertTrue(EntitlementPolicy.seatRequired(serverLicensed = false, valid))
        // A rollback or an altered record is demo's limits, not a seat question.
        assertFalse(EntitlementPolicy.seatRequired(serverLicensed = true, TokenStatus.Lapsed("ROLLBACK")))
        assertFalse(EntitlementPolicy.seatRequired(serverLicensed = true, TokenStatus.Lapsed("TAMPERED")))
    }

    @Test
    fun `only a key with no record and no seal in flight reads as tampering (TD-265)`() {
        fun reading(hasRecord: Boolean, key: KeyPresence, sealing: Boolean = false) =
            EntitlementPolicy.reading(hasRecord, key, sealing)

        assertEquals(StoreReading.NOTHING, reading(false, KeyPresence.ABSENT))
        assertEquals(StoreReading.NOTHING, reading(false, KeyPresence.ABSENT, sealing = true))
        assertEquals(StoreReading.UNSEAL, reading(true, KeyPresence.PRESENT))
        assertEquals(StoreReading.UNSEAL, reading(true, KeyPresence.PRESENT, sealing = true))
        assertEquals("a lost key", StoreReading.DROP_RECORD, reading(true, KeyPresence.ABSENT))
        assertEquals("a crash before the write", StoreReading.DROP_KEY, reading(false, KeyPresence.PRESENT, true))
        assertEquals("a deleted record", StoreReading.TAMPERED, reading(false, KeyPresence.PRESENT))
        assertEquals(StoreReading.NOTHING, reading(true, KeyPresence.UNREADABLE))
        assertEquals(StoreReading.NOTHING, reading(false, KeyPresence.UNREADABLE))
    }

    @Test
    fun `intake follows the pins and the route`() {
        assertEquals(TokenIntake.IGNORE, EntitlementPolicy.intake(false, "t", authoritative = true))
        assertEquals(TokenIntake.IGNORE, EntitlementPolicy.intake(false, null, authoritative = true))
        assertEquals(TokenIntake.STORE, EntitlementPolicy.intake(true, "t", authoritative = false))
        assertEquals(TokenIntake.CLEAR, EntitlementPolicy.intake(true, null, authoritative = true))
        assertEquals(TokenIntake.CLEAR, EntitlementPolicy.intake(true, " ", authoritative = true))
        assertEquals(TokenIntake.KEEP, EntitlementPolicy.intake(true, null, authoritative = false))
    }

    private companion object {
        const val DEMO = 25
        const val DAY_MS = 86_400_000L
    }
}
