package com.sempermechanics.semper.data.account.entitlement

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Signs entitlement tokens the way the backend does, for tests that need
 * tokens with times of their own. The vectors (`entitlement_vector*.json`)
 * prove the encoding against Python; this proves everything else.
 */
internal class TestTokens(val kid: String = "test-kid") {

    val signer: KeyPair = p256()
    val device: KeyPair = p256()

    val pins: EntitlementPins
        get() = EntitlementPins.parse("$kid:" + Base64.getEncoder().encodeToString(signer.public.encoded))

    val expected = EntitlementVerifier.Expected(
        appId = "semper",
        deviceId = DEVICE_ID,
        deviceKeyThumbprint = DeviceKeyThumbprint.of(device.public.encoded),
    )

    fun header(alg: String = "ES256", typ: String = "semper-ent+jws", kid: String = this.kid): JsonObject =
        buildJsonObject {
            put("alg", alg)
            put("kid", kid)
            put("typ", typ)
        }

    @Suppress("LongParameterList") // one defaulted knob per claim a test varies
    fun claims(
        iat: Long = IAT,
        graceUntil: Long = iat + GRACE_DAYS * DAY_S,
        exp: Long = iat + EXP_DAYS * DAY_S,
        mode: String = "licensed",
        share: Boolean = true,
        backup: Boolean = true,
        maxAnalyses: Int = 500,
        overrides: Map<String, JsonPrimitive> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("iss", "semper-api")
        put("sub", "uid-1")
        put("app", "semper")
        put("dev", DEVICE_ID)
        put("dkt", expected.deviceKeyThumbprint)
        put("mode", mode)
        putJsonObject("features") {
            put("share", share)
            put("backup", backup)
            put("maxAnalyses", maxAnalyses)
        }
        put("lic", if (mode == "licensed") "lic-1" else null)
        put("iat", iat)
        put("refreshAfter", iat + DAY_S)
        put("graceUntil", graceUntil)
        put("exp", exp)
        overrides.forEach { (k, v) -> put(k, v) }
    }

    fun token(header: JsonObject = header(), claims: JsonObject = claims(), key: KeyPair = signer): String {
        val input = Base64Url.encode(header.toString().toByteArray()) + "." +
            Base64Url.encode(claims.toString().toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key.private)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return input + "." + Base64Url.encode(derToRaw(der))
    }

    fun verifier(): EntitlementVerifier = EntitlementVerifier(pins)

    companion object {
        const val DEVICE_ID = "and-test-device"
        const val IAT = 1_800_000_000L
        const val DAY_S = 86_400L
        const val GRACE_DAYS = 7L
        const val EXP_DAYS = 30L

        fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        /** DER `SEQUENCE { INTEGER r, INTEGER s }` to raw `r || s`, 32 bytes each. */
        fun derToRaw(der: ByteArray): ByteArray {
            var at = 2
            fun next(): ByteArray {
                check(der[at] == 0x02.toByte())
                val len = der[at + 1].toInt()
                val value = der.copyOfRange(at + 2, at + 2 + len)
                at += 2 + len
                val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
                return ByteArray(32 - trimmed.size) + trimmed
            }
            return next() + next()
        }
    }
}
