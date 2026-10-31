package com.sempermechanics.semper.ui.examples

import android.app.Application
import android.content.Intent
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Credits & licences (ADR-031, TD-57): every part of the app with its
 * licence, the full texts on the website, and the credits and short notices
 * that have to travel with the app on pages of their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LicencesActivityTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val items by lazy { LicencePage.items(app, app.assets) }
    private val rows by lazy { items.filterIsInstance<LicenceItem.Row>() }
    private fun row(title: String) = rows.single { it.title == title }

    private fun pageText(target: Target?): String {
        val key = (target as Target.Page).key
        val (title, paragraphs) = requireNotNull(LicencePage.page(app, app.assets, key)) { key }
        return (listOf(title) + paragraphs).joinToString("\n")
    }

    private val libraries by lazy {
        val raw = app.resources.openRawResource(R.raw.aboutlibraries).use { it.readBytes().decodeToString() }
        LicencePage.parseLibraries(raw)
    }

    @Test
    fun `the list opens with the link to the full texts, then the sections`() {
        Robolectric.buildActivity(LicencesActivity::class.java).setup().use { controller ->
            val list = controller.get().findViewById<RecyclerView>(R.id.rvLicences)
            idleUntil("the list is built") { (list.adapter?.itemCount ?: 0) > 0 }
            list.measure(0, 0)
            list.layout(0, 0, 1080, 2400)
            val link = list.getChildAt(1)
            assertEquals("Full licence texts", link.findViewById<TextView>(R.id.tvLicenceRowTitle).text.toString())
            link.performClick()
            val opened = shadowOf(controller.get()).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, opened.action)
            assertEquals("https://sempermechanics.com/licences/", opened.dataString)
        }
        assertEquals(
            listOf("Example data", "Analysis engine", "Libraries in the app", "Google Play services and Firebase"),
            items.filterIsInstance<LicenceItem.Heading>().map { it.text },
        )
    }

    @Test
    fun `each example set's row opens its credit, and a DICe set's carries the BSD text`() {
        assertEquals(4, rows.count { it.target is Target.Page && "credit:" in (it.target as Target.Page).key })
        val pmma = pageText(row("PMMA beam in 3-point bending").target)
        assertTrue(pmma, "doi:10.5281/zenodo.1172068" in pmma)
        val dice = pageText(row("Carbon-fibre plate with a hole").target)
        assertTrue(dice, "National Technology & Engineering Solutions of Sandia" in dice)
        // The BSD-3 clause DICe's binary redistribution asks to be reproduced, without its comment slashes.
        assertTrue(dice, "Redistributions in binary form must reproduce the above copyright" in dice && "// " !in dice)
    }

    @Test
    fun `the engine's parts show their licence, and the short notices open in the app`() {
        assertEquals("Apache-2.0", row("OpenCV").subtitle)
        assertEquals("MPL-2.0", row("Eigen").subtitle)
        assertNull("OpenCV's text is on the website", row("OpenCV").target)
        mapOf(
            "zlib" to "Jean-loup Gailly and Mark Adler",
            "libpng" to "PNG Reference Library License version 2",
            "libjpeg-turbo" to "Independent JPEG Group",
            "libtiff" to "Sam Leffler",
            "libwebp" to "Copyright (c) 2010, Google Inc.",
            "carotene" to "NVIDIA Corporation",
        ).forEach { (part, words) -> assertTrue(part, words in pageText(row(part).target)) }
    }

    @Test
    fun `every library in the APK has a row with its licence`() {
        val (libs, _) = libraries
        assertTrue(libs.size.toString(), libs.size > 50)
        val okhttp = row("okhttp")
        assertTrue(okhttp.subtitle, okhttp.subtitle.endsWith("· Apache-2.0"))
        assertNull("Apache's text is on the website", okhttp.target)
        // checker-qual (MIT) left the APK with guava 33.7.2-android, which semperdic-app's
        // readiness TE5-105 pins over play-services-measurement's 31.1 (app/build.gradle.kts):
        // guava 33 annotates with jspecify (Apache-2.0). Its notice stays in assets/licences
        // for the day it comes back; protobuf's BSD notice is the short one shipping now.
        assertTrue(rows.none { it.title == "Checker Qual" })
        val protobuf = pageText(row("Preferences External Protobuf").target)
        assertTrue(protobuf, "Code generated by the Protocol Buffer compiler" in protobuf)
    }

    @Test
    fun `a library under a licence with no notice in the app fails here`() {
        // Apache-2.0 and the Google terms are on the website. A new licence, or a new MIT / BSD
        // library, needs its notice in assets/licences and NOTICE_BY_LIBRARY before this passes.
        val (libs, licences) = libraries
        val onTheWebsite = setOf(
            "Apache-2.0",
            "Android Software Development Kit License",
            "Play Core Software Development Kit Terms of Service",
            "Play Integrity API Terms of Service",
        )
        val notices = app.assets.list(LicencePage.LICENCES_DIR).orEmpty().toSet()
        libs.forEach { lib ->
            lib.licences.forEach { id ->
                val name = licences[id]?.name ?: id
                if (id !in onTheWebsite && name !in onTheWebsite) {
                    assertTrue("${lib.id} is under $name", LicencePage.NOTICE_BY_LIBRARY[lib.id] in notices)
                }
            }
        }
    }

    @Test
    fun `the Play services notices are a link to the website`() {
        val link = rows.last().target
        assertEquals(Target.Link("https://sempermechanics.com/licences/#play-services"), link)
    }

    @Test
    fun `a page opens on its own and an unknown one closes`() {
        val key = (row("zlib").target as Target.Page).key
        val intent = LicencesActivity.intent(app).putExtra(LicencesActivity.EXTRA_PAGE, key)
        Robolectric.buildActivity(LicencesActivity::class.java, intent).setup().use { controller ->
            val list = controller.get().findViewById<RecyclerView>(R.id.rvLicences)
            idleUntil("the page is built") { (list.adapter?.itemCount ?: 0) > 0 }
            list.measure(0, 0)
            list.layout(0, 0, 1080, 2400)
            assertEquals("zlib", (list.getChildAt(0) as TextView).text.toString())
        }
        assertNotNull(LicencePage.page(app, app.assets, key))
        assertNull(LicencePage.page(app, app.assets, "notice:../examples/steel_tensile/example.json"))
        assertNull(LicencePage.page(app, app.assets, "credit:no_such_set"))
    }

    @Test
    fun `a licence's paragraphs reflow to the screen and keep their words`() {
        val licence = """
            // ****************************
            //
            // Redistribution and use in source and binary forms, with or without
            // modification, are permitted.
            //
            // 1. Redistributions of source code must retain the above copyright
            // notice.
            // ****************************
        """.trimIndent()
        assertEquals(
            "Redistribution and use in source and binary forms, with or without modification, are permitted." +
                "\n\n1. Redistributions of source code must retain the above copyright notice.",
            LicencePage.reflow(licence),
        )
    }

    @Test
    fun `a licence that is not a comment keeps its slashes and drops its rules`() {
        val licence = "Mozilla Public License Version 2.0\n==================================\n\n" +
            "1.1. \"Contributor\"\n    means each individual // or entity\n    that contributes."
        assertEquals(
            "Mozilla Public License Version 2.0\n\n" +
                "1.1. \"Contributor\" means each individual // or entity that contributes.",
            LicencePage.reflow(licence),
        )
    }
}
