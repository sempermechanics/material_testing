package com.indicvision.semper.data

import android.content.Context
import com.indicvision.semper.data.net.AppRemoteConfig

/**
 * Client view of the account's Demo / Professional entitlements.
 *
 * The backend is the source of truth after [AppRemoteConfig.apply]. Until then
 * the app fails closed to Demo: 25 saved analyses, no cloud backup/restore,
 * no share.
 */
object LicenseEntitlements {

    const val PLAN_DEMO = "demo"
    const val PLAN_PROFESSIONAL = "professional"
    const val DEMO_MAX_ANALYSES = 25
    const val BLOCKED_ALPHA = 0.5f

    fun plan(context: Context): String {
        val stored = AppRemoteConfig.plan(context)
        return if (stored == PLAN_PROFESSIONAL) PLAN_PROFESSIONAL else PLAN_DEMO
    }

    fun isProfessional(context: Context): Boolean = plan(context) == PLAN_PROFESSIONAL

    fun cloudBackupEnabled(context: Context): Boolean =
        isProfessional(context) && AppRemoteConfig.cloudBackupEnabled(context)

    fun shareEnabled(context: Context): Boolean =
        isProfessional(context) && AppRemoteConfig.shareEnabled(context)

    /**
     * `""`, `"individual"`, or `"campus"`. Display/support metadata only — an
     * individual and a campus seat both resolve to [PLAN_PROFESSIONAL] with
     * identical entitlements, so nothing above gates on this value.
     */
    fun licenseKind(context: Context): String = AppRemoteConfig.licenseKind(context)

    fun unlimitedAnalysis(context: Context): Boolean = isProfessional(context)

    /**
     * Local analysis cap. Demo is 25 even before config has been fetched.
     * Professional has no local analysis cap.
     */
    fun analysisCap(context: Context): Int {
        if (unlimitedAnalysis(context)) return Int.MAX_VALUE
        val remote = AppRemoteConfig.maxSessions(context)
        return if (remote > 0) remote else DEMO_MAX_ANALYSES
    }
}
