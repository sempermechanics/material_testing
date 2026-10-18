package com.sempermechanics.semper.ui.account

import android.annotation.SuppressLint
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.fixtures.CleanAppState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Redeeming a key ([LicenseActivation]): a success stores the account's config
 * and fetches `/v1/config` for the entitlement token, as a seat checkout does;
 * a refusal stores nothing and says why; and the entry points offer it only
 * on demo.
 */
@RunWith(RobolectricTestRunner::class)
class LicenseActivationTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeCloudApi()
    private val tokens = FakeTokens()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @SuppressLint("RestrictedApi")
    @After
    fun tearDown() {
        WorkManagerTestInitHelper.closeWorkDatabase()
        WorkManagerImpl.setDelegate(null)
        AppRemoteConfig.clear(context)
    }

    private val licensed = AppConfigDto(mode = "licensed", maxSessions = 50, cloudBackupEnabled = true)

    @Test
    fun `a redeemed key stores the account's config and fetches config for the token`() = runTest {
        var sent = ""
        api.onActivateLicense = { _, key ->
            sent = key
            licensed
        }
        api.onGetConfig = { licensed }

        val outcome = LicenseActivation.activate(context, KEY, api, tokens)

        assertEquals(ActivationOutcome.Activated, outcome)
        assertEquals(KEY, sent)
        assertEquals(listOf("activateLicense", "getConfig"), api.calls)
        assertTrue(LicenseEntitlements.serverSaysLicensed(context))
        assertFalse("no longer offered once licensed", LicenseActivation.isOffered(context))
    }

    @Test
    fun `a failed config fetch after a redeemed key is still an activation`() = runTest {
        api.onActivateLicense = { _, _ -> licensed }
        api.onGetConfig = { throw IOException("dropped") }

        assertEquals(ActivationOutcome.Activated, LicenseActivation.activate(context, KEY, api, tokens))
        assertTrue(LicenseEntitlements.serverSaysLicensed(context))
    }

    @Test
    fun `a refusal is named, stores nothing and asks nothing more`() = runTest {
        api.onActivateLicense = { _, _ ->
            throw ApiException(409, """{"detail":"license_already_redeemed"}""", "req-1")
        }

        val outcome = LicenseActivation.activate(context, KEY, api, tokens)

        assertEquals(ActivationOutcome.Refused(ActivationError.ALREADY_REDEEMED), outcome)
        assertEquals(listOf("activateLicense"), api.calls)
        assertFalse(LicenseEntitlements.serverSaysLicensed(context))
    }

    @Test
    fun `an unknown key and a device bound elsewhere are told apart`() = runTest {
        api.onActivateLicense = { _, _ -> throw ApiException(404, """{"detail":"license_not_found"}""") }
        assertEquals(
            ActivationOutcome.Refused(ActivationError.INVALID_KEY),
            LicenseActivation.activate(context, KEY, api, tokens),
        )

        api.onActivateLicense = { _, _ -> throw ApiException(409, """{"detail":"device_in_use"}""") }
        assertEquals(
            ActivationOutcome.Refused(ActivationError.DEVICE_IN_USE),
            LicenseActivation.activate(context, KEY, api, tokens),
        )
    }

    @Test
    fun `no answer is the network`() = runTest {
        api.onActivateLicense = { _, _ -> throw IOException("unreachable") }

        assertEquals(
            ActivationOutcome.Refused(ActivationError.NETWORK),
            LicenseActivation.activate(context, KEY, api, tokens),
        )
    }

    @Test
    fun `signed out, or no backend, asks nothing and says network`() = runTest {
        tokens.token = null
        assertEquals(
            ActivationOutcome.Refused(ActivationError.NETWORK),
            LicenseActivation.activate(context, KEY, api, tokens),
        )

        val off = FakeCloudApi(enabled = false)
        assertEquals(
            ActivationOutcome.Refused(ActivationError.NETWORK),
            LicenseActivation.activate(context, KEY, off, FakeTokens()),
        )
        assertTrue(api.calls.isEmpty())
        assertTrue(off.calls.isEmpty())
    }

    @Test
    fun `activation is offered on demo only, and not to a member waiting for a floating seat`() {
        assertTrue(LicenseActivation.isOffered(serverSaysLicensed = false, needsSeat = false))
        assertFalse(LicenseActivation.isOffered(serverSaysLicensed = true, needsSeat = false))
        assertFalse(LicenseActivation.isOffered(serverSaysLicensed = false, needsSeat = true))
        assertFalse(LicenseActivation.isOffered(serverSaysLicensed = true, needsSeat = true))

        // A fresh account, with no config yet, is demo.
        assertTrue(LicenseActivation.isOffered(context))
    }

    private companion object {
        const val KEY = "SEMP-U8BX-4KQ2-ZP7M-W3RT"
    }
}
