package com.sempermechanics.semper.data.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `/v1/config`'s `activation` and `pendingLicence` blocks as [AppRemoteConfig]
 * keeps them (ADR-033): the config's answer is the word, and a lease answer,
 * which carries neither, leaves them as they were.
 */
@RunWith(RobolectricTestRunner::class)
class AppRemoteConfigActivationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clear() = AppRemoteConfig.clear(context)

    private val active = ActivationDto(
        ActivationDto.STATE_ACTIVE,
        devicesActive = 2,
        maxDevices = 3,
        pool = true,
        model = "activation",
    )

    @Test
    fun `before any answer this phone has no licence and nothing waits`() {
        assertEquals(AppRemoteConfig.Activation.NONE, AppRemoteConfig.activation(context))
        assertNull(AppRemoteConfig.pendingLicence(context))
    }

    @Test
    fun `the config's blocks are stored as sent`() {
        val expires = "2027-01-31T00:00:00+00:00"
        AppRemoteConfig.apply(
            context,
            AppConfigDto(
                activation = active,
                pendingLicence = PendingLicenceDto("new-1", "SEM-AB", expiresAt = expires),
            ),
            authoritative = true,
        )
        assertEquals(
            AppRemoteConfig.Activation("active", 2, 3, pool = true, model = "activation"),
            AppRemoteConfig.activation(context),
        )
        val pending = AppRemoteConfig.pendingLicence(context)
        assertEquals("new-1", pending?.licenseId)
        assertEquals("SEM-AB", pending?.prefix)
        assertEquals(1_801_353_600_000L, pending?.expiresAtMs)
    }

    @Test
    fun `a lease answer keeps them, a config answer without them clears them`() {
        val both = AppConfigDto(activation = active, pendingLicence = PendingLicenceDto("new-1"))
        AppRemoteConfig.apply(context, both, authoritative = true)

        AppRemoteConfig.apply(context, AppConfigDto(mode = "licensed"))
        assertEquals("active", AppRemoteConfig.activation(context).state)
        assertEquals("new-1", AppRemoteConfig.pendingLicence(context)?.licenseId)

        AppRemoteConfig.apply(context, AppConfigDto(mode = "demo"), authoritative = true)
        assertEquals(AppRemoteConfig.Activation.NONE, AppRemoteConfig.activation(context))
        assertNull(AppRemoteConfig.pendingLicence(context))
    }

    @Test
    fun `a hidden card is kept across answers and dropped at sign-out`() {
        AppRemoteConfig.hideActivationCard(context, "activate:2")
        AppRemoteConfig.apply(context, AppConfigDto(activation = active), authoritative = true)
        assertEquals("activate:2", AppRemoteConfig.activationCardHidden(context))
        AppRemoteConfig.clear(context)
        assertEquals("", AppRemoteConfig.activationCardHidden(context))
    }
}
