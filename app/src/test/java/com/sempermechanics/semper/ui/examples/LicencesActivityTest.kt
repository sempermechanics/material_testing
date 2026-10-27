package com.sempermechanics.semper.ui.examples

import android.app.Application
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Credits & licences (ADR-031): every set's credit, and the licence texts in full. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class LicencesActivityTest {

    @Test
    fun `every credit and every licence text is on the page`() {
        Robolectric.buildActivity(LicencesActivity::class.java).setup().use { controller ->
            val text = controller.get().findViewById<TextView>(R.id.tvLicences)
            idleUntil("the page is built") { text.text.isNotEmpty() }
            val page = text.text.toString()
            listOf(
                "Sanjeev Koirala",
                "doi:10.5281/zenodo.1172068",
                "doi:10.17632/z3yc9z84tk.3",
                "National Technology & Engineering Solutions of Sandia",
                // The BSD-3 clause DICe's binary redistribution asks to be reproduced.
                "Redistributions in binary form must reproduce the above copyright",
                "Apache License",
                "Mozilla Public License Version 2.0",
            ).forEach { assertTrue(it, it in page) }
            assertFalse("DICe's comment slashes are gone", "// " in page)
        }
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
            LicencesActivity.reflow(licence),
        )
    }

    @Test
    fun `a licence that is not a comment keeps its slashes and drops its rules`() {
        val licence = "Mozilla Public License Version 2.0\n==================================\n\n" +
            "1.1. \"Contributor\"\n    means each individual // or entity\n    that contributes."
        assertEquals(
            "Mozilla Public License Version 2.0\n\n" +
                "1.1. \"Contributor\" means each individual // or entity that contributes.",
            LicencesActivity.reflow(licence),
        )
    }
}
