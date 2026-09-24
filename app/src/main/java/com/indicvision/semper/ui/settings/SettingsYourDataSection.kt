@file:Suppress("TooManyFunctions", "LongMethod")

package com.indicvision.semper.ui.settings

import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.indicvision.semper.Diagnostics
import com.indicvision.semper.R
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.AuthRepository
import com.indicvision.semper.data.CloudAccountExport
import com.indicvision.semper.data.CloudSync
import com.indicvision.semper.data.DevAuth
import com.indicvision.semper.data.DicSettings
import com.indicvision.semper.data.SessionEverythingExporter
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenStore
import com.indicvision.semper.ui.common.AuthRoute
import com.indicvision.semper.ui.common.TransferBannerController
import com.indicvision.semper.ui.viewer.SendToSheet
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Export / erasure / diagnostics. Session restore / download / delete stay on
 * [SettingsActivity].
 */
class SettingsYourDataSection(
    private val activity: SettingsActivity,
) {
    fun wire() {
        activity.findViewById<View>(R.id.btnExportData).setOnClickListener { exportMyData() }
        activity.findViewById<View>(R.id.btnExportCloudData).setOnClickListener { exportCloudAccountData() }
        activity.findViewById<View>(R.id.btnDeleteAccount).setOnClickListener { confirmDeleteAccount() }

        val switchDiagnostics = activity.findViewById<SwitchMaterial>(R.id.switchDiagnostics)
        switchDiagnostics.isChecked = DicSettings.diagnosticsEnabled(activity)
        switchDiagnostics.setOnCheckedChangeListener { _, checked ->
            // Applies immediately in both directions: turning this off also
            // deletes any crash report still queued on disk.
            Diagnostics.setEnabled(activity, checked)
        }
        wireLegal()
    }

    /**
     * The product-improvement consent, kept apart from diagnostics and from
     * the Terms: it is optional, off until granted, and withdrawable here at
     * any time. The switch reflects the last value the server confirmed.
     */
    private fun wireLegal() {
        val switchImprove = activity.findViewById<SwitchMaterial>(R.id.switchImprovementConsent)
        switchImprove.isChecked = TokenStore.improvementConsent(activity) == true
        switchImprove.setOnCheckedChangeListener { _, checked ->
            switchImprove.isEnabled = false
            activity.lifecycleScope.launch {
                val result = AuthRepository(activity).setImprovementConsent(checked)
                switchImprove.isEnabled = true
                result.onFailure {
                    Timber.w(it, "Improvement consent update failed")
                    // Revert silently: the server still holds the previous answer.
                    switchImprove.setOnCheckedChangeListener(null)
                    switchImprove.isChecked = !checked
                    wireLegal()
                    Toast.makeText(activity, R.string.terms_error_generic, Toast.LENGTH_LONG).show()
                }
            }
        }

        val accepted = TokenStore.termsAcceptedVersion(activity)
        activity.findViewById<TextView>(R.id.tvTermsAccepted).text =
            if (accepted == null) {
                activity.getString(R.string.settings_terms_not_accepted)
            } else {
                activity.getString(R.string.settings_terms_accepted_fmt, accepted)
            }
        activity.findViewById<View>(R.id.btnViewTerms).setOnClickListener {
            activity.openExternalUrl(activity.getString(R.string.legal_terms_url))
        }
    }

    /**
     * Download what the *cloud* holds about this account (GDPR Art. 20).
     *
     * Separate from [exportMyData], which bundles the sessions on this device.
     * The policy has always promised this; until now it existed only as an API
     * endpoint with no way for a user to reach it.
     */
    private fun exportCloudAccountData() {
        val api = IndicApi.get(activity)
        if (!api.enabled) {
            Toast.makeText(activity, R.string.export_cloud_data_offline, Toast.LENGTH_LONG).show()
            return
        }
        runExport(CLOUD_EXPORT) { CloudAccountExport.download(activity.cacheDir, api) }
    }

    private fun exportMyData() {
        runExport(LOCAL_EXPORT) { onProgress ->
            SessionEverythingExporter.exportMasterZip(activity, onProgress)?.file
        }
    }

    /** What differs between the two "export my data" buttons; the flow is shared. */
    private class ExportKind(
        val key: String,
        val analyticsKind: String,
        val bannerTitle: Int,
        val failedMessage: Int,
        val mime: String,
    )

    /**
     * Banner, produce, share: one flow for both exports. [produce] returns the
     * file to share, or null on failure; an empty file counts as a failure.
     * Cancelling from the banner cancels [produce] — and says nothing, because
     * the user asked for it.
     */
    private fun runExport(
        kind: ExportKind,
        produce: suspend (onProgress: (done: Int, total: Int) -> Unit) -> java.io.File?,
    ) {
        if (activity.transferBanner.contains(kind.key)) {
            Toast.makeText(activity, R.string.download_analysis_already, Toast.LENGTH_SHORT).show()
            return
        }
        var job: kotlinx.coroutines.Job? = null
        SemperAnalytics.event(activity, SemperAnalytics.EXPORT_STARTED, mapOf("kind" to kind.analyticsKind))
        activity.transferBanner.upsert(
            TransferBannerController.Transfer(
                id = kind.key,
                title = activity.getString(kind.bannerTitle),
                onCancel = { job?.cancel() },
            ),
        )
        job = activity.lifecycleScope.launch {
            try {
                val produced = produce { done, total ->
                    val pct = if (total > 0) done * SettingsActivity.PERCENT_MAX / total else 0
                    activity.runOnUiThread {
                        activity.transferBanner.updateProgress(
                            kind.key,
                            pct,
                            activity.getString(R.string.export_progress_fmt, done, total),
                        )
                    }
                }
                activity.transferBanner.remove(kind.key)
                // Safety: only share a file that actually exists and has content.
                val file = produced?.takeIf { it.exists() && it.length() > 0L }
                if (file == null) {
                    SemperAnalytics.event(
                        activity,
                        SemperAnalytics.EXPORT_FAILED,
                        mapOf("kind" to kind.analyticsKind),
                    )
                    Toast.makeText(activity, kind.failedMessage, Toast.LENGTH_LONG).show()
                    return@launch
                }
                SemperAnalytics.event(
                    activity,
                    SemperAnalytics.EXPORT_COMPLETED,
                    mapOf("kind" to kind.analyticsKind),
                )
                SendToSheet.show(activity, file, kind.mime)
            } finally {
                activity.transferBanner.remove(kind.key)
            }
        }
    }

    private fun confirmDeleteAccount() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.delete_account_title)
            .setMessage(R.string.delete_account_body)
            .setPositiveButton(R.string.delete_account_confirm) { _, _ -> verifyThenDeleteAccount() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * Erasing an identity is the one action a stolen unlocked phone must not be
     * able to perform on an old session, so we prove who is holding it first.
     */
    private fun verifyThenDeleteAccount() {
        if (DevAuth.active) {
            deleteAccount()
            return
        }
        activity.launchReauth()
    }

    fun deleteAccount() {
        val progress = MaterialAlertDialogBuilder(activity)
            .setMessage(R.string.delete_account_working)
            .setCancelable(false)
            .show()
        activity.lifecycleScope.launch {
            val outcome = CloudSync.deleteAccount(activity)
            progress.dismiss()
            when (outcome) {
                CloudSync.AccountDeletion.DELETED -> {
                    activity.toast(activity.getString(R.string.delete_account_done))
                    AuthRoute.toSignIn(activity)
                }
                CloudSync.AccountDeletion.IDENTITY_KEPT -> {
                    activity.toast(activity.getString(R.string.delete_account_identity_kept))
                    AuthRoute.toSignIn(activity)
                }
                CloudSync.AccountDeletion.CLOUD_UNREACHABLE ->
                    activity.toast(activity.getString(R.string.delete_account_failed))
            }
        }
    }

    private companion object {
        val CLOUD_EXPORT = ExportKind(
            key = "export_cloud",
            analyticsKind = "cloud",
            bannerTitle = R.string.transfer_banner_export_cloud,
            failedMessage = R.string.export_cloud_data_failed,
            mime = SettingsActivity.JSON_MIME,
        )
        val LOCAL_EXPORT = ExportKind(
            key = "export_local",
            analyticsKind = "local",
            bannerTitle = R.string.transfer_banner_export_local,
            failedMessage = R.string.export_data_failed,
            mime = SettingsActivity.ZIP_MIME,
        )
    }
}
