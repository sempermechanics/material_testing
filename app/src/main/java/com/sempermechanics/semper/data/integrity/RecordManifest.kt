package com.sempermechanics.semper.data.integrity

import com.sempermechanics.semper.data.session.SessionRecordSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What a run's `record_manifest.json` says about the results it wrote
 * (ADR-028, `docs/adr/ADR-028-tamper-evident-records.md`): when, by which
 * app and engine, with which settings, the SHA-256 of every result file, and
 * a signature by the device key over all of it.
 *
 * The signed bytes are [ManifestJson.canonicalBytes]: this object as JSON
 * without [signature], keys sorted at every level, no whitespace. The file is
 * the same JSON with [signature] added, so a verifier gets the signed bytes
 * back from the file alone, whatever version wrote it.
 *
 * @property v the manifest format, [VERSION]
 * @property engine the engine submodule's commit (`BuildConfig.ENGINE_PIN`)
 * @property keyThumbprint `DeviceKeyThumbprint.of` [publicKey]'s DER; null unsigned
 * @property publicKey the device key's SubjectPublicKeyInfo DER, base64; null unsigned
 * @property signature SHA256withECDSA over the canonical bytes, base64 DER;
 *   null when the device key could not sign
 */
@Serializable
data class RecordManifest(
    val v: Int = VERSION,
    val createdAt: CreatedAt,
    val app: AppStamp,
    val engine: String,
    val runSpec: RunSpecRecord,
    val files: List<FileEntry>,
    val keyThumbprint: String? = null,
    val publicKey: String? = null,
    val signature: String? = null,
) {
    /**
     * When the manifest was written, by the phone's clock. The clock is the
     * user's to set, so [source] says it is not a trusted time.
     */
    @Serializable
    data class CreatedAt(val source: String = DEVICE_CLOCK, val utc: String)

    /** The build that ran the analysis. */
    @Serializable
    data class AppStamp(val versionCode: Int, val versionName: String)

    /** One result file, by its name in the session directory. */
    @Serializable
    data class FileEntry(val name: String, val size: Long, val sha256: String)

    /** The run's settings, as its session row records them ([SessionRecordSettings]). */
    @Serializable
    data class RunSpecRecord(
        val subset: Int,
        val step: Int,
        val strainWin: Int,
        val roiX: Int,
        val roiY: Int,
        val roiW: Int,
        val roiH: Int,
        val use6x6: Boolean,
        val refUpdateInterval: Int,
    ) {
        companion object {
            fun of(settings: SessionRecordSettings) = RunSpecRecord(
                subset = settings.subset,
                step = settings.step,
                strainWin = settings.strainWin,
                roiX = settings.roiX,
                roiY = settings.roiY,
                roiW = settings.roiW,
                roiH = settings.roiH,
                use6x6 = settings.use6x6,
                refUpdateInterval = settings.refUpdateInterval,
            )
        }
    }

    companion object {
        const val VERSION = 1

        /** [CreatedAt.source]: the phone's own clock, which the user can set. */
        const val DEVICE_CLOCK = "device-clock-untrusted"
    }
}

/** The manifest's JSON: the file's text and the canonical bytes the signature covers. */
object ManifestJson {

    /** The member that holds the signature, left out of what it signs. */
    const val SIGNATURE = "signature"

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        // A later version's fields are read past here and still verified:
        // the signature covers the file's JSON, not this model.
        ignoreUnknownKeys = true
    }

    /** [manifest] as the file holds it: sorted keys, no whitespace. */
    fun encode(manifest: RecordManifest): String = sorted(toJson(manifest)).toString()

    /** What [manifest]'s signature covers: [encode] without [SIGNATURE]. */
    fun canonicalBytes(manifest: RecordManifest): ByteArray = canonicalBytes(toJson(manifest).jsonObject)

    /** What a manifest read as [obj] was signed over: [obj] without [SIGNATURE], sorted, compact, UTF-8. */
    fun canonicalBytes(obj: JsonObject): ByteArray =
        sorted(JsonObject(obj - SIGNATURE)).toString().toByteArray(Charsets.UTF_8)

    /** A manifest file's [text], as JSON and as the model; null when it is not one. */
    fun parse(text: String): Parsed? = try {
        val obj = json.parseToJsonElement(text).jsonObject
        Parsed(obj, json.decodeFromJsonElement(RecordManifest.serializer(), obj))
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        // jsonObject on an array or a primitive.
        null
    }

    private fun toJson(manifest: RecordManifest): JsonElement =
        json.encodeToJsonElement(RecordManifest.serializer(), manifest)

    /** A manifest as read: its JSON ([obj], what was signed) and its model. */
    class Parsed(val obj: JsonObject, val manifest: RecordManifest)

    /**
     * [element] with every object's keys in ascending order. Keys are ASCII,
     * so this is byte order too. Arrays keep their order.
     */
    private fun sorted(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.keys.sorted().associateWith { sorted(element.getValue(it)) })
        is JsonArray -> JsonArray(element.map(::sorted))
        else -> element
    }
}
