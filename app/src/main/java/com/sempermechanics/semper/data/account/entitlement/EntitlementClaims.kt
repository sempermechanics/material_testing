package com.sempermechanics.semper.data.account.entitlement

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** What the licence lets this account do, as the backend signed it. */
data class EntitlementFeatures(
    val share: Boolean,
    val backup: Boolean,
    /** The analysis ceiling; 0 or less means the token sets none. */
    val maxAnalyses: Int,
)

/**
 * The payload of an entitlement token (`typ` `semper-ent+jws`), read only after
 * its signature checked out. Times are epoch **seconds** (JWT NumericDate), so
 * neither a time zone nor DST enters anything that compares them.
 */
data class EntitlementClaims(
    /** Always [ISSUER]. */
    val iss: String,
    /** The account (Firebase uid). */
    val sub: String,
    /** The application id the token was issued to. */
    val app: String,
    /** The device id ([com.sempermechanics.semper.data.account.DeviceKeys.deviceId]). */
    val dev: String,
    /** base64url SHA-256 of the device key's SubjectPublicKeyInfo DER ([DeviceKeyThumbprint]). */
    val dkt: String,
    /** `licensed` or `demo`. */
    val mode: String,
    val features: EntitlementFeatures,
    /** The licence id, when there is one. */
    val lic: String?,
    val iat: Long,
    /** Past this, an online app should fetch a fresh token. */
    val refreshAfter: Long,
    /** Offline use ends here: the server's time at issue plus the licence's offline grace. */
    val graceUntil: Long,
    val exp: Long,
) {
    val isLicensed: Boolean get() = mode == MODE_LICENSED

    companion object {
        const val ISSUER = "semper-api"
        const val MODE_LICENSED = "licensed"

        /** The claims in [payload], or null when one is missing or of the wrong JSON type. */
        fun parse(payload: ByteArray): EntitlementClaims? {
            val json = CompactJws.jsonObject(payload) ?: return null
            val features = json["features"] as? JsonObject
            return runCatching {
                EntitlementClaims(
                    iss = json.string("iss"),
                    sub = json.string("sub"),
                    app = json.string("app"),
                    dev = json.string("dev"),
                    dkt = json.string("dkt"),
                    mode = json.string("mode"),
                    features = EntitlementFeatures(
                        share = features.boolean("share"),
                        backup = features.boolean("backup"),
                        maxAnalyses = features.long("maxAnalyses").toIntClamped(),
                    ),
                    lic = json.optionalString("lic"),
                    iat = json.long("iat"),
                    refreshAfter = json.long("refreshAfter"),
                    graceUntil = json.long("graceUntil"),
                    exp = json.long("exp"),
                )
            }.getOrNull()
        }

        private fun JsonObject?.primitive(key: String): JsonPrimitive =
            requireNotNull(this?.get(key) as? JsonPrimitive) { "missing $key" }

        private fun JsonObject.string(key: String): String {
            val value = primitive(key)
            require(value.isString) { "$key is not a string" }
            return value.content
        }

        private fun JsonObject.optionalString(key: String): String? {
            val value: JsonElement = this[key] ?: return null
            return if (value is JsonNull) null else string(key)
        }

        /** A JSON number with no fraction; a quoted number is refused. */
        private fun JsonObject?.long(key: String): Long {
            val value = primitive(key)
            require(!value.isString) { "$key is quoted" }
            return requireNotNull(value.longOrNull) { "$key is not an integer" }
        }

        private fun JsonObject?.boolean(key: String): Boolean {
            val value = primitive(key)
            require(!value.isString) { "$key is quoted" }
            return requireNotNull(value.booleanOrNull) { "$key is not a boolean" }
        }

        private fun Long.toIntClamped(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
