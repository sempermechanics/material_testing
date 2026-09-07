package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.LicenseEntitlements
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LicenseEntitlements] is the single place the app asks "am I Demo or
 * Professional" — everything else (Settings gates, upload/backup, share)
 * should read through it rather than [AppRemoteConfig] directly.
 *
 * Key invariant under test: an individual key and an institution seat are the same
 * plan shape to every gate here. `licenseKind` is carried through purely for
 * display/support, never as a gating input — see the doc comment on
 * [LicenseEntitlements.licenseKind].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LicenseEntitlementsTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        AppRemoteConfig.clear(ctx)
    }

    @After
    fun tearDown() {
        AppRemoteConfig.clear(ctx)
    }

    @Test
    fun `fails closed to Demo before any config has ever landed`() {
        assertEquals(LicenseEntitlements.MODE_DEMO, LicenseEntitlements.mode(ctx))
        assertFalse(LicenseEntitlements.isLicensed(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
        assertEquals("", LicenseEntitlements.licenseKind(ctx))
    }

    @Test
    fun `an individual key and an institution seat grant identical entitlements`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(
                mode = "licensed",
                cloudBackupEnabled = true,
                shareEnabled = true,
                licensePrefix = "SEMP-AB12",
                licenseKind = "individual",
            ),
        )
        val individualBackup = LicenseEntitlements.cloudBackupEnabled(ctx)
        val individualShare = LicenseEntitlements.shareEnabled(ctx)
        val individualUnlimited = LicenseEntitlements.unlimitedAnalysis(ctx)

        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(
                mode = "licensed",
                cloudBackupEnabled = true,
                shareEnabled = true,
                licensePrefix = "SEMP-CD34",
                licenseKind = "institution",
            ),
        )
        assertEquals(individualBackup, LicenseEntitlements.cloudBackupEnabled(ctx))
        assertEquals(individualShare, LicenseEntitlements.shareEnabled(ctx))
        assertEquals(individualUnlimited, LicenseEntitlements.unlimitedAnalysis(ctx))
        assertTrue(LicenseEntitlements.isLicensed(ctx))
        // licenseKind itself DOES differ — it's carried through for display only.
        assertEquals("institution", LicenseEntitlements.licenseKind(ctx))
    }

    @Test
    fun `demo mode stays gated even if cloudBackupEnabled somehow arrives true`() {
        // Defense in depth: mode is the real gate, not the individual booleans —
        // a demo user must never get cloud backup/share regardless of what
        // else is in the config payload.
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "demo", cloudBackupEnabled = true, shareEnabled = true),
        )
        assertFalse(LicenseEntitlements.isLicensed(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `a licensed account has no local analysis cap`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", maxSessions = 5))
        assertEquals(Int.MAX_VALUE, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `demo analysis cap falls back to the constant before config is known`() {
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `downgrading from licensed to demo drops entitlements without needing a clear`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "licensed", cloudBackupEnabled = true, shareEnabled = true),
        )
        assertTrue(LicenseEntitlements.isLicensed(ctx))

        // revalidate_device_lock / a revoked seat resolves the NEXT config fetch
        // to mode=demo — the app applies whatever GET /v1/config last returned.
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "demo"))
        assertFalse(LicenseEntitlements.isLicensed(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
    }

    // ---- compatibility with a backend that predates the plan->mode rename ----

    @Test
    fun `falls back to the plan mirror when the backend sends no mode`() {
        // A deploy that has not shipped the rename sends only `plan`. Reading
        // that as demo would strip a paying account's entitlements.
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(plan = "professional", cloudBackupEnabled = true, shareEnabled = true),
        )
        assertTrue(LicenseEntitlements.isLicensed(ctx))
        assertTrue(LicenseEntitlements.cloudBackupEnabled(ctx))
    }

    @Test
    fun `mode wins over a disagreeing plan mirror`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "demo", plan = "professional", cloudBackupEnabled = true),
        )
        assertFalse(LicenseEntitlements.isLicensed(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
    }

    @Test
    fun `an unrecognised mode is demo, never licensed`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "enterprise", plan = "enterprise", cloudBackupEnabled = true),
        )
        assertFalse(LicenseEntitlements.isLicensed(ctx))
    }

    @Test
    fun `the pre-rename campus licenseKind is normalised to institution`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", licenseKind = "campus"))
        assertEquals("institution", LicenseEntitlements.licenseKind(ctx))
    }

    @Test
    fun `a licensed account upgrading the app is not demoted before the next fetch`() {
        // Simulate the prefs a build predating the rename left behind: it wrote
        // the plan under its own key and knows nothing about `mode`. Reading
        // demo here would strip entitlements from launch until /v1/config
        // lands, which offline may be a long time.
        ctx.getSharedPreferences("indic_remote_config", Context.MODE_PRIVATE)
            .edit()
            .putString("plan", "professional")
            .commit()

        assertEquals(LicenseEntitlements.MODE_LICENSED, LicenseEntitlements.mode(ctx))
        assertTrue(LicenseEntitlements.isLicensed(ctx))
    }

    @Test
    fun `a demo account upgrading the app stays demo`() {
        ctx.getSharedPreferences("indic_remote_config", Context.MODE_PRIVATE)
            .edit()
            .putString("plan", "demo")
            .commit()

        assertFalse(LicenseEntitlements.isLicensed(ctx))
    }
}
