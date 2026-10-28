package com.sempermechanics.semper.data.prefs

import android.app.Application
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first screens' preference files were first opened, and so loaded from
 * disk, on the main thread: the beta-notice and delete-feedback flags on Home,
 * the coach marks and the parameter clipboard in the wizard, the device id in
 * Settings, and the account file the beta-notice check keys on (readiness
 * TE1-104; the account file was still read on main at re-verify, 32 ms). SemperApp now opens them at start on IO
 * ([PrefFiles.warm]), so a screen finds them loaded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PrefWarmupTest {

    @Test
    fun `the warm-up opens every file the first screens read`() {
        val opened = mutableListOf<String>()
        val app = ApplicationProvider.getApplicationContext<Application>()
        val counting = object : ContextWrapper(app) {
            override fun getApplicationContext() = this

            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                opened += name
                return super.getSharedPreferences(name, mode)
            }
        }

        PrefFiles.warm(counting)

        assertEquals(
            setOf(
                PrefFiles.Onboarding.NAME,
                PrefFiles.SessionDeletesPrefs.NAME,
                PrefFiles.Coach.NAME,
                PrefFiles.Clipboard.NAME,
                PrefFiles.Device.NAME,
                // Home's beta-notice check reads the account file first (AccountCache.betaAckKey).
                PrefFiles.Session.NAME,
            ),
            opened.toSet(),
        )
        // dic_settings stays with AppSettings.migrate, which must read it first.
        assertTrue(PrefFiles.Settings.NAME !in opened)
    }
}
