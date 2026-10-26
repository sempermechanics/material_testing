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
        listOf("{\"v\":1}", "{\"v\":0}", "{\"token\":\"h.p.s\"}").forEach {
            val junk = it.toByteArray()
            val tagged = Base64Url.encode(junk) + "." + Base64Url.encode(tagger.tag(junk))
            assertEquals(it, SealedEntitlement.Unsealed.Tampered, SealedEntitlement.unseal(tagged, tagger))
        }
    }

    /**
     * A v1 record as phones store it (readiness TE1-004). The fixture was
     * computed outside this code, in Python, from the format the seal writes:
     * the JSON body `{"v":1,"token":"h.p.s","ls":…,"rb":false}` with this
     * file's [record], base64url without padding, ".", and its HMAC-SHA256
     * under the key "device-key". A VERSION bump must keep this test passing:
     * a phone upgraded past it still holds a v1 record.
     */
    private val sealedV1: String by lazy {
        checkNotNull(javaClass.getResource("/entitlement/sealed_v1.txt")).readText().trim()
    }

    @Test
    fun `a stored v1 record still unseals`() {
        assertEquals(SealedEntitlement.Unsealed.Ok(record), SealedEntitlement.unseal(sealedV1, tagger))
    }

    @Test
    fun `this build writes v1 byte for byte`() {
        assertEquals(sealedV1, SealedEntitlement.seal(record, tagger))
    }

    @Test
    fun `a well-tagged record from a newer build is not tampering`() {
        // A phone that ran a newer build and went back to this one: the MAC
        // is this phone's, so nothing was edited; this build just cannot read it.
        val newer = "{\"v\":2,\"token\":\"h.p.s\"}".toByteArray()
        val tagged = Base64Url.encode(newer) + "." + Base64Url.encode(tagger.tag(newer))
        assertEquals(SealedEntitlement.Unsealed.Newer, SealedEntitlement.unseal(tagged, tagger))
        // Untagged, the same body is still tampering.
        val forged = Base64Url.encode(newer) + "." + Base64Url.encode(hmac("attacker").tag(newer))
        assertEquals(SealedEntitlement.Unsealed.Tampered, SealedEntitlement.unseal(forged, tagger))
    }
}
