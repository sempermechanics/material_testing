package com.sempermechanics.semper.ui.home

import android.content.Context
import android.content.Intent
import android.widget.TextView
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.session.SessionQuota
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.ui.limit.SessionLimitActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home's account lines and its analysis-limit gate: the quota line
 * ([quotaView], shown from [SHOW_FROM_PERCENT] percent of the ceiling),
 * the license-expiry notice ([licenseView]), and the
 * persistent limit screen every quota check on Home opens.
 *
 * Everything here that reads the licence reaches EntitlementStore, whose first
 * read opens the Keystore and whose clock re-seal is a Keystore HMAC, so none of
 * it runs on the main thread (readiness TE1-006, ADR-016): the lines render from
 * [Lines], read with Home's list on its IO dispatcher, and each limit check runs
 * on [io] and then acts.
 *
 * @param openSettings where a tap on the quota line goes while under the limit.
 */
class HomeQuotaCard(
    private val activity: AppCompatActivity,
    private val quotaView: TextView,
    private val licenseView: TextView,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val openSettings: () -> Unit,
) {

    /** The licence half of both lines, read off the main thread ([read]). */
    data class Lines(
        /** [LicenseEntitlements.shownAnalysisCap]: 0 while no ceiling is known. */
        val shownCap: Int,
        /** [LicenseEntitlements.expiryNoticeDays]. */
        val expiryNoticeDays: Long?,
        /** [LicenseEntitlements.isInGrace]. */
        val inGrace: Boolean,
    ) {
        companion object {
            /** Before the first read: no ceiling and no notice. */
            val NONE = Lines(shownCap = 0, expiryNoticeDays = null, inGrace = false)

            /** Reads the licence for both lines; reaches EntitlementStore, so never on the main thread. */
            @WorkerThread
            fun read(context: Context) = Lines(
                shownCap = LicenseEntitlements.shownAnalysisCap(context),
                expiryNoticeDays = LicenseEntitlements.expiryNoticeDays(context),
                inGrace = LicenseEntitlements.isInGrace(context),
            )
        }
    }

    /** Whether the account is at its limit, read on [io] ([SessionQuota.blocked] reads the licence). */
    private suspend fun blocked(localCount: Int?): Boolean = withContext(io) {
        if (localCount != null) SessionQuota.blocked(activity, localCount) else SessionQuota.blockedNow(activity)
    }

    /**
     * At the account's analysis limit ([SessionQuota.blocked]), opens the
     * persistent limit screen and says so; Home's every quota
     * gate goes through here. [localCount] is the phone's rows when the caller
     * has just counted them; a tap on the main thread uses the count the
     * index's last write stored ([SessionQuota.blockedNow]). The check runs
     * on [io]; the screen opens back on the caller's thread.
     */
    suspend fun openLimitScreenIfReached(localCount: Int? = null): Boolean {
        val reached = blocked(localCount)
        if (reached) activity.startActivity(Intent(activity, SessionLimitActivity::class.java))
        return reached
    }

    /** Both lines, for a phone list of [localSessionCount] analyses and the licence as [lines] read it. */
    fun render(localSessionCount: Int, lines: Lines) {
        updateQuotaIndicator(localSessionCount, lines.shownCap)
        updateLicenseNotice(lines)
    }

    /**
     * Records the used count a cloud check returned, with the phone's own
     * count, so the new-analysis gate and the limit screen reflect the
     * latest server truth; newly at the cap, opens the limit screen.
     */
    suspend fun recordReconciled(quotaUsed: Int) {
        val localCount = withContext(Dispatchers.IO) { SessionStore.list(activity).size }
        val wasLimited = blocked(localCount)
        // Ceiling is owned by AppRemoteConfig (refreshed by the same
        // reconcile's config fetch); only the used count is stored here.
        AccountCache.setQuota(activity, quotaUsed, localCount)
        if (!wasLimited) openLimitScreenIfReached(localCount)
    }

    private fun updateQuotaIndicator(localSessionCount: Int, max: Int) {
        // The cap the gate enforces, not the server's maxSessions: with the
        // entitlement token lapsed that is demo's (TD-263): Lines.shownCap.
        val used = AccountCache.quotaUsed(activity).coerceAtLeast(localSessionCount)
        if (max <= 0) {
            if (AppRemoteConfig.shouldHintSyncBlocked(activity) && SemperApi.get(activity).enabled) {
                quotaView.isVisible = true
                quotaView.text = activity.getString(R.string.home_sync_config_unavailable)
            } else {
                quotaView.isVisible = false
            }
            return
        }
        // Far from the ceiling the count is noise; it shows once the ceiling is near.
        quotaView.isVisible = nearCeiling(used, max)
        if (!quotaView.isVisible) return
        quotaView.text = activity.resources.getQuantityString(R.plurals.home_quota_fmt, used, used, max)
        quotaView.setTextColor(
            activity.getColor(
                if (used >= max) R.color.semantic_danger else R.color.text_secondary,
            ),
        )
        quotaView.setOnClickListener {
            activity.lifecycleScope.launch { if (!openLimitScreenIfReached()) openSettings() }
        }
    }

    /**
     * Warn that a timed license is running out, or has run out and is inside
     * its grace window.
     *
     * Its own view rather than [quotaView]: a licensed account always has a
     * known quota, so it never reaches that view's unknown-quota hint branch.
     *
     * Advisory only. Entitlement is decided by the backend and arrives as
     * `mode`; this notice is suppressed entirely when the cached config is too
     * old to trust, so a renewal that landed while the device was offline
     * cannot show up here as a false alarm.
     *
     * Informational only ([HomeLicenseNotice]): what the licence does and
     * until when, never a call to renew or buy.
     */
    private fun updateLicenseNotice(lines: Lines) {
        val days = lines.expiryNoticeDays
        if (days == null) {
            licenseView.isVisible = false
            return
        }
        licenseView.isVisible = true
        val notice = HomeLicenseNotice.of(
            inGrace = lines.inGrace,
            days = days,
            graceEndsAtMs = AppRemoteConfig.licenseGraceEndsAtMs(activity),
        )
        licenseView.text = when (notice) {
            is HomeLicenseNotice.Notice.GraceUntil ->
                activity.getString(R.string.license_grace_until_fmt, HomeLicenseNotice.formatDay(notice.endsAtMs))
            HomeLicenseNotice.Notice.Grace -> activity.getString(R.string.license_grace)
            HomeLicenseNotice.Notice.Expired -> activity.getString(R.string.license_expired)
            HomeLicenseNotice.Notice.ExpiresToday -> activity.getString(R.string.license_expiring_today)
            is HomeLicenseNotice.Notice.ExpiresIn -> activity.resources.getQuantityString(
                R.plurals.license_expiring_fmt,
                notice.days,
                notice.days,
            )
        }
        licenseView.setTextColor(
            activity.getColor(
                if (lines.inGrace) {
                    R.color.semantic_danger
                } else {
                    R.color.text_secondary
                },
            ),
        )
    }

    companion object {
        /** The quota line shows from this share of the ceiling, in percent. */
        const val SHOW_FROM_PERCENT = 80

        private const val PERCENT = 100L

        /** True when [used] of [max] analyses is at least [SHOW_FROM_PERCENT] percent of the ceiling. */
        fun nearCeiling(used: Int, max: Int): Boolean =
            max > 0 && used.toLong() * PERCENT >= max.toLong() * SHOW_FROM_PERCENT
    }
}
