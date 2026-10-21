package com.sempermechanics.semper.diagnostics

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.AppSettings
import timber.log.Timber

/**
 * Applies the user's diagnostics choice to the Firebase SDKs.
 *
 * Crashlytics and Analytics used to collect from first launch with no notice and
 * no way to decline. Collection is now disabled in the manifest, so the default
 * for a fresh install is *off*; this turns it on only after an explicit opt-in
 * and turns it back off the moment the user withdraws consent.
 *
 * Crashlytics keeps unsent reports on disk, so withdrawing consent also deletes
 * anything still queued — otherwise a "no" would still ship the last crash.
 */
object Diagnostics {

    /** The Analytics user property and Crashlytics custom key a test account is tagged by. */
    const val ACCOUNT_TYPE = "account_type"

    /** [ACCOUNT_TYPE] of a QC test account. */
    const val ACCOUNT_TYPE_TEST = "test"

    /** [ACCOUNT_TYPE] of a CI test account. */
    const val ACCOUNT_TYPE_CI = "ci"

    /** Where the account type goes: both Firebase SDKs. A test seam, as `SemperAnalytics.sink` is. */
    @Volatile
    var accountTypeSink: AccountTypeSink = FirebaseAccountTypeSink

    /** Receives the account type to tag diagnostics with; null clears it. */
    fun interface AccountTypeSink {
        fun tag(context: Context, accountType: String?)
    }

    private object FirebaseAccountTypeSink : AccountTypeSink {
        override fun tag(context: Context, accountType: String?) {
            runCatching {
                FirebaseAnalytics.getInstance(context).setUserProperty(ACCOUNT_TYPE, accountType)
                // Crashlytics has no way to remove a key; empty is "not a test account".
                FirebaseCrashlytics.getInstance().setCustomKey(ACCOUNT_TYPE, accountType.orEmpty())
            }.onFailure { Timber.w(it, "Could not tag the account type") }
        }
    }

    /**
     * Tags Analytics and Crashlytics with [accountType] of the cached test
     * account, so QC and CI traffic can be told from real use. Only while the
     * user has consented, like every event ([SemperAnalytics.event]);
     * cleared without consent, for a real account, and after sign-out. Called
     * whenever the consent or the cached config changes.
     */
    fun applyAccountType(context: Context) {
        val consented = AppSettings.diagnosticsEnabled(context)
        val type = if (consented) accountType(AppRemoteConfig.testAccount(context)) else null
        accountTypeSink.tag(context.applicationContext, type)
    }

    /** [ACCOUNT_TYPE_CI] for a CI account, [ACCOUNT_TYPE_TEST] for any other test account, null for a real one. */
    fun accountType(testAccount: AppRemoteConfig.TestAccount?): String? = when {
        testAccount == null -> null
        testAccount.isCi -> ACCOUNT_TYPE_CI
        else -> ACCOUNT_TYPE_TEST
    }

    /** Push the stored preference into both SDKs. Safe to call repeatedly. */
    fun apply(context: Context) {
        val enabled = AppSettings.diagnosticsEnabled(context)
        runCatching {
            FirebaseCrashlytics.getInstance().apply {
                isCrashlyticsCollectionEnabled = enabled
                if (!enabled) deleteUnsentReports()
            }
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled)
        }.onFailure {
            // Never let telemetry configuration take the app down.
            Timber.w(it, "Could not apply diagnostics preference")
        }
        applyAccountType(context)
    }

    /** Record the user's choice and apply it immediately. */
    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            AppSettings.setDiagnosticsEnabled(context, true)
            apply(context)
            SemperAnalytics.event(context, SemperAnalytics.DIAGNOSTICS_OPT_IN)
        } else {
            // Prefer was on; emit while the pref is still true, then disable.
            SemperAnalytics.event(context, SemperAnalytics.DIAGNOSTICS_OPT_OUT)
            AppSettings.setDiagnosticsEnabled(context, false)
            apply(context)
        }
    }
}
