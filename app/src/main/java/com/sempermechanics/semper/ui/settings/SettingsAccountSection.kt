package com.sempermechanics.semper.ui.settings

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.ActivationDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.databinding.ViewSettingsScrollContentBinding
import com.sempermechanics.semper.ui.admin.AdminActivity
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.ui.home.HomeLicenseNotice
import com.sempermechanics.semper.ui.limit.LicenseRefresh
import com.sempermechanics.semper.ui.phones.PhonesActivity
import com.sempermechanics.semper.ui.phones.PhonesView
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Account identity row: email, device id, licence prefix, whether this is a
 * QC or CI test account, the licence's phones, Refresh licence, and the admin
 * entry point.
 */
class SettingsAccountSection(
    private val activity: SettingsActivity,
    private val views: ViewSettingsScrollContentBinding,
) {
    fun wire() {
        views.tvAccountEmail.text = AccountCache.cachedEmail(activity).orEmpty()
        val deviceId = DeviceKeys.deviceId(activity)
        views.tvAccountDevice.text = activity.getString(R.string.account_device_id_fmt, deviceId)

        // The prefix is the only part of a key the app is ever told, and it is
        // what support asks for. Shown only while the account runs licensed:
        // older backends send the prefix of any key the account holds, a
        // revoked, lapsed or unseated one included, and "Licensed as" beside
        // demo's limits is wrong. Empty on a backend that predates the field.
        val prefix = LicenseEntitlements.licensePrefix(activity)
        views.tvAccountLicense.apply {
            isVisible = prefix.isNotEmpty() && LicenseEntitlements.isLicensed(activity)
            text = activity.getString(R.string.account_license_prefix_fmt, prefix)
        }

        // Staff made this account for QC or CI, and it is ended on that day.
        val testAccount = testAccountLine(activity, AppRemoteConfig.testAccount(activity))
        views.tvAccountTestAccount.apply {
            isVisible = testAccount != null
            text = testAccount.orEmpty()
        }

        wirePhones()
        views.btnRefreshLicense.setOnClickListener { refreshLicense() }

        views.btnAdmin.apply {
            isVisible = AccountCache.isAdmin(activity)
            setOnClickListener { activity.startActivity(Intent(activity, AdminActivity::class.java)) }
        }
    }

    /**
     * The licence's phones (ADR-033): its count, and Your phones, where this
     * phone is activated and the others deactivated. Shown while the account
     * holds a licence for this app that counts phones, or a new one waits;
     * not for Demo, nor a floating seat, which is checked out instead.
     */
    private fun wirePhones() {
        val activation = AppRemoteConfig.activation(activity)
        val upgrade = AppRemoteConfig.pendingLicence(activity) != null
        val count = PhonesView.count(activation.devicesActive, activation.maxDevices, activation.pool)
        views.tvAccountPhones.apply {
            isVisible = count != null && activation.state != ActivationDto.STATE_FLOATING
            text = count?.text(activity).orEmpty()
        }
        views.btnAccountPhones.apply {
            isVisible = upgrade || activation.isActive || activation.canActivate
            setOnClickListener { PhonesActivity.open(activity) }
        }
    }

    /**
     * Asks `/v1/config` for the account's licence now ([LicenseRefresh]) and
     * says what it found. A change rebuilds Settings: the licence decides
     * which cloud and storage rows are open or locked.
     */
    private fun refreshLicense() {
        setRefreshing(true)
        activity.lifecycleScope.launch {
            val outcome = LicenseRefresh.run(activity)
            setRefreshing(false)
            Feedback.toast(activity, outcome.result.messageRes, long = true)
            if (outcome.changed) activity.recreate()
        }
    }

    private fun setRefreshing(busy: Boolean) {
        views.progressRefreshLicense.setBusy(busy, views.btnRefreshLicense, idleVisibility = View.GONE)
    }

    companion object {
        /**
         * The account section's test-account line: "Test account · ends
         * {day}" (the UTC day, in [locale], as the licence lines and the
         * consoles show an end), "Test account (CI)" for a CI account with no
         * end, or null for a real account.
         */
        internal fun testAccountLine(
            context: Context,
            account: AppRemoteConfig.TestAccount?,
            locale: Locale = Locale.getDefault(),
        ): String? = when {
            account == null -> null
            account.expiresAtMs > AppRemoteConfig.NO_INSTANT -> context.getString(
                R.string.account_test_account_ends_fmt,
                HomeLicenseNotice.formatDay(account.expiresAtMs, locale),
            )
            account.isCi -> context.getString(R.string.account_test_account_ci)
            else -> context.getString(R.string.account_test_account)
        }
    }
}
