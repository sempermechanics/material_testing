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

/** One line of Credits & licences: a section heading, a paragraph, or a row. */
internal sealed interface LicenceItem {
    data class Heading(val text: String) : LicenceItem
    data class Paragraph(val text: String) : LicenceItem

    /** A part of the app and its licence; a tap opens [target], when it has one. */
    data class Row(val title: String, val subtitle: String, val target: Target? = null) : LicenceItem
}

/** Where a row leads: a page of the app's own, by [LicencePage.page] key, or the website. */
internal sealed interface Target {
    data class Page(val key: String) : Target
    data class Link(val url: String) : Target
}

/**
 * What Credits & licences shows (ADR-031, TD-57): one list of every part of
 * the app and the licence it is under. The full texts, and the notices Google
 * bundles inside Play services and Firebase, are on the website
 * (`licences_full_url`, built by semper-website's `tools/build-licences.py`
 * from this build). The app keeps only what has to travel with it: each example
 * set's credit (CC BY 4.0 §3(a)) and the short BSD, MIT, IJG, zlib, libpng and
 * libtiff notices in `assets/licences/` and each DICe set's `LICENSE.DICe`.
 */
internal object LicencePage {

    /** The short notices that travel with the app. */
    const val LICENCES_DIR = "licences"

    /** A short notice's page key: `notice:<file in LICENCES_DIR>`. */
    private const val NOTICE = "notice:"

    /** An example set's credit page key: `credit:<set id>`. */
    private const val CREDIT = "credit:"

    /** The notice in [LICENCES_DIR] a library carries with it, by its Maven coordinates. */
    val NOTICE_BY_LIBRARY = mapOf(
        "org.checkerframework:checker-qual" to "checker-qual-MIT.txt",
        "androidx.datastore:datastore-preferences-external-protobuf" to "protobuf-BSD-3-Clause.txt",
    )

    /** The engine's libraries whose licence text is long and only on the website. */
    private val ENGINE_WITHOUT_NOTICE = listOf("OpenCV" to "Apache-2.0", "Eigen" to "MPL-2.0")

    /** The licence part of a notice's file name: `libpng-PNG-2.txt` is libpng under PNG-2. */
    private val LICENCE_SUFFIX = Regex("""-(IJG-BSD-3-Clause-Zlib|BSD-3-Clause|Apache-2\.0|MIT|Zlib|PNG-2|libtiff)$""")

    private val json = Json { ignoreUnknownKeys = true }

    /** A library in the APK, as the AboutLibraries plugin found it. */
    data class Library(val id: String, val name: String, val version: String, val licences: List<String>)

    /** A licence the libraries use: its plugin id, short name, full name and where its text is. */
    data class Licence(val id: String, val shortName: String, val name: String, val url: String)

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
            val name = o.string("name").ifBlank { id }
            Licence(id, o.string("spdxId").ifBlank { name }, name, o.string("url"))
        }
        return libraries.sortedBy { it.name.lowercase() } to licences
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    /** The whole list. Reads the library JSON and the assets, so call it off the main thread. */
    fun items(context: Context, assets: AssetManager): List<LicenceItem> = buildList {
        val website = context.getString(R.string.licences_full_url)
        add(LicenceItem.Paragraph(context.getString(R.string.licences_intro)))
        val fullTexts = context.getString(R.string.licences_full_texts)
        add(LicenceItem.Row(fullTexts, website.removePrefix("https://"), Target.Link(website)))

        add(LicenceItem.Heading(context.getString(R.string.licences_examples)))
        ExampleCatalog.load(assets).forEach { set ->
            add(LicenceItem.Row(set.title, set.credit.licence, Target.Page(CREDIT + set.id)))
        }

        add(LicenceItem.Heading(context.getString(R.string.licences_engine)))
        val notices = listAssets(assets).map { file ->
            val stem = file.removeSuffix(".txt")
            val match = LICENCE_SUFFIX.find(stem)
            val name = match?.let { stem.removeRange(it.range) } ?: stem
            name to LicenceItem.Row(name, match?.groupValues?.get(1).orEmpty(), Target.Page(NOTICE + file))
        }
        val engine = ENGINE_WITHOUT_NOTICE.map { (name, licence) -> name to LicenceItem.Row(name, licence) } +
            notices.filter { (name, _) -> NOTICE_BY_LIBRARY.values.none { it.startsWith("$name-") } }
        engine.sortedBy { it.first.lowercase() }.forEach { add(it.second) }

        val raw = context.resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().decodeToString() }
        val (libraries, licences) = parseLibraries(raw)
        add(LicenceItem.Heading(context.getString(R.string.licences_libraries)))
        libraries.forEach { lib ->
            val names = lib.licences.joinToString(", ") { licences[it]?.shortName ?: it }
            val notice = NOTICE_BY_LIBRARY[lib.id]?.let { Target.Page(NOTICE + it) }
            add(LicenceItem.Row(lib.name, "${lib.version} · $names", notice))
        }

        add(LicenceItem.Heading(context.getString(R.string.licences_play_services)))
        add(
            LicenceItem.Row(
                context.getString(R.string.licences_play_services_row),
                context.getString(R.string.licences_on_website),
                Target.Link(website + "#play-services"),
            ),
        )
    }

    /** The title and paragraphs of the page [key] names, or null for an unknown key. */
    fun page(context: Context, assets: AssetManager, key: String): Pair<String, List<String>>? = when {
        key.startsWith(CREDIT) -> ExampleCatalog.find(assets, key.removePrefix(CREDIT))?.let { set ->
            val credit = ExampleDetailSheet.creditText(context, set).lines()
            // A BSD set's licence asks for its text to travel with every copy: DICe's LICENSE.DICe.
            val licence = set.credit.licenceFile?.let { reflow(readAsset(assets, ExampleCatalog.path(set.id, it))) }
            set.title to credit + listOfNotNull(licence).flatMap { it.split("\n\n") }
        }
        key.startsWith(NOTICE) -> {
            val file = key.removePrefix(NOTICE)
            if (file !in listAssets(assets)) {
                null
            } else {
                val stem = file.removeSuffix(".txt")
                val title = LICENCE_SUFFIX.find(stem)?.let { stem.removeRange(it.range) } ?: stem
                title to reflow(readAsset(assets, "$LICENCES_DIR/$file")).split("\n\n")
            }
        }
        else -> null
    }

    private fun listAssets(assets: AssetManager): List<String> = try {
        assets.list(LICENCES_DIR).orEmpty().sortedBy { it.lowercase() }
    } catch (e: IOException) {
        Timber.w(e, "No licence notices")
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
