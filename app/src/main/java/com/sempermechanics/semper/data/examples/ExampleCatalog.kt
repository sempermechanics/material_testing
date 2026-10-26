package com.sempermechanics.semper.data.examples

import android.content.res.AssetManager
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.IOException

/**
 * The example sets bundled under `assets/examples/` (ADR-031), read from each
 * folder's `example.json`. Reading is quick (five small files) but touches
 * the asset manager, so callers run [load] off the main thread.
 */
object ExampleCatalog {

    /** Where the sets live in the APK's assets. */
    const val ROOT = "examples"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Every set whose manifest parses, in their `order`. A set that fails to
     * parse is logged and left out rather than failing the whole screen.
     */
    fun load(assets: AssetManager): List<ExampleManifest> {
        val ids = try {
            assets.list(ROOT).orEmpty()
        } catch (e: IOException) {
            Timber.w(e, "No example sets")
            return emptyList()
        }
        // A phone lists one level; Robolectric lists every file under it, so keep the folder names.
        return ids.map { it.substringBefore('/') }.distinct().mapNotNull { read(assets, it) }.sortedBy { it.order }
    }

    /** The set [id], or null when there is none or its manifest is unreadable. */
    fun find(assets: AssetManager, id: String): ExampleManifest? = read(assets, id)

    /** The asset path of [file] in the set [id]. */
    fun path(id: String, file: String): String = "$ROOT/$id/$file"

    fun parse(text: String): ExampleManifest = json.decodeFromString(ExampleManifest.serializer(), text)

    private fun read(assets: AssetManager, id: String): ExampleManifest? = try {
        assets.open(path(id, ExampleManifest.MANIFEST)).use { stream ->
            parse(stream.readBytes().decodeToString()).takeIf { it.id == id }
        }
    } catch (e: IOException) {
        Timber.w(e, "Example %s has no manifest", id)
        null
    } catch (e: SerializationException) {
        Timber.w(e, "Example %s manifest does not parse", id)
        null
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "Example %s manifest is invalid", id)
        null
    }
}
