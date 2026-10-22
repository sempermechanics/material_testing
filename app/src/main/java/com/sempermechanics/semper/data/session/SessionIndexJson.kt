package com.sempermechanics.semper.data.session

import com.sempermechanics.semper.ext.AppExtensions
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * `sessions/index.json` as text: the rows [SessionStore] keeps, and how they
 * are written. Keys the reader does not know are skipped, so a row an older or
 * newer build wrote still reads.
 *
 * An installed [com.sempermechanics.semper.ext.SessionExtension] names the
 * top-level keys its older builds wrote ([legacyKeys][com.sempermechanics.semper.ext.SessionExtension.legacyKeys]);
 * those move into [SessionRecord.extras] before decoding, so the next write
 * keeps them, under `extras` (ADR-025). With none named, as in Semper, the
 * text is decoded straight, exactly as before.
 */
internal object SessionIndexJson {

    /** The row key [SessionRecord.extras] is written under. */
    const val EXTRAS_KEY = "extras"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val rows = ListSerializer(SessionRecord.serializer())

    /** The rows in [text]; throws for text that is not an index. */
    fun decode(text: String, legacyKeys: Set<String> = AppExtensions.session.legacyKeys): List<SessionRecord> {
        if (legacyKeys.isEmpty()) return json.decodeFromString(rows, text)
        val lifted = json.parseToJsonElement(text).jsonArray.map { liftLegacyKeys(it, legacyKeys) }
        return json.decodeFromJsonElement(rows, JsonArray(lifted))
    }

    /** [records] as the index's text. */
    fun encode(records: List<SessionRecord>): String = json.encodeToString(records)

    /**
     * [row] with its top-level [keys] moved into its `extras` object. A key
     * `extras` already holds keeps that value: it is the newer of the two.
     * A row with none of [keys], or that is not an object, is returned as it is.
     */
    fun liftLegacyKeys(row: JsonElement, keys: Set<String>): JsonElement {
        val obj = row as? JsonObject
        if (obj == null || obj.keys.none { it in keys }) return row
        val extras = LinkedHashMap<String, JsonElement>()
        val rest = LinkedHashMap<String, JsonElement>()
        for ((key, value) in obj) {
            if (key in keys) extras[key] = value else rest[key] = value
        }
        (obj[EXTRAS_KEY] as? JsonObject)?.let(extras::putAll)
        rest[EXTRAS_KEY] = JsonObject(extras)
        return JsonObject(rest)
    }
}
