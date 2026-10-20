package com.sempermechanics.semper.data.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `/v1/config`'s `testAccount` (ADR-027), as [AppRemoteConfig] keeps it: a
 * QC account with its end, a CI one without, none on a real account, and
 * only `/v1/config` itself may clear it.
 */
@RunWith(RobolectricTestRunner::class)
class AppRemoteConfigTestAccountTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clear() = AppRemoteConfig.clear(context)

    private fun config(testAccount: TestAccountDto?) =
        AppConfigDto(mode = "licensed", maxSessions = 999, testAccount = testAccount)

    @Test
    fun `a QC account is kept with its end, read to the second`() {
        AppRemoteConfig.apply(
            context,
            config(TestAccountDto(purpose = "qc", expiresAt = "2026-11-18T10:00:00.654321+00:00")),
            authoritative = true,
        )
        val account = AppRemoteConfig.testAccount(context)!!
        assertEquals(AppRemoteConfig.TestAccount.PURPOSE_QC, account.purpose)
        assertEquals(1_794_996_000_000L, account.expiresAtMs)
        assertFalse(account.isCi)
    }

    @Test
    fun `a CI account without an end is kept with none`() {
        AppRemoteConfig.apply(context, config(TestAccountDto(purpose = "ci", expiresAt = null)), authoritative = true)
        val account = AppRemoteConfig.testAccount(context)!!
        assertTrue(account.isCi)
        assertEquals(AppRemoteConfig.NO_INSTANT, account.expiresAtMs)
    }

    @Test
    fun `a real account, a blank purpose and no answer yet are no test account`() {
        assertNull(AppRemoteConfig.testAccount(context))
        AppRemoteConfig.apply(context, config(null), authoritative = true)
        assertNull(AppRemoteConfig.testAccount(context))
        AppRemoteConfig.apply(context, config(TestAccountDto(purpose = " ")), authoritative = true)
        assertNull(AppRemoteConfig.testAccount(context))
    }

    @Test
    fun `only the config answer clears it, and sign-out does`() {
        AppRemoteConfig.apply(context, config(TestAccountDto(purpose = "ci")), authoritative = true)
        // A seat checkout's nested config does not carry the field.
        AppRemoteConfig.apply(context, config(null), authoritative = false)
        assertEquals("ci", AppRemoteConfig.testAccount(context)?.purpose)

        AppRemoteConfig.apply(context, config(null), authoritative = true)
        assertNull(AppRemoteConfig.testAccount(context))

        AppRemoteConfig.apply(context, config(TestAccountDto(purpose = "qc")), authoritative = true)
        AppRemoteConfig.clear(context)
        assertNull(AppRemoteConfig.testAccount(context))
    }
}
