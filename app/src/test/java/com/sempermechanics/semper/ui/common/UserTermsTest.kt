package com.sempermechanics.semper.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One user-facing word per concept in the app's strings (readiness AE3-001..008):
 * "back up" for the cloud copy, "phone" for the handset, "analysis" for a saved
 * run, "free up" for removing local copies, and British spelling. Identifiers
 * keep "device" (Device ID, device key). The tiers are "Demo" and "Licensed"
 * (readiness ADDENDUM-3), the product is lower-case "semper" (ADDENDUM-4), the
 * account verb is "sign in", and an account holder's helpers are "your
 * institution's IT" and "semper support" (AE3-107). The PDF report's literals
 * follow the same product name and "analysis" word (AE3-102, AE3-112), and so
 * does the FAQ the app links to (AE3-112).
 */
class UserTermsTest {

    /**
     * Every string in every `values/strings*.xml`, not only `strings.xml`: the
     * account-deletion strings live in their own file and once kept a capital
     * "Semper". Each plurals item is keyed as "name[quantity]".
     */
    private val strings: Map<String, String> by lazy {
        val dotAll = RegexOption.DOT_MATCHES_ALL
        val string = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", dotAll)
        val plurals = Regex("""<plurals name="([^"]+)"[^>]*>(.*?)</plurals>""", dotAll)
        val item = Regex("""<item quantity="([^"]+)"[^>]*>(.*?)</item>""", dotAll)
        // A "\n" or "\t" escape reads as a break, so "\nDevice:" still starts a word.
        val breaks = Regex("""\\[nt]""")
        val files = File("src/main/res/values").listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("strings") && it.extension == "xml" }
        check(files.size > 1) { "expected strings.xml and its sibling files, found $files" }
        files.flatMap { file ->
            val xml = file.readText()
            string.findAll(xml).map { it.groupValues[1] to it.groupValues[2] }.toList() +
                plurals.findAll(xml).flatMap { p ->
                    val name = p.groupValues[1]
                    item.findAll(p.groupValues[2]).map { "$name[${it.groupValues[1]}]" to it.groupValues[2] }
                }.toList()
        }.associate { (name, value) -> name to value.replace(breaks, " ") }
    }

    private fun offenders(pattern: String, except: (String, String) -> Boolean = { _, _ -> false }): List<String> {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        return strings.filter { (name, value) -> regex.containsMatchIn(value) && !except(name, value) }.keys.sorted()
    }

    /** Like [offenders], but the capital matters: "Demo" and "semper" are spelled one way. */
    private fun caseSensitiveOffenders(pattern: String): List<String> {
        val regex = Regex(pattern)
        return strings.filter { (_, value) -> regex.containsMatchIn(value) }.keys.sorted()
    }

    /** Every string literal in the PDF report's sources, as "File.kt: \"literal\"". */
    private val reportLiterals: List<String> by lazy {
        val literal = Regex("\"" + """(?:[^"\\\n]|\\.)*""" + "\"")
        File("src/main/java/com/sempermechanics/semper/report").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.name }
            .flatMap { file -> literal.findAll(file.readText()).map { "${file.name}: ${it.value}" }.toList() }
    }

    @Test
    fun `the cloud copy is a backup, not a sync`() {
        val iFrames = { _: String, value: String -> "I-frame" in value }
        assertEquals(emptyList<String>(), offenders("""\bsync(ed|ing)?\b""", iFrames) + offenders("save to cloud"))
    }

    @Test
    fun `the handset is a phone`() {
        // Identifiers keep "device": a Device ID, a device key. One string names the
        // signing key by where it was used, "Signed on another device", and is exempt
        // by name (readiness AE3-002, the Admin's "keep device for the key"); its
        // warning names the key itself ("a device key that is not this phone's").
        val signedElsewhere = { name: String, _: String -> name == "record_integrity_signed_elsewhere" }
        assertEquals(emptyList<String>(), offenders("""\bdevice\b(?!\s+(key|ID)\b)""", signedElsewhere))
        // More than one handset is always phones: no key or identifier is plural.
        assertEquals(emptyList<String>(), offenders("""\bdevices\b"""))
    }

    @Test
    fun `a saved run is an analysis`() {
        assertEquals(emptyList<String>(), offenders("exporting session"))
    }

    @Test
    fun `the free tier is Demo with a capital D`() {
        // Readiness ADDENDUM-3: "on Demo", "the Demo limit", "Demo allows". Never
        // "the demo", "on the demo", "the demo limit" or "demo mode".
        assertEquals(
            emptyList<String>(),
            caseSensitiveOffenders("""\bdemo\b""") + offenders("""\b(on|to) the demo\b""") + offenders("demo mode"),
        )
        assertEquals("Still on Demo", strings.getValue("license_refresh_demo"))
    }

    @Test
    fun `the paid tier is Licensed`() {
        // Readiness ADDENDUM-3. The manual quotes these lines character for character.
        assertEquals("Licensed (institution seat)", strings.getValue("license_refresh_institution"))
        assertEquals("Licensed (semper licence)", strings.getValue("license_refresh_individual"))
        assertEquals("""Couldn\'t reach semper — will retry""", strings.getValue("license_refresh_unreachable"))
        // No other plan name, and the tier word keeps its capital.
        val otherNames = offenders("""\b(professional|premium|pro|paid) (plan|tier)\b""")
        assertEquals(emptyList<String>(), otherNames + caseSensitiveOffenders("""\blicensed\b"""))
    }

    @Test
    fun `the product name is lower-case semper`() {
        // Readiness ADDENDUM-4: lower-case even at the start of a sentence. The
        // company, Semper Mechanics, keeps its capital; the sister app is written
        // "semper Classroom", as the console writes it. In this repository the app
        // is that sister app, so its own name is "semper Classroom" (AppNameTest).
        assertEquals("semper Classroom", strings.getValue("app_name"))
        assertEquals(emptyList<String>(), caseSensitiveOffenders("""\bSemper\b(?! Mechanics\b)"""))
        assertTrue(strings.getValue("delete_account_body").contains("semper Classroom"))
        assertTrue(strings.getValue("delete_account_phone_not_cleared").contains("Clear semper\\'s storage"))
    }

    @Test
    fun `the account verb is sign in, not log in`() {
        assertEquals(emptyList<String>(), offenders("""\blog[ -]?(in|out)\b"""))
    }

    @Test
    fun `account holders are sent only to their institution IT and semper support`() {
        // Readiness AE3-107: not "an admin", "your IT contact" or "your semper contact".
        assertEquals(emptyList<String>(), offenders("""\ban admin\b|\bIT contact\b|\bsemper contact\b"""))
    }

    @Test
    fun `the PDF report says semper and analysis`() {
        // The report's text is Kotlin literals, not strings.xml (readiness AE3-102,
        // AE3-112, AE3-116): no capital product name, no "Session", no "Analyzed".
        val product = Regex("""\bSemper\b(?! Mechanics\b)""")
        val terms = Regex("""\bsessions?\b|analyz""", RegexOption.IGNORE_CASE)
        assertTrue(reportLiterals.toString(), reportLiterals.isNotEmpty())
        val offending = reportLiterals.filter { product.containsMatchIn(it) || terms.containsMatchIn(it) }
        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun `removing local copies is freeing them up, not releasing them`() {
        // Staff releasing a phone from an account is the seat/binding sense, not local copies.
        val phoneBinding = { name: String, _: String -> name == "auth_phone_released" }
        assertEquals(emptyList<String>(), offenders("""\breleased\b|removed from this phone""", phoneBinding))
    }

    @Test
    fun `the after-expiry clock is called grace`() {
        assertTrue(strings.getValue("license_grace_until_fmt").contains("after-expiry grace"))
        assertTrue(strings.getValue("license_grace").contains("after-expiry grace"))
    }

    @Test
    fun `the beta notice states the same data use as the Terms box and Settings`() {
        // It said analysis data "will be collected" while the Terms box and
        // Settings call improvement use optional, untickable and withdrawable
        // (readiness AE1V-016).
        val notice = strings.getValue("beta_notice_body")
        assertTrue(notice, "will be collected" !in notice)
        assertTrue(notice, "untick" in notice)
        assertTrue(notice, "Settings \\u2192 Your data" in notice)
        assertTrue(notice, "within 30 days" in notice)
        assertTrue("within 30 days" in strings.getValue("settings_improve_summary"))
        assertTrue(notice, "images, results and reports you back up" in notice)
        assertTrue("images, results and reports you back up" in strings.getValue("terms_checkbox_improve_hint"))
    }

    /**
     * The FAQ the app opens (the engine-start "Why?" link, `docs/app/FAQ.md`)
     * is user-facing text too, so it keeps the same words (readiness AE3-112):
     * phone, analysis, lower-case semper, Demo and Licensed, sign in. Each
     * offending line is reported as "FAQ.md:<line>: <text>".
     */
    @Test
    fun `the FAQ the app links to uses the same words`() {
        val faq = File("../docs/app/FAQ.md").readLines()
        assertTrue("FAQ.md not found or empty", faq.isNotEmpty())
        val ignoreCase = setOf(RegexOption.IGNORE_CASE)
        val rules = listOf(
            // Identifiers keep "device", as in the app's strings: a Device ID, a device key.
            Regex("""\bdevice\b(?!\s+(key|ID)\b)|\bdevices\b""", ignoreCase),
            Regex("""\bsessions?\b""", ignoreCase),
            Regex("""\bSemper\b(?! Mechanics\b)"""),
            Regex("""\bdemo\b|\blicensed\b"""),
            Regex("""\b(on|to) the demo\b|\bdemo mode\b""", ignoreCase),
            Regex("""\blog[ -]?(in|out)\b""", ignoreCase),
        )
        val offending = faq.withIndex()
            .filter { (_, line) -> rules.any { it.containsMatchIn(line) } }
            .map { (i, line) -> "FAQ.md:${i + 1}: ${line.trim()}" }
        assertEquals(emptyList<String>(), offending)
    }

    @Test
    fun `spelling is British`() {
        assertEquals(emptyList<String>(), offenders("""organiz|initializ"""))
    }
}
