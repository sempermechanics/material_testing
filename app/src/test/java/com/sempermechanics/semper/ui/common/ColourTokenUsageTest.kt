package com.sempermechanics.semper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Colours come from values/colors.xml (readiness AE1-007): a hex literal in a layout,
 * a background drawable or a style escapes the palette, so a token change (a contrast
 * fix, the night half) never reaches it. Icon vectors (ic_*, the launcher and the
 * wordmark) keep their own fills.
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

    private companion object {
        val ICON = Regex("""^(ic_|semper_)""")
    }
}
