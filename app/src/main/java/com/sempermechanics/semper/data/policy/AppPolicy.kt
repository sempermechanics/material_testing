package com.sempermechanics.semper.data.policy

import android.content.Context
import androidx.core.content.edit
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.PrefFiles
import com.sempermechanics.semper.data.prefs.get
import com.sempermechanics.semper.data.prefs.privatePrefs
import com.sempermechanics.semper.data.prefs.put

/**
 * [FeatureGates] over this phone's inputs: the kill switches and version
 * gate from the last `/v1/config` ([AppRemoteConfig]) and the managed
 * configuration ([ManagedConfigReader]). Each answer is a condition on top of
 * the licence, read where the licence is read (`LicenseEntitlements`).
 */
object AppPolicy {

    /** `app/build.gradle.kts`'s versionCode when no `-PversionCode` is given. */
    private const val UNVERSIONED = 1

    fun inputs(context: Context): PolicyInputs = PolicyInputs(
        killSwitches = AppRemoteConfig.killSwitches(context),
        managed = ManagedConfigReader.current(context),
        version = version(context),
    )

    /**
     * This build against the backend's minimum and recommended versionCode.
     *
     * A local debug build without `-PversionCode` is versionCode 1 and is no
     * release the backend can retire, so it is never gated; to try the gate,
     * build with `-PversionCode` below the backend's minimum.
     */
    fun version(context: Context): VersionVerdict =
        if (BuildConfig.DEBUG && BuildConfig.VERSION_CODE == UNVERSIONED) {
            VersionVerdict.Current
        } else {
            VersionGate.decide(
                versionCode = BuildConfig.VERSION_CODE,
                minSupported = AppRemoteConfig.minSupportedVersionCode(context),
                recommended = AppRemoteConfig.recommendedVersionCode(context),
                lastNudge = privatePrefs(context, PrefFiles.Onboarding.NAME)[PrefFiles.Onboarding.UPDATE_NUDGE],
            )
        }

    /** The update nudge for [verdict] has been shown; it is not shown again for this pair. */
    fun markNudged(context: Context, verdict: VersionVerdict.Recommended) {
        privatePrefs(context, PrefFiles.Onboarding.NAME).edit {
            put(PrefFiles.Onboarding.UPDATE_NUDGE, verdict.nudgeKey)
        }
    }

    fun shareAllowed(context: Context): Boolean = FeatureGates.share(inputs(context))

    fun backupAllowed(context: Context): Boolean = FeatureGates.backup(inputs(context))

    fun recordsToCloud(context: Context): Boolean = FeatureGates.recordsToCloud(inputs(context))

    fun uploadAllowed(context: Context): Boolean = FeatureGates.upload(inputs(context))

    fun restoreAllowed(context: Context): Boolean = FeatureGates.restore(inputs(context))

    fun newAnalysisAllowed(context: Context): Boolean = FeatureGates.newAnalysis(inputs(context))
}
