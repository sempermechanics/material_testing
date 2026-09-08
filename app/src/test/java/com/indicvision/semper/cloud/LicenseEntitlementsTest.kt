package com.indicvision.semper.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.data.LicenseEntitlements
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

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

    // ---- license duration, grace, and cache staleness ----

    private val day = 24L * 60 * 60 * 1000

    private fun applyLicensed(expiresAt: String?, inGrace: Boolean = false, now: Long) {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(
                mode = "licensed",
                licenseDuration = if (expiresAt == null) "perpetual" else "timed",
                licenseExpiresAt = expiresAt,
                inGrace = inGrace,
            ),
            now = now,
        )
    }

    @Test
    fun `a perpetual license never produces an expiry notice`() {
        val now = 1_000_000_000_000L
        applyLicensed(expiresAt = null, now = now)
        assertNull(LicenseEntitlements.daysUntilExpiry(ctx, now))
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `no notice until the license is inside the warning window`() {
        val now = 1_000_000_000_000L
        applyLicensed(Instant.ofEpochMilli(now + 30 * day).toString(), now = now)
        assertEquals(30L, LicenseEntitlements.daysUntilExpiry(ctx, now))
        assertNull("30 days out is not yet worth interrupting for",
            LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `a notice appears inside the warning window`() {
        val now = 1_000_000_000_000L
        applyLicensed(Instant.ofEpochMilli(now + 9 * day).toString(), now = now)
        assertEquals(9L, LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `grace notices regardless of how far past expiry it is`() {
        val now = 1_000_000_000_000L
        applyLicensed(Instant.ofEpochMilli(now - 3 * day).toString(), inGrace = true, now = now)
        assertTrue(LicenseEntitlements.inGrace(ctx))
        // Still fully entitled — grace withdraws nothing.
        assertTrue(LicenseEntitlements.isLicensed(ctx))
        assertEquals(-3L, LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `a stale cache suppresses the notice entirely`() {
        // A renewal may have landed while the device was offline. Warning from
        // a weeks-old cache would be a false alarm the user cannot act on.
        val fetchedAt = 1_000_000_000_000L
        applyLicensed(Instant.ofEpochMilli(fetchedAt + 2 * day).toString(), now = fetchedAt)
        val muchLater = fetchedAt + 30 * day
        assertTrue(AppRemoteConfig.isStale(ctx, LicenseEntitlements.STALE_CACHE_MS, muchLater))
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, muchLater))
    }

    @Test
    fun `a demo account never gets an expiry notice`() {
        val now = 1_000_000_000_000L
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "demo", licenseExpiresAt = Instant.ofEpochMilli(now).toString()),
            now = now,
        )
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `an unparseable expiry is treated as absent, not as expired at the epoch`() {
        val now = 1_000_000_000_000L
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "licensed", licenseDuration = "timed", licenseExpiresAt = "not-a-date"),
            now = now,
        )
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `the dev-auth bypass licenses without an expiry, so it never warns`() {
        // DevAuth.install seeds mode=licensed with no expiry. A banner firing
        // on every emulator launch would be noise nobody can act on.
        val now = 1_000_000_000_000L
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(mode = "licensed", cloudBackupEnabled = true, shareEnabled = true),
            now = now,
        )
        assertNull(LicenseEntitlements.expiryNoticeDays(ctx, now))
    }

    @Test
    fun `a never-fetched cache is stale`() {
        assertTrue(AppRemoteConfig.isStale(ctx, LicenseEntitlements.STALE_CACHE_MS, 1L))
    }

    @Test
    fun `a fetched-at in the future reads as stale, not fresh forever`() {
        // NTP correction or the user changing the date. CloudSync guards its
        // reconcile throttle the same way.
        val fetchedAt = 1_000_000_000_000L
        applyLicensed(expiresAt = null, now = fetchedAt)
        assertTrue(AppRemoteConfig.isStale(ctx, LicenseEntitlements.STALE_CACHE_MS, fetchedAt - day))
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
