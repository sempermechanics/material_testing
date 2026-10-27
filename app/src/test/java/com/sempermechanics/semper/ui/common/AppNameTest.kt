package com.sempermechanics.semper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The app calls itself Semper Classroom wherever a user reads its name (ADR-030;
 * readiness MT-AE3-001). "Semper" alone is kept only where it names the shared
 * service or company, or the other app beside this one ("Semper and Semper
 * Classroom"): a Semper licence or account, Semper support, the Semper
 * website, reaching Semper's server, and "improve Semper", the Terms' own words
 * for the improvement consent (TERMS_OF_SERVICE.md 7.3). The PDF's name is
 * ReportWordingTest's.
 */
class AppNameTest {

    private val strings: Map<String, String> by lazy {
        File("src/main/res/values").listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }.orEmpty()
            .flatMap { file ->
                Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(file.readText())
                    .map { it.groupValues[1] to it.groupValues[2] }
                    .toList()
            }.toMap()
    }

    @Test
    fun `the app's own name is Semper Classroom`() {
        assertEquals("Semper Classroom", strings.getValue("app_name"))
        val bare = Regex(
            """(?<!improve |reach )\bSemper\b(?! (Classroom|licence|account|support|website|and Semper Classroom))""",
        )
        assertEquals(emptyList<String>(), strings.filterValues { bare.containsMatchIn(it) }.keys.sorted())
    }
}
