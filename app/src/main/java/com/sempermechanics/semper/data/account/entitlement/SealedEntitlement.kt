package com.sempermechanics.semper.data.account.entitlement

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** A MAC over bytes: HMAC-SHA256 under a Keystore key on the phone, a plain key in tests. */
fun interface Tagger {
    fun tag(data: ByteArray): ByteArray
}

/**
 * The stored entitlement: the token as received and its [ClockState], as one
 * string, `base64url(json) "." base64url(tag)`. The tag is what makes an edited
 * clock state (a lowered high-water mark, a cleared rollback) read as
 * [Unsealed.Tampered] instead of as the truth. The token carries its own
 * signature; sealing it with the clock only keeps the pair together.
 */
object SealedEntitlement {

    data class Record(val token: String, val clock: ClockState)

    sealed interface Unsealed {
        data class Ok(val record: Record) : Unsealed

        /** The tag does not match, or the body is not a record this build wrote. */
        data object Tampered : Unsealed

        /**
         * The tag matches, and the body says a newer build wrote it: a phone
         * that went back a version. Nothing was edited, so it is no token,
         * not tampering (readiness TE1-004).
         */
        data object Newer : Unsealed
    }

    /**
     * The record's format. A bump must keep reading the older versions a
     * phone may still hold: SealedEntitlementTest unseals a committed v1.
     */
    private const val VERSION = 1

    fun seal(record: Record, tagger: Tagger): String {
        val body = buildJsonObject {
            put("v", VERSION)
            put("token", record.token)
            put("ls", record.clock.lastServerMs)
            put("ws", record.clock.wallAtServerMs)
            put("hw", record.clock.highWaterWallMs)
            put("es", record.clock.elapsedSinceServerMs)
            put("le", record.clock.lastElapsedMs)
            put("bc", record.clock.lastBootCount)
            put("rb", record.clock.rolledBack)
        }.toString().toByteArray(Charsets.UTF_8)
        return Base64Url.encode(body) + "." + Base64Url.encode(tagger.tag(body))
    }

    /** [text] checked against [tagger]; a [Tagger] that throws reads as tampered too. */
    fun unseal(text: String, tagger: Tagger): Unsealed {
        val body = Base64Url.decode(text.substringBefore('.', missingDelimiterValue = ""))
        val tag = Base64Url.decode(text.substringAfter('.', missingDelimiterValue = ""))
        val expected = body?.let { runCatching { tagger.tag(it) }.getOrNull() }
        val json = if (expected != null && tag != null && MessageDigest.isEqual(expected, tag)) {
            CompactJws.jsonObject(body)
        } else {
            null
        }
        val version = (json?.get("v") as? JsonPrimitive)?.longOrNull
        val record = json?.let(::record)
        return when {
            version != null && version > VERSION -> Unsealed.Newer
            record != null -> Unsealed.Ok(record)
            else -> Unsealed.Tampered
        }
    }

    private fun record(json: JsonObject): Record? = runCatching {
        require(json.long("v") == VERSION.toLong()) { "unknown version" }
        val token = (json["token"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        Record(
            token = requireNotNull(token) { "no token" },
            clock = ClockState(
                lastServerMs = json.long("ls"),
                wallAtServerMs = json.long("ws"),
                highWaterWallMs = json.long("hw"),
                elapsedSinceServerMs = json.long("es"),
                lastElapsedMs = json.long("le"),
                lastBootCount = requireNotNull((json["bc"] as? JsonPrimitive)?.intOrNull) { "no boot" },
                rolledBack = requireNotNull((json["rb"] as? JsonPrimitive)?.booleanOrNull) { "no rollback flag" },
            ),
        )
    }.getOrNull()

    private fun JsonObject.long(key: String): Long = requireNotNull((this[key] as? JsonPrimitive)?.longOrNull) { key }
}
