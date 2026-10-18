package com.sempermechanics.semper.data.account.entitlement

import org.junit.Assert.assertEquals
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SealedEntitlementTest {

    private fun hmac(key: String) = Tagger { data ->
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
            doFinal(data)
        }
    }

    private val tagger = hmac("device-key")

    private val record = SealedEntitlement.Record(
        token = "h.p.s",
        clock = ClockState(
            lastServerMs = 1_800_000_000_000L,
            wallAtServerMs = 1_799_999_000_000L,
            highWaterWallMs = 1_800_000_500_000L,
            elapsedSinceServerMs = 42L,
            lastElapsedMs = 9_000L,
            lastBootCount = 3,
            rolledBack = false,
        ),
    )

    @Test
    fun `a sealed record unseals to itself`() {
        fun roundTrip(r: SealedEntitlement.Record) = SealedEntitlement.unseal(SealedEntitlement.seal(r, tagger), tagger)
        assertEquals(SealedEntitlement.Unsealed.Ok(record), roundTrip(record))
        val rolled = record.copy(clock = record.clock.copy(rolledBack = true, lastBootCount = DeviceTime.UNKNOWN_BOOT))
        assertEquals(SealedEntitlement.Unsealed.Ok(rolled), roundTrip(rolled))
    }

    @Test
    fun `an edited clock state reads as tampered`() {
        val sealed = SealedEntitlement.seal(record, tagger)
        val body = Base64Url.decode(sealed.substringBefore('.'))!!.decodeToString()
        val lowered = body.replace("\"hw\":1800000500000", "\"hw\":1")
        check(lowered != body)
        val forged = Base64Url.encode(lowered.toByteArray()) + "." + sealed.substringAfter('.')
        assertEquals(SealedEntitlement.Unsealed.Tampered, SealedEntitlement.unseal(forged, tagger))
    }

    @Test
    fun `a record re-tagged under another key reads as tampered`() {
        val sealed = SealedEntitlement.seal(record, hmac("attacker"))
        assertEquals(SealedEntitlement.Unsealed.Tampered, SealedEntitlement.unseal(sealed, tagger))
    }

    @Test
    fun `a tagger that throws reads as tampered`() {
        val sealed = SealedEntitlement.seal(record, tagger)
        val broken = Tagger { error("keystore unavailable") }
        assertEquals(SealedEntitlement.Unsealed.Tampered, SealedEntitlement.unseal(sealed, broken))
    }

    @Test
    fun `garbage and well-tagged junk read as tampered`() {
        listOf("", ".", "abc", "abc.def", "!!.!!").forEach {
            assertEquals(it, SealedEntitlement.Unsealed.Tampered, SealedEntitlement.unseal(it, tagger))
        }
        val junk = "{\"v\":2}".toByteArray()
        val tagged = Base64Url.encode(junk) + "." + Base64Url.encode(tagger.tag(junk))
        assertEquals(SealedEntitlement.Unsealed.Tampered, SealedEntitlement.unseal(tagged, tagger))
    }
}
