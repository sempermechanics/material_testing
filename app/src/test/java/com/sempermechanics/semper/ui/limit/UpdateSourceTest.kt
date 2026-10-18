package com.sempermechanics.semper.ui.limit

import org.junit.Assert.assertEquals
import org.junit.Test

/** TD-268: a Play install updates from Play; anything else from the release page. */
class UpdateSourceTest {

    private val id = "com.sempermechanics.semper"

    @Test
    fun `a build Play installed opens its listing, then the listing's web page`() {
        assertEquals(
            listOf("market://details?id=$id", "https://play.google.com/store/apps/details?id=$id"),
            UpdateSource.urls(id, "com.android.vending"),
        )
    }

    @Test
    fun `a sideloaded build opens the release download page`() {
        val releases = listOf("https://github.com/sempermechanics/semperdic-app/releases")
        assertEquals("the package installer", releases, UpdateSource.urls(id, "com.google.android.packageinstaller"))
        assertEquals("adb, or the platform does not say", releases, UpdateSource.urls(id, null))
        assertEquals("another store", releases, UpdateSource.urls(id, "com.sec.android.app.samsungapps"))
    }
}
