package com.sempermechanics.semper.data.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.account.entitlement.EntitlementStore
import com.sempermechanics.semper.data.account.entitlement.TokenStatus
import com.sempermechanics.semper.data.policy.AppPolicy
import com.sempermechanics.semper.data.policy.KillSwitch
import com.sempermechanics.semper.data.policy.VersionVerdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The `/v1/config` fields beyond the licence: kill switches (A6.4), the
 * version gate (A6.1) and the entitlement token, as [AppRemoteConfig] stores
 * them and `LicenseEntitlements` / [AppPolicy] read them.
 */
@RunWith(RobolectricTestRunner::class)
class AppRemoteConfigPolicyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val licensed = AppConfigDto(
        maxSessions = 999,
        mode = LicenseEntitlements.MODE_LICENSED,
        cloudBackupEnabled = true,
        shareEnabled = true,
    )

    private val gated = licensed.copy(killSwitches = listOf("upload"), minSupportedVersionCode = 30)

    @After
    fun clear() = AppRemoteConfig.clear(context)

    @Test
    fun `the config route stores kill switches and versions`() {
        val config = licensed.copy(killSwitches = listOf("share", "Restore", "nonsense"), minSupportedVersionCode = 30)
        AppRemoteConfig.record(context, Result.success(config.copy(recommendedVersionCode = 40)))

        assertEquals(setOf(KillSwitch.SHARE, KillSwitch.RESTORE), AppRemoteConfig.killSwitches(context))
        assertEquals(30, AppRemoteConfig.minSupportedVersionCode(context))
        assertEquals(40, AppRemoteConfig.recommendedVersionCode(context))
    }

    @Test
    fun `a lease answer without them leaves them as they were`() {
        AppRemoteConfig.record(context, Result.success(gated))
        AppRemoteConfig.apply(context, licensed)

        assertEquals(setOf(KillSwitch.UPLOAD), AppRemoteConfig.killSwitches(context))
        assertEquals(30, AppRemoteConfig.minSupportedVersionCode(context))
    }

    @Test
    fun `a config answer without them clears them`() {
        AppRemoteConfig.record(context, Result.success(gated))
        AppRemoteConfig.record(context, Result.success(licensed))

        assertTrue(AppRemoteConfig.killSwitches(context).isEmpty())
        assertEquals(0, AppRemoteConfig.minSupportedVersionCode(context))
    }

    @Test
    fun `the share kill switch closes share and leaves backup`() {
        AppRemoteConfig.record(context, Result.success(licensed.copy(killSwitches = listOf("share"))))

        assertTrue(LicenseEntitlements.isLicensed(context))
        assertFalse(LicenseEntitlements.shareEnabled(context))
        assertTrue(LicenseEntitlements.cloudBackupEnabled(context))
        assertTrue(LicenseEntitlements.restoreEnabled(context))
    }

    @Test
    fun `the backup kill switch closes backup and restore, not share`() {
        AppRemoteConfig.record(context, Result.success(licensed.copy(killSwitches = listOf("backup"))))

        assertFalse(LicenseEntitlements.cloudBackupEnabled(context))
        assertFalse(LicenseEntitlements.restoreEnabled(context))
        assertTrue(LicenseEntitlements.shareEnabled(context))
        assertFalse(AppPolicy.uploadAllowed(context))
    }

    @Test
    fun `the restore kill switch closes restore only`() {
        AppRemoteConfig.record(context, Result.success(licensed.copy(killSwitches = listOf("restore"))))

        assertTrue(LicenseEntitlements.cloudBackupEnabled(context))
        assertFalse(LicenseEntitlements.restoreEnabled(context))
        assertTrue(AppPolicy.uploadAllowed(context))
    }

    @Test
    fun `an unversioned debug build is never gated`() {
        AppRemoteConfig.record(context, Result.success(licensed.copy(minSupportedVersionCode = 1_000)))

        assertEquals(VersionVerdict.Current, AppPolicy.version(context))
        assertTrue(AppPolicy.newAnalysisAllowed(context))
    }

    @Test
    fun `without pinned keys a token changes nothing`() {
        AppRemoteConfig.record(context, Result.success(licensed.copy(entitlement = "not.a.token")))

        assertEquals(TokenStatus.NotUsed, EntitlementStore.status(context))
        assertTrue(LicenseEntitlements.isLicensed(context))
        assertEquals(999, LicenseEntitlements.analysisCap(context))
    }
}
