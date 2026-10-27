package com.sempermechanics.semper.ui.examples

import android.content.Context
import android.content.res.AssetManager
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.examples.ExampleCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.IOException

/** One paragraph of Credits & licences; a [heading] is shown bold. */
internal data class LicenceBlock(val text: String, val heading: Boolean = false)

/**
 * What Credits & licences shows (ADR-031, TD-349), as paragraphs, so the list
 * lays out only those on screen: the Play services notices alone are about a
 * megabyte of text.
 *
 * 1. Each example set's credit.
 * 2. Every library in the APK, from the AboutLibraries plugin's
 *    `res/raw/aboutlibraries.json`, with its licence, and where each licence is.
 * 3. The licence texts in `assets/licences/`: DICe's BSD-3-Clause, the engine's
 *    native libraries (OpenCV, Eigen and the codecs OpenCV links in), and the
 *    MIT and BSD libraries whose notice has to travel with them.
 * 4. The notices Google bundles inside Play services and Firebase, gathered at
 *    build time by `CollectPlayServicesNotices` (`app/build.gradle.kts`).
 */
internal object LicencePage {

    /** Where the full licence texts live in the APK's assets. */
    const val LICENCES_DIR = "licences"

    /** The file `CollectPlayServicesNotices` writes into the assets. */
    const val NOTICES_FILE = "notices/play-services-notices.txt"

    /** Opens each notice in [NOTICES_FILE]: the build task's `NOTICE_HEADING`. */
    private const val NOTICE_HEADING = "### "

    private val json = Json { ignoreUnknownKeys = true }

    /** A library in the APK, as the AboutLibraries plugin found it. */
    data class Library(val id: String, val name: String, val version: String, val licences: List<String>)

    /** A licence the libraries use: its plugin id, name and where its text is. */
    data class Licence(val id: String, val name: String, val url: String)

    /** The libraries and licences in [raw], the plugin's JSON. */
    fun parseLibraries(raw: String): Pair<List<Library>, Map<String, Licence>> {
        val root = json.parseToJsonElement(raw).jsonObject
        val libraries = root.getValue("libraries").jsonArray.map { element ->
            val o = element.jsonObject
            Library(
                id = o.string("uniqueId"),
                name = o.string("name").ifBlank { o.string("uniqueId") },
                version = o.string("artifactVersion"),
                licences = o["licenses"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            )
        }
        val licences = root["licenses"]?.jsonObject.orEmpty().mapValues { (id, element) ->
            val o = element.jsonObject
            Licence(id, o.string("name").ifBlank { id }, o.string("url"))
        }
        return libraries.sortedBy { it.name.lowercase() } to licences
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    fun build(context: Context, assets: AssetManager): List<LicenceBlock> = buildList {
        add(LicenceBlock(context.getString(R.string.licences_examples), heading = true))
        ExampleCatalog.load(assets).forEach { set ->
            add(LicenceBlock(set.title, heading = true))
            add(LicenceBlock(ExampleDetailSheet.creditText(context, set)))
        }

        val raw = context.resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().decodeToString() }
        val (libraries, licences) = parseLibraries(raw)
        add(LicenceBlock(context.getString(R.string.licences_libraries), heading = true))
        add(LicenceBlock(context.getString(R.string.licences_libraries_intro)))
        add(
            LicenceBlock(
                libraries.joinToString("\n") { lib ->
                    val names = lib.licences.joinToString(", ") { licences[it]?.name ?: it }
                    "${lib.name} ${lib.version} (${lib.id}): $names"
                },
            ),
        )
        add(LicenceBlock(context.getString(R.string.licences_used), heading = true))
        add(LicenceBlock(licences.values.sortedBy { it.name }.joinToString("\n") { "${it.name}: ${it.url}" }))

        add(LicenceBlock(context.getString(R.string.licences_texts), heading = true))
        listAssets(assets, LICENCES_DIR).forEach { name ->
            add(LicenceBlock(name.removeSuffix(".txt"), heading = true))
            addParagraphs(reflow(readAsset(assets, "$LICENCES_DIR/$name")))
        }

        val notices = try {
            readAsset(assets, NOTICES_FILE)
        } catch (e: IOException) {
            Timber.w(e, "No Play services notices")
            ""
        }
        if (notices.isNotBlank()) {
            add(LicenceBlock(context.getString(R.string.licences_play_services), heading = true))
            add(LicenceBlock(context.getString(R.string.licences_play_services_intro)))
            notices.split("\n$NOTICE_HEADING").forEach { entry ->
                val (names, text) = entry.removePrefix(NOTICE_HEADING).split("\n", limit = 2) + ""
                add(LicenceBlock(names.trim(), heading = true))
                addParagraphs(reflow(text))
            }
        }
    }

    private fun MutableList<LicenceBlock>.addParagraphs(text: String) =
        text.split("\n\n").filter(String::isNotBlank).forEach { add(LicenceBlock(it)) }

    private fun listAssets(assets: AssetManager, dir: String): List<String> = try {
        assets.list(dir).orEmpty().sorted()
    } catch (e: IOException) {
        Timber.w(e, "No licence texts")
        emptyList()
    }

    private fun readAsset(assets: AssetManager, path: String): String =
        assets.open(path).use { it.readBytes().decodeToString() }

    /**
     * A plain-text licence laid out for a phone. The files are wrapped at
     * about 80 columns, so shown as they are every line ends in a stray word
     * on a narrow screen. Each paragraph (lines between blank lines) becomes
     * one line the TextView wraps itself. A file that is a source comment
     * (DICe's: every line starts `//`) loses the slashes, and rule lines
     * (`****`, `====`, `----`) go. The words are unchanged.
     */
    fun reflow(licence: String): String {
        val lines = licence.lines()
        val commented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith("//") }
        return lines
            .map { if (commented) it.trimStart().removePrefix("//") else it }
            .filterNot { RULE.matches(it.trim()) }
            .joinToString("\n")
            .split(PARAGRAPH_BREAK)
            .map { paragraph -> paragraph.lines().map(String::trim).filter(String::isNotEmpty).joinToString(" ") }
            .filter(String::isNotEmpty)
            .joinToString("\n\n")
    }

    private val RULE = Regex("""[*=\-]{4,}""")
    private val PARAGRAPH_BREAK = Regex("""\n\s*\n""")
}
