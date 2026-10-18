package com.sempermechanics.semper.data.account.entitlement

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * RFC 4648 §5 base64url with no padding: the encoding of every part of a
 * compact JWS (RFC 7515 §2). Pure JVM; `java.util.Base64` is API 26+, and
 * minSdk is 30.
 */
internal object Base64Url {

    private val ALPHABET = Regex("^[A-Za-z0-9_-]*$")

    /** A base64 quantum is 4 characters; a remainder of 1 can hold no whole byte. */
    private const val QUANTUM = 4

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * The bytes of [text], or null when it is not unpadded base64url: padding,
     * a `+` or `/`, whitespace and an impossible length are all refused, so one
     * token has one spelling.
     */
    fun decode(text: String): ByteArray? =
        if (!ALPHABET.matches(text) || text.length % QUANTUM == 1) {
            null
        } else {
            runCatching { decoder.decode(text) }.getOrNull()
        }
}

/**
 * ECDSA signature encodings. A JWS carries `r || s` raw (RFC 7518 §3.4);
 * `java.security.Signature("SHA256withECDSA")` reads and writes ASN.1 DER
 * `SEQUENCE { INTEGER r, INTEGER s }`.
 */
internal object EcdsaDer {

    /** P-256: `r` and `s` are 32 bytes each, big-endian. */
    const val P256_COORDINATE_BYTES = 32
    const val P256_RAW_BYTES = 2 * P256_COORDINATE_BYTES

    private const val SEQUENCE: Byte = 0x30
    private const val INTEGER: Byte = 0x02
    private const val SIGN_BIT = 0x80

    /**
     * DER for a raw P-256 signature, or null when [raw] is not 64 bytes. Both
     * integers stay under 128 bytes, so every length is DER's short form.
     */
    fun rawToDer(raw: ByteArray): ByteArray? {
        if (raw.size != P256_RAW_BYTES) return null
        val body = integer(raw.copyOfRange(0, P256_COORDINATE_BYTES)) +
            integer(raw.copyOfRange(P256_COORDINATE_BYTES, P256_RAW_BYTES))
        return byteArrayOf(SEQUENCE, body.size.toByte()) + body
    }

    /** A DER INTEGER for an unsigned big-endian value: minimal, positive. */
    private fun integer(unsigned: ByteArray): ByteArray {
        var start = 0
        while (start < unsigned.size - 1 && unsigned[start] == 0.toByte()) start++
        var magnitude = unsigned.copyOfRange(start, unsigned.size)
        if (magnitude[0].toInt() and SIGN_BIT != 0) magnitude = byteArrayOf(0) + magnitude
        return byteArrayOf(INTEGER, magnitude.size.toByte()) + magnitude
    }
}

/**
 * A compact JWS split into its parts (RFC 7515 §7.1). Parsing checks only the
 * shape: three base64url parts and a JSON object for a header. Nothing in the
 * payload is read until the signature over [signingInput] has been checked.
 */
internal class CompactJws private constructor(
    /** ASCII(`header_b64 "." payload_b64`): the bytes the signature covers. */
    val signingInput: ByteArray,
    val header: JsonObject,
    val payload: ByteArray,
    val signature: ByteArray,
) {
    companion object {
        private const val PARTS = 3

        fun parse(token: String): CompactJws? {
            val parts = token.split('.')
            if (parts.size != PARTS) return null
            val (headerB64, payloadB64, signatureB64) = parts
            val headerBytes = Base64Url.decode(headerB64)
            val payload = Base64Url.decode(payloadB64)
            val signature = Base64Url.decode(signatureB64)
            val header = headerBytes?.let(::jsonObject)
            return if (header == null || payload == null || signature == null) {
                null
            } else {
                CompactJws("$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII), header, payload, signature)
            }
        }

        /** [bytes] as strict UTF-8 JSON, when that is an object. */
        fun jsonObject(bytes: ByteArray): JsonObject? = runCatching {
            Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
        }.getOrNull()
    }
}
