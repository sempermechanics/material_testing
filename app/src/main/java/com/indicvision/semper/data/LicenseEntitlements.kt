package com.indicvision.semper.data

import android.content.Context
import com.indicvision.semper.data.net.AppRemoteConfig

/**
 * Client view of the account's demo / licensed entitlements.
 *
 * The backend is the source of truth after [AppRemoteConfig.apply]. Until then
 * the app fails closed to demo: 25 saved analyses, no cloud backup/restore,
 * no share.
 */
object LicenseEntitlements {

    const val MODE_DEMO = "demo"
    const val MODE_LICENSED = "licensed"
    const val DEMO_MAX_ANALYSES = 25
    const val BLOCKED_ALPHA = 0.5f

    fun mode(context: Context): String {
        val stored = AppRemoteConfig.mode(context)
        return if (stored == MODE_LICENSED) MODE_LICENSED else MODE_DEMO
    }

    fun isLicensed(context: Context): Boolean = mode(context) == MODE_LICENSED

    fun cloudBackupEnabled(context: Context): Boolean =
        isLicensed(context) && AppRemoteConfig.cloudBackupEnabled(context)

    fun shareEnabled(context: Context): Boolean =
        isLicensed(context) && AppRemoteConfig.shareEnabled(context)

    /**
     * `""`, `"individual"`, or `"institution"`. Display/support metadata only —
     * an individual and an institution seat both resolve to [MODE_LICENSED]
     * with identical entitlements, so nothing above gates on this value.
     */
    fun licenseKind(context: Context): String = AppRemoteConfig.licenseKind(context)

    fun unlimitedAnalysis(context: Context): Boolean = isLicensed(context)

    /**
     * Local analysis cap. Demo is 25 even before config has been fetched.
     * A licensed account has no local analysis cap.
     */
    fun analysisCap(context: Context): Int {
        if (unlimitedAnalysis(context)) return Int.MAX_VALUE
        val remote = AppRemoteConfig.maxSessions(context)
        return if (remote > 0) remote else DEMO_MAX_ANALYSES
    }
}
