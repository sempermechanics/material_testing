package com.sempermechanics.semper.data.account.phones

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.cloud.FakePhonesApi
import com.sempermechanics.semper.cloud.FakeTokens
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AccountPhoneDto
import com.sempermechanics.semper.data.net.AccountPhonesDto
import com.sempermechanics.semper.data.net.ActivationDto
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.DeviceActivateResponse
import com.sempermechanics.semper.data.net.DeviceActivationDto
import com.sempermechanics.semper.data.net.DeviceDeactivateResponse
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.PendingLicenceDto
import com.sempermechanics.semper.fixtures.CleanAppState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Activate, list and Deactivate ([DeviceActivation], ADR-033): what each
 * answer comes to, and that Activate's config is stored at once, so the
 * licence's features open without a second call.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceActivationTest {

    @get:Rule
    val clean = CleanAppState()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakePhonesApi()
    private val tokens = FakeTokens()

    @After
    fun tearDown() = AppRemoteConfig.clear(context)

    private val demo = AppConfigDto(
        mode = "demo",
        maxSessions = 25,
        activation = ActivationDto(state = ActivationDto.STATE_AVAILABLE, devicesActive = 1, maxDevices = 2),
    )
    private val licensed = AppConfigDto(
        mode = "licensed",
        maxSessions = 500,
        cloudBackupEnabled = true,
        shareEnabled = true,
        activation = ActivationDto(state = ActivationDto.STATE_ACTIVE, devicesActive = 2, maxDevices = 2),
    )

    private fun refusal(code: Int, detail: String) = ApiException(code, """{"detail":"$detail"}""")

    @Test
    fun `activate stores the config it answers, so the licence opens at once`() = runBlocking {
        AppRemoteConfig.apply(context, demo, authoritative = true)
        assertFalse(LicenseEntitlements.isLicensed(context))
        api.onActivate = { _, _ ->
            DeviceActivateResponse(DeviceActivationDto(devicesActive = 2, maxDevices = 2), licensed)
        }

        val outcome = DeviceActivation.activate(context, api = api, tokens = tokens)

        assertEquals(ActivateOutcome.Activated(already = false, devicesActive = 2, maxDevices = 2), outcome)
        assertEquals(listOf("activateDevice"), api.calls)
        assertTrue(LicenseEntitlements.isLicensed(context))
        assertTrue(LicenseEntitlements.cloudBackupEnabled(context))
        assertTrue(AppRemoteConfig.activation(context).isActive)
    }

    @Test
    fun `activate with upgrade asks for the new licence and clears the one waiting`() = runBlocking {
        val waiting = demo.copy(pendingLicence = PendingLicenceDto("new-1", "SEM-AB"))
        AppRemoteConfig.apply(context, waiting, authoritative = true)
        assertEquals("SEM-AB", AppRemoteConfig.pendingLicence(context)?.prefix)
        api.onActivate = { _, upgrade ->
            assertTrue(upgrade)
            DeviceActivateResponse(DeviceActivationDto(already = true, devicesActive = 1, maxDevices = 3), licensed)
        }

        val outcome = DeviceActivation.activate(context, upgrade = true, api = api, tokens = tokens)

        assertEquals(ActivateOutcome.Activated(already = true, devicesActive = 1, maxDevices = 3), outcome)
        assertNull(AppRemoteConfig.pendingLicence(context))
    }

    @Test
    fun `an answer without its activation reads the count from the config`() = runBlocking {
        api.onActivate = { _, _ -> DeviceActivateResponse(activation = null, config = licensed) }
        assertEquals(
            ActivateOutcome.Activated(already = false, devicesActive = 2, maxDevices = 2),
            DeviceActivation.activate(context, api = api, tokens = tokens),
        )
    }

    @Test
    fun `each refusal is its own outcome, and nothing is stored`() = runBlocking {
        AppRemoteConfig.apply(context, demo, authoritative = true)
        val cases = listOf(
            refusal(409, "device_limit_reached: 2/2") to ActivateOutcome.LimitReached(2, 2),
            refusal(404, "no_license") to ActivateOutcome.NoLicence,
            refusal(409, "seating_floating") to ActivateOutcome.Floating,
            refusal(404, "no_pending_upgrade") to ActivateOutcome.NoPendingUpgrade,
        )
        for ((thrown, expected) in cases) {
            api.onActivate = { _, _ -> throw thrown }
            assertEquals(expected, DeviceActivation.activate(context, api = api, tokens = tokens))
        }
        assertFalse(LicenseEntitlements.isLicensed(context))
    }

    @Test
    fun `no connection, no token or no backend is offline, any other failure refused`() = runBlocking {
        api.onActivate = { _, _ -> throw IOException("no route") }
        assertEquals(ActivateOutcome.Offline, DeviceActivation.activate(context, api = api, tokens = tokens))

        assertEquals(ActivateOutcome.Offline, DeviceActivation.activate(context, api = api, tokens = FakeTokens(null)))
        assertEquals(
            ActivateOutcome.Offline,
            DeviceActivation.activate(context, api = FakePhonesApi(enabled = false), tokens = tokens),
        )

        api.onActivate = { _, _ -> throw refusal(500, "boom") }
        val refused = DeviceActivation.activate(context, api = api, tokens = tokens)
        assertEquals(HttpFailure.Kind.SERVER, (refused as ActivateOutcome.Refused).failure.kind)
    }

    @Test
    fun `a limit with a missing or odd count still says full`() {
        assertEquals(ActivateOutcome.LimitReached(0, 0), DeviceActivation.limitReached("device_limit_reached"))
        assertEquals(ActivateOutcome.LimitReached(3, 0), DeviceActivation.limitReached("device_limit_reached: 3/x"))
        assertEquals(ActivateOutcome.LimitReached(1, 4), DeviceActivation.limitReached("device_limit_reached: 1 / 4"))
    }

    @Test
    fun `list answers the account's phones, or offline, or why not`() = runBlocking {
        val phones = AccountPhonesDto(
            licenseId = "lic-1",
            devicesActive = 1,
            maxDevices = 2,
            model = "activation",
            devices = listOf(AccountPhoneDto("and-1", "Pixel 6", activated = true, thisDevice = true)),
        )
        api.onList = { phones }
        assertEquals(PhonesOutcome.Listed(phones), DeviceActivation.list(context, api, tokens))

        api.onList = { throw IOException("no route") }
        assertEquals(PhonesOutcome.Offline, DeviceActivation.list(context, api, tokens))
        assertEquals(PhonesOutcome.Offline, DeviceActivation.list(context, api, FakeTokens(null)))

        api.onList = { throw refusal(403, "not_approved") }
        assertTrue(DeviceActivation.list(context, api, tokens) is PhonesOutcome.Refused)
    }

    @Test
    fun `deactivate answers the licence's count after it`() = runBlocking {
        api.onDeactivate = { _, id -> DeviceDeactivateResponse(id, devicesActive = 1, maxDevices = 2) }

        val outcome = DeviceActivation.deactivate(context, "and-1", api, tokens)

        assertEquals(DeactivateOutcome.Deactivated(1, 2), outcome)
        assertEquals(listOf("deactivateDevice(and-1)"), api.calls)
    }

    @Test
    fun `a phone already gone is not found, and a failure is offline or refused`() = runBlocking {
        api.onDeactivate = { _, _ -> throw refusal(404, "activation_not_found") }
        assertEquals(DeactivateOutcome.NotFound, DeviceActivation.deactivate(context, "x", api, tokens))

        api.onDeactivate = { _, _ -> throw IOException("no route") }
        assertEquals(DeactivateOutcome.Offline, DeviceActivation.deactivate(context, "x", api, tokens))
        assertEquals(DeactivateOutcome.Offline, DeviceActivation.deactivate(context, "x", api, FakeTokens(null)))

        api.onDeactivate = { _, _ -> throw refusal(401, "bad_signature") }
        assertTrue(DeviceActivation.deactivate(context, "x", api, tokens) is DeactivateOutcome.Refused)
    }
}
