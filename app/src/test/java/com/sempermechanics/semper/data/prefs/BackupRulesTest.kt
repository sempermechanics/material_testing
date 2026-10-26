package com.sempermechanics.semper.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Analysis content stays out of Auto Backup and device transfer (readiness TE5-006):
 * both rule files exclude the same file paths, and the wizard draft (reference image,
 * ROI mask, frame list) is one of them.
 */
class BackupRulesTest {

    private fun fileExcludes(parent: Element): Set<String> {
        val nodes = parent.getElementsByTagName("exclude")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    private fun root(name: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/xml/$name")).documentElement

    private val expected = setOf("sessions", WizardDraft.DIR_NAME)

    @Test
    fun `api 30 auto backup excludes sessions and the wizard draft`() {
        assertEquals(expected, fileExcludes(root("backup_rules.xml")))
    }

    @Test
    fun `api 31 cloud backup and device transfer exclude sessions and the wizard draft`() {
        val rules = root("data_extraction_rules.xml")
        for (section in listOf("cloud-backup", "device-transfer")) {
            val element = rules.getElementsByTagName(section).item(0) as Element
            assertEquals(section, expected, fileExcludes(element))
        }
    }
}
