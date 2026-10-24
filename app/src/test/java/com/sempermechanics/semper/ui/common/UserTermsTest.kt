package com.sempermechanics.semper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One user-facing word per concept in the app's strings (readiness AE3-001..008):
 * "back up" for the cloud copy, "phone" for the handset, "analysis" for a saved
 * run, "the demo" for the free tier, "free up" for removing local copies, and
 * British spelling. Identifiers keep "device" (Device ID, device key).
 */
class UserTermsTest {

    private val strings: Map<String, String> by lazy {
        val xml = File("src/main/res/values/strings.xml").readText()
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun offenders(pattern: String, except: (String, String) -> Boolean = { _, _ -> false }): List<String> {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        return strings.filter { (name, value) -> regex.containsMatchIn(value) && !except(name, value) }.keys.sorted()
    }

    @Test
    fun `the cloud copy is a backup, not a sync`() {
        val iFrames = { _: String, value: String -> "I-frame" in value }
        assertEquals(emptyList<String>(), offenders("""\bsync(ed|ing)?\b""", iFrames) + offenders("save to cloud"))
    }

    @Test
    fun `the handset is a phone`() {
        assertEquals(
            emptyList<String>(),
            offenders("""\b(this|another|requested|the) device\b|\bto device\b|profile and device"""),
        )
    }

    @Test
    fun `a saved run is an analysis and the free tier is the demo`() {
        assertEquals(emptyList<String>(), offenders("exporting session") + offenders("demo mode"))
    }

    @Test
    fun `removing local copies is freeing them up, not releasing them`() {
        assertEquals(emptyList<String>(), offenders("""\breleased\b|removed from this phone"""))
    }

    @Test
    fun `the after-expiry clock is called grace`() {
        assertTrue(strings.getValue("license_grace_until_fmt").contains("after-expiry grace"))
        assertTrue(strings.getValue("license_grace").contains("after-expiry grace"))
    }

    @Test
    fun `spelling is British`() {
        assertEquals(emptyList<String>(), offenders("""organiz|initializ"""))
    }
}
