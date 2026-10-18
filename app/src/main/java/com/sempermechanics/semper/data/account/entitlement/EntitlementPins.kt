package com.sempermechanics.semper.data.account.entitlement

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The backend's entitlement signing keys this build trusts, by `kid`.
 *
 * Fed from `SEMPER_ENTITLEMENT_PUBKEYS` (`kid:base64SPKI,kid2:base64SPKI`,
 * BuildConfig). More than one `kid` is how a key rotates: ship the new key
 * beside the old, then let the backend sign with it. An empty set means this
 * build ignores tokens altogether and keeps the unsigned `/v1/config` cache as
 * the only source, exactly as builds before tokens did.
 */
class EntitlementPins private constructor(
    val keys: Map<String, PublicKey>,
    /** Entries that were not `kid:SPKI` for an EC P-256 key, or repeated a `kid`. */
    val invalid: List<String>,
) {
    val isEmpty: Boolean get() = keys.isEmpty()

    companion object {
        private const val P256_FIELD_BITS = 256

        val NONE = EntitlementPins(emptyMap(), emptyList())

        /** The pins in [spec]; blank entries are skipped, unusable ones listed in [invalid]. */
        fun parse(spec: String): EntitlementPins {
            val keys = LinkedHashMap<String, PublicKey>()
            val invalid = ArrayList<String>()
            spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { entry ->
                val kid = entry.substringBefore(':', missingDelimiterValue = "").trim()
                val key = p256Key(entry.substringAfter(':', missingDelimiterValue = "").trim())
                if (kid.isEmpty() || key == null || kid in keys) {
                    invalid += kid.ifEmpty { entry.take(MAX_SHOWN) }
                } else {
                    keys[kid] = key
                }
            }
            return EntitlementPins(keys, invalid)
        }

        /** An EC P-256 public key from base64 (standard alphabet) SubjectPublicKeyInfo DER. */
        fun p256Key(base64Spki: String): PublicKey? = runCatching {
            val der = Base64.getDecoder().decode(base64Spki)
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        }.getOrNull()?.takeIf { it is ECPublicKey && it.params.curve.field.fieldSize == P256_FIELD_BITS }

        /** How much of a malformed entry a log line may repeat. */
        private const val MAX_SHOWN = 8
    }
}

/** The `dkt` claim: what binds a token to this device's Keystore key. */
object DeviceKeyThumbprint {

    /** base64url (no padding) of SHA-256 over [spkiDer], a public key's X.509 SubjectPublicKeyInfo. */
    fun of(spkiDer: ByteArray): String = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(spkiDer))
}

/**
 * The short app name the backend puts in a token's `app` claim, from this
 * build's applicationId. Mirrors backend `app/apps.py` (`_BY_APPLICATION_ID`);
 * an id it does not know maps to itself, which no token names.
 */
object EntitlementApp {

    private val BY_APPLICATION_ID = mapOf(
        "com.sempermechanics.semper" to "semper",
        "com.sempermechanics.materialtesting" to "materialtesting",
        "com.indicvision.semper" to "semper",
        "com.indicvision.semper.materialtesting" to "materialtesting",
    )

    fun shortId(applicationId: String): String = BY_APPLICATION_ID[applicationId] ?: applicationId
}
