package com.sempermechanics.semper.ui.analysis.wizard

import android.app.Activity
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.policy.AppPolicy
import com.sempermechanics.semper.data.session.SessionQuota
import com.sempermechanics.semper.data.session.SessionStore
import com.sempermechanics.semper.navigation.AppIntents
import com.sempermechanics.semper.ui.analysis.StaticAnalysisActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Navigation helpers extracted from [StaticAnalysisActivity] so the limit
 * gates do not keep growing the Activity. A finished run opens its results
 * from its saved row (`BatchRunController`, `ViewerArgs.of`).
 */
object AnalysisNavHelper {

    fun openSessionLimit(host: Activity) {
        host.startActivity(AppIntents.sessionLimit(host))
    }

    fun openSeatRequired(host: Activity) {
        host.startActivity(AppIntents.seatRequired(host))
    }

    fun openUpdateRequired(host: Activity) {
        host.startActivity(AppIntents.updateRequired(host))
    }

    /**
     * Every reason a new analysis may not start, in one call. Every run is a
     * new analysis: a saved one is never written over (owner decision,
     * 2026-10-15), so whatever a run saves is a new Home row.
     *
     * Returns false having already navigated to whichever gate applies. Only
     * starting is checked: a run already under way is never stopped by
     * either gate.
     */
    suspend fun ensureCanStart(host: Activity): Boolean =
        ensureUpToDate(host) && ensureSeat(host) && ensureSessionQuota(host)

    /**
     * Hard stop while the backend requires a newer build
     * ([AppPolicy.newAnalysisAllowed]). Saved analyses stay readable; only
     * starting new work waits for the update.
     */
    fun ensureUpToDate(host: Activity): Boolean {
        if (AppPolicy.newAnalysisAllowed(host)) return true
        openUpdateRequired(host)
        return false
    }

    /**
     * Hard stop when this account holds no floating seat.
     *
     * A **separate** gate from the quota one rather than a widening of it: an
     * institution member is licensed, so [SessionQuota.blocked] is
     * false for them by definition and they would otherwise pass every
     * existing check.
     *
     * Only a floating institution license can fail here. Assigned licenses,
     * individual licenses and demo accounts all return true and fall through
     * to the quota gate, which is what actually limits them.
     */
    fun ensureSeat(host: Activity): Boolean {
        if (!LicenseEntitlements.isSeatRequiredToStart(host)) return true
        openSeatRequired(host)
        return false
    }

    /**
     * Hard stop for a new analysis at the account's limit ([SessionQuota.blocked]).
     * Returns false after navigating to the limit screen; a licensed account
     * with no config yet does not block — analysis is on-device and only its
     * upload is gated (see [com.sempermechanics.semper.data.cloud.CloudSync]).
     * Counts the local session index's rows off the main thread.
     */
    suspend fun ensureSessionQuota(host: Activity): Boolean {
        val localCount = withContext(Dispatchers.IO) {
            SessionStore.list(host).size
        }
        if (!SessionQuota.blocked(host, localCount)) return true
        openSessionLimit(host)
        return false
    }
}
