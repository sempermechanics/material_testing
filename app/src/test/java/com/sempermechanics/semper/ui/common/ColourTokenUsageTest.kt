package com.sempermechanics.semper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Colours come from values/colors.xml (readiness AE1-007): a hex literal in a layout,
 * a background drawable, a style or UI Kotlin escapes the palette, so a token change
 * (a contrast fix, the night half) never reaches it. Icon vectors (ic_*, the launcher
 * and the wordmark) keep their own fills.
 */
class ColourTokenUsageTest {

    private val res = File("src/main/res")
    private val hex = Regex("""[">]#[0-9A-Fa-f]{3,8}["<]""")

    private fun literals(files: List<File>): List<String> = files.flatMap { file ->
        file.readLines().mapIndexedNotNull { i, line ->
            if (hex.containsMatchIn(line)) "${file.parentFile.name}/${file.name}:${i + 1}: ${line.trim()}" else null
        }
    }

    @Test
    fun `layouts, background drawables and styles use colour tokens`() {
        val layouts = res.resolve("layout").listFiles().orEmpty().filter { it.extension == "xml" }
        val drawables = res.resolve("drawable").listFiles().orEmpty().filter {
            it.extension == "xml" && !ICON.containsMatchIn(it.name)
        }
        val values = listOf(res.resolve("values/styles.xml"), res.resolve("values/themes.xml"))
        check(layouts.isNotEmpty() && drawables.isNotEmpty()) { "res not found from ${File(".").absolutePath}" }
        assertEquals(emptyList<String>(), literals(layouts + drawables + values))
    }

    /**
     * Kotlin UI code draws with tokens too: a Color constant, a hex string or a 0xFF… int
     * under ui/ escapes the palette as a hex in a layout does (readiness AE1-007). A file
     * whose job is to write a file keeps its literals, because those bytes must never
     * follow a theme: an *Export (an exported graph) or an *Encoder (the ROI mask the
     * engine reads). Comments are not code.
     */
    @Test
    fun `ui Kotlin draws with colour tokens`() {
        val ui = File("src/main/java/com/sempermechanics/semper/ui")
        val sources = ui.walkTopDown()
            .filter { it.extension == "kt" && !WRITES_A_FILE.containsMatchIn(it.name) }
            .sortedBy { it.path }
            .toList()
        check(sources.size > MIN_UI_SOURCES) { "ui/ not found from ${File(".").absolutePath}" }
        val found = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                val code = line.trim()
                val comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")
                if (!comment && KOTLIN_COLOUR.containsMatchIn(code.substringBefore(" // "))) {
                    "${file.relativeTo(ui).invariantSeparatorsPath}:${i + 1}: $code"
                } else {
                    null
                }
            }
        }
        assertEquals(emptyList<String>(), found)
    }

    private companion object {
        val ICON = Regex("""^(ic_|semper_)""")
        const val MIN_UI_SOURCES = 100
        val WRITES_A_FILE = Regex("""(Export|Encoder)\.kt$""")
        val KOTLIN_COLOUR = Regex(
            """\bColor\.(?:RED|GREEN|BLUE|YELLOW|CYAN|MAGENTA|GRAY|GREY|DKGRAY|LTGRAY|WHITE|BLACK)\b""" +
                """|"#[0-9A-Fa-f]{6,8}"|\b0x[Ff]{2}[0-9A-Fa-f]{6}\b|\bColor\.(?:a?rgb|parseColor)\(\s*["\d]""",
        )
    }
}
