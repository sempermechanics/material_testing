package com.sempermechanics.semper.ui.limit

import android.os.Bundle
import android.view.View
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.session.SessionQuota
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.databinding.ActivitySessionLimitBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.SupportMail
import com.sempermechanics.semper.ui.common.auth.contextLines
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import kotlinx.coroutines.launch

/**
 * Persistent gate shown when the account's cloud analysis quota is full. Unlike
 * a transient warning, this screen stays until the limit is resolved. The cap
 * counts stored analyses, so the way on is to delete one and re-check; an
 * account that expects a seat or a licence taps Refresh licence. Support can
 * be mailed, neutrally: the app only consumes the licence the account holds,
 * so nothing here asks for a higher limit, a price or a purchase.
 */
@MainThread
class SessionLimitActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionLimitBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionLimitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.limitRoot)

        renderQuota()

        binding.btnEmailSupport.setOnClickListener { emailSupport() }
        binding.btnRecheckLimit.setOnClickListener { recheck() }
        binding.btnRefreshLicense.setOnClickListener { refreshLicense() }
        binding.tvLimitBack.setOnClickListener { finish() }
    }

    /**
     * The body and the counter, by which half of the cap holds the stop. The
     * account's counter is beside the point when the device is full (TD-279):
     * "3 / 25" under "limit reached" reads as a fault, so it is hidden.
     */
    private fun renderQuota() {
        val deviceStop = SessionQuota.deviceStop(this)
        binding.tvLimitBody.setText(if (deviceStop) R.string.limit_body_device else R.string.limit_body)
        val used = AccountCache.quotaUsed(this)
        // The cap the gate enforces, not the server's maxSessions: with the
        // entitlement token lapsed that is demo's (TD-263).
        val max = LicenseEntitlements.shownAnalysisCap(this)
        // Only show the counter when the backend actually reported numbers.
        binding.tvLimitQuota.isVisible = max > 0 && !deviceStop
        if (binding.tvLimitQuota.isVisible) {
            binding.tvLimitQuota.text = resources.getQuantityString(R.plurals.limit_quota_fmt, used, used, max)
        }
    }

    /**
     * Opens the mail app on a neutral message to support, with the account,
     * its quota and the device underneath: the blank lines leave the cursor
     * above them, as Settings' support mail does. It asks for nothing.
     */
    private fun emailSupport() {
        val email = AccountCache.cachedEmail(this) ?: getString(R.string.pending_unknown_account)
        val quota = "Quota: ${AccountCache.quotaUsed(this)}/${LicenseEntitlements.shownAnalysisCap(this)}" +
            if (SessionQuota.deviceStop(this)) " (device limit)" else ""
        val context = SupportMail.contextLines(email, DeviceKeys.deviceId(this), extra = listOf(quota))
        SupportMail.open(
            this,
            subject = getString(R.string.limit_subject) + " — " + email,
            body = "\n\n---\n$context",
            purpose = "support from the limit screen",
        )
    }

    /**
     * For an account that expects a seat or a licence: ask `/v1/config` now
     * ([LicenseRefresh]), say what it found, and when the licence changed run
     * the limit check again, which closes this screen once there is room.
     */
    private fun refreshLicense() {
        setLoading(true)
        lifecycleScope.launch {
            val outcome = LicenseRefresh.run(this@SessionLimitActivity)
            setLoading(false)
            Feedback.toast(this@SessionLimitActivity, outcome.result.messageRes, long = true)
            if (outcome.changed) recheck() else renderQuota()
        }
    }

    /**
     * Re-query the backend; if the account is under the limit again, dismiss.
     * The device's stop outlives it: the reconcile reports the account's count
     * only, and lifting the stop on it let the next upload be refused again.
     */
    private fun recheck() {
        setLoading(true)
        lifecycleScope.launch {
            // deep=true: the user explicitly tapped Recheck, so bypass the
            // reconcile throttle — a silently skipped check would report
            // "still full" from stale data.
            val outcome = CloudSync.reconcile(this@SessionLimitActivity, deep = true)
            // The rows after the reconcile, which may have repaired some.
            val localCount = SessionStore.listOnIo(this@SessionLimitActivity).size
            when (outcome) {
                is CloudSync.Outcome.Ok -> {
                    // Ceiling is owned by AppRemoteConfig (refreshed by the same
                    // reconcile's config fetch); only the used count is stored here.
                    AccountCache.setQuota(
                        this@SessionLimitActivity,
                        outcome.quotaUsed,
                        localCount,
                    )
                }
                else -> Unit
            }
            setLoading(false)
            if (!SessionQuota.blocked(this@SessionLimitActivity, localCount)) {
                Feedback.toast(this@SessionLimitActivity, R.string.limit_cleared)
                finish()
            } else {
                renderQuota()
                val still = if (SessionQuota.deviceStop(this@SessionLimitActivity)) {
                    R.string.limit_still_full_device
                } else {
                    R.string.limit_still_full
                }
                Feedback.toast(this@SessionLimitActivity, still, long = true)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressLimit.setBusy(
            loading,
            binding.btnRecheckLimit,
            binding.btnRefreshLicense,
            binding.btnEmailSupport,
            idleVisibility = View.INVISIBLE,
        )
    }
}
