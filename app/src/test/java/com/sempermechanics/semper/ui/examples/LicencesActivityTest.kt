package com.sempermechanics.semper.ui.examples

import android.app.Application
import android.widget.TextView
import com.sempermechanics.semper.R
import com.sempermechanics.semper.fixtures.idleUntil
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
        }
    }
}
