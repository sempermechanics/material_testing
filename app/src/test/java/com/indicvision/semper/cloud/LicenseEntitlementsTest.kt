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
 * Key invariant under test: an individual key and a campus seat are the same
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
        assertEquals(LicenseEntitlements.PLAN_DEMO, LicenseEntitlements.plan(ctx))
        assertFalse(LicenseEntitlements.isProfessional(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
        assertEquals("", LicenseEntitlements.licenseKind(ctx))
    }

    @Test
    fun `an individual professional key and a campus seat grant identical entitlements`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(
                plan = "professional",
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
                plan = "professional",
                cloudBackupEnabled = true,
                shareEnabled = true,
                licensePrefix = "SEMP-CD34",
                licenseKind = "campus",
            ),
        )
        assertEquals(individualBackup, LicenseEntitlements.cloudBackupEnabled(ctx))
        assertEquals(individualShare, LicenseEntitlements.shareEnabled(ctx))
        assertEquals(individualUnlimited, LicenseEntitlements.unlimitedAnalysis(ctx))
        assertTrue(LicenseEntitlements.isProfessional(ctx))
        // licenseKind itself DOES differ — it's carried through for display only.
        assertEquals("campus", LicenseEntitlements.licenseKind(ctx))
    }

    @Test
    fun `demo plan stays gated even if cloudBackupEnabled somehow arrives true`() {
        // Defense in depth: plan is the real gate, not the individual booleans —
        // a demo user must never get cloud backup/share regardless of what
        // else is in the config payload.
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(plan = "demo", cloudBackupEnabled = true, shareEnabled = true),
        )
        assertFalse(LicenseEntitlements.isProfessional(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `professional has no local analysis cap`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(plan = "professional", maxSessions = 5))
        assertEquals(Int.MAX_VALUE, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `demo analysis cap falls back to the constant before config is known`() {
        assertEquals(LicenseEntitlements.DEMO_MAX_ANALYSES, LicenseEntitlements.analysisCap(ctx))
    }

    @Test
    fun `downgrading from professional to demo drops entitlements without needing a clear`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(plan = "professional", cloudBackupEnabled = true, shareEnabled = true),
        )
        assertTrue(LicenseEntitlements.isProfessional(ctx))

        // revalidate_device_lock / a revoked seat resolves the NEXT config fetch
        // to plan=demo — the app applies whatever GET /v1/config last returned.
        AppRemoteConfig.apply(ctx, AppConfigDto(plan = "demo"))
        assertFalse(LicenseEntitlements.isProfessional(ctx))
        assertFalse(LicenseEntitlements.cloudBackupEnabled(ctx))
        assertFalse(LicenseEntitlements.shareEnabled(ctx))
    }
}
