package com.sempermechanics.semper.ui.common

import com.sempermechanics.semper.data.examples.ExampleDownloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app calls itself semper Classroom wherever a user reads its name (ADR-030;
 * readiness MT-AE3-001), with the product name in lower case (readiness ADDENDUM-4).
 * "semper" alone is kept only where it names the shared service or company, or the
 * other app beside this one ("semper and semper Classroom"): a semper licence or
 * account, semper support, the semper website, reaching semper's server, and
 * "improve semper", the Terms' own words for the improvement consent
 * (TERMS_OF_SERVICE.md 7.3). The PDF's name is ReportWordingTest's.
 *
 * Every text node of the strings files is read, so this app's plurals (the lab
 * Results' "The whole test", the example sets' setup line) are held to it too, as
 * are the READMEs Save to phone copies out with each example set and the folder it
 * copies them into. The sets' CREDIT.txt files are licence notices and stay as
 * written (ADDENDUM-4 excludes legal text).
 */
class AppNameTest {

    private val stringsFiles by lazy {
        File("src/main/res/values").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
            .sortedBy { it.name }
    }

    private val strings: Map<String, String> by lazy {
        stringsFiles.flatMap { file ->
            Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(file.readText())
                .map { it.groupValues[1] to it.groupValues[2] }
                .toList()
        }.toMap()
    }

    /** Every text node in the strings files, `<string>` and plural `<item>` alike. */
    private val texts: List<String> by lazy {
        stringsFiles.flatMap { file ->
            Regex(""">([^<>]+)</(string|item)>""").findAll(file.readText()).map { it.groupValues[1] }.toList()
        }
    }

    private val readmes: List<File> by lazy {
        File("src/main/assets/examples").listFiles().orEmpty()
            .map { File(it, "README.md") }
            .filter { it.isFile }
            .sortedBy { it.path }
    }

    @Test
    fun `the app's own name is semper Classroom`() {
        assertEquals("semper Classroom", strings.getValue("app_name"))
        assertEquals("semper Classroom", strings.getValue("app_name_short"))
        val bare = Regex(
            """(?<!improve |reach )\bsemper\b(?! (Classroom|licence|account|support|website|and semper Classroom))""",
            RegexOption.IGNORE_CASE,
        )
        assertEquals(emptyList<String>(), texts.filter { bare.containsMatchIn(it) })
    }

    @Test
    fun `no text says Semper with a capital, plurals included`() {
        val capital = Regex("""\bSemper\b(?! Mechanics\b)""")
        assertEquals(emptyList<String>(), texts.filter { capital.containsMatchIn(it) })
    }

    @Test
    fun `the free tier is Demo in this app's text too, plurals included`() {
        val tier = Regex("""\bdemo\b|\b(on|to) the [Dd]emo\b|\b[Dd]emo mode\b""")
        assertEquals(emptyList<String>(), texts.filter { tier.containsMatchIn(it) })
    }

    @Test
    fun `the example sets name the app and their folder in lower case`() {
        assertTrue("no example README found", readmes.isNotEmpty())
        val capital = Regex("""\bSemper\b(?! Mechanics\b)""")
        val offending = readmes.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> capital.containsMatchIn(line) }
                .map { (i, line) -> "${file.parentFile?.name}/README.md:${i + 1}: ${line.take(80)}" }
        }
        assertEquals(emptyList<String>(), offending)
        assertEquals("semper examples", ExampleDownloads.PARENT_FOLDER)
    }
}
