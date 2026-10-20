package com.sempermechanics.semper.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.TestAccountDto
import com.sempermechanics.semper.data.prefs.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [Diagnostics.applyAccountType]: the `account_type` user property and
 * Crashlytics key a test account is tagged by, set only with the user's
 * consent and cleared without it, for a real account and at sign-out.
 */
@RunWith(RobolectricTestRunner::class)
class AccountTypeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Every value handed to the sink, in order; null is a clear. */
    private val tagged = mutableListOf<String?>()

    @Before
    fun setUp() {
        AppSettings.setDiagnosticsEnabled(context, false)
        Diagnostics.accountTypeSink = Diagnostics.AccountTypeSink { _, type -> tagged += type }
    }

    @After
    fun tearDown() {
        Diagnostics.accountTypeSink = Diagnostics.AccountTypeSink { _, _ -> }
        AppSettings.setDiagnosticsEnabled(context, false)
        AppRemoteConfig.clear(context)
    }

    private fun configFor(purpose: String?) = AppRemoteConfig.apply(
        context,
        AppConfigDto(mode = "licensed", testAccount = purpose?.let { TestAccountDto(purpose = it) }),
        authoritative = true,
    )

    @Test
    fun `a test account is never tagged without consent`() {
        configFor("qc")
        Diagnostics.apply(context)
        assertEquals(listOf<String?>(null, null), tagged)
    }

    @Test
    fun `with consent a QC account is test and a CI account is ci`() {
        AppSettings.setDiagnosticsEnabled(context, true)
        configFor("qc")
        assertEquals(Diagnostics.ACCOUNT_TYPE_TEST, tagged.last())
        configFor("ci")
        assertEquals(Diagnostics.ACCOUNT_TYPE_CI, tagged.last())
    }

    @Test
    fun `a real account, a sign-out and a withdrawn consent clear it`() {
        AppSettings.setDiagnosticsEnabled(context, true)
        configFor("ci")
        configFor(null)
        assertNull(tagged.last())

        configFor("ci")
        AppRemoteConfig.clear(context)
        assertNull(tagged.last())

        configFor("qc")
        Diagnostics.setEnabled(context, false)
        assertNull(tagged.last())
        Diagnostics.setEnabled(context, true)
        assertEquals(Diagnostics.ACCOUNT_TYPE_TEST, tagged.last())
    }

    @Test
    fun `the type follows the purpose`() {
        assertNull(Diagnostics.accountType(null))
        assertEquals("ci", Diagnostics.accountType(AppRemoteConfig.TestAccount("ci", 0L)))
        assertEquals("test", Diagnostics.accountType(AppRemoteConfig.TestAccount("qc", 1L)))
        assertEquals("test", Diagnostics.accountType(AppRemoteConfig.TestAccount("other", 0L)))
        assertEquals("account_type", Diagnostics.ACCOUNT_TYPE)
    }
}
