package com.sempermechanics.semper.ui.limit

import android.content.Context
import androidx.annotation.StringRes
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.account.SeatLease
import com.sempermechanics.semper.data.account.entitlement.ConfigFetch
import com.sempermechanics.semper.data.net.ActivationDto
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.data.net.TokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a "Refresh licence" tap found, and the one line the user is told. */
enum class LicenseRefreshResult(@StringRes val messageRes: Int) {
    /** The account runs licensed on an institution licence (a roster or floating seat). */
    LICENSED_INSTITUTION(R.string.license_refresh_institution),

    /** The account runs licensed on its own licence. */
    LICENSED_INDIVIDUAL(R.string.license_refresh_individual),

    /** The backend answered, and the account is on the demo. */
    DEMO(R.string.license_refresh_demo),

    /** The account holds a licence for this app, and this phone is not activated on it (ADR-033). */
    NOT_ACTIVATED(R.string.license_refresh_not_activated),

    /** The account holds a licence, and every phone it covers is in use. */
    PHONES_FULL(R.string.license_refresh_phones_full),

    /** A new licence is waiting for this phone's Activate. */
    UPGRADE_READY(R.string.license_refresh_upgrade_ready),

    /** No answer; the one-off refresh is queued and retries in the background. */
    UNREACHABLE(R.string.license_refresh_unreachable),
}

/**
 * The account's licence as the screens read it, before and after a refresh:
 * when the two differ, Settings rebuilds and the limit screen checks again.
 */
data class LicenseState(
    val licensed: Boolean,
    val kind: String,
    val backup: Boolean,
    val share: Boolean,
    val cap: Int,
    /** `activation.state` ([com.sempermechanics.semper.data.net.ActivationDto]). */
    val activation: String = "",
    /** A new licence is waiting for Activate. */
    val upgrade: Boolean = false,
)

/**
 * "Refresh licence" (Settings' account section, the analysis-limit screen):
 * the account is the anchor, and a seat, licence or claim code the server
 * attached arrives in `/v1/config` and its signed token. This asks for it now
 * instead of at the next periodic refresh. Nothing here sells, links or asks
 * for a key: the app only consumes what the server grants.
 */
object LicenseRefresh {

    /** Wire value of `licenseKind` for an institution licence. */
    private const val KIND_INSTITUTION = "institution"

    /** How one refresh went: what to say, and whether the licence changed. */
    data class Outcome(val result: LicenseRefreshResult, val changed: Boolean)

    /**
     * Fetch `/v1/config` the way a seat checkout does ([SeatLease.fetchConfig],
     * which stores the answer and its entitlement token). When it does not
     * answer, queue the retrying one-off ([LicenseConfigWorker.refreshNow]).
     */
    suspend fun run(
        context: Context,
        api: CloudApi = SemperApi.get(context),
        tokens: TokenSource = TokenProvider,
    ): Outcome = withContext(Dispatchers.IO) {
        // Off the main thread: storing the answer seals the entitlement record
        // with commit() and reads the Keystore, as LicenseConfigWorker does.
        val before = state(context)
        val fetch = SeatLease.fetchConfig(context, api, tokens)
        if (queuesRetry(fetch)) LicenseConfigWorker.refreshNow(context)
        val after = state(context)
        Outcome(resultOf(fetch, after), changed = before != after)
    }

    /** What the screens read of the licence now. */
    fun state(context: Context): LicenseState = LicenseState(
        licensed = LicenseEntitlements.isLicensed(context),
        kind = LicenseEntitlements.licenseKind(context),
        backup = LicenseEntitlements.cloudBackupEnabled(context),
        share = LicenseEntitlements.shareEnabled(context),
        cap = LicenseEntitlements.analysisCap(context),
        activation = AppRemoteConfig.activation(context).state,
        upgrade = AppRemoteConfig.pendingLicence(context) != null,
    )

    /** Whether a fetch that went [fetch] leaves the retrying one-off to try again. */
    fun queuesRetry(fetch: ConfigFetch): Boolean = fetch != ConfigFetch.ANSWERED

    /**
     * The line for a fetch that went [fetch], with the licence read [after] it.
     * No answer (a failure, or no call possible yet) is "couldn't reach", never
     * "still on the demo": the cached answer may be stale. A new licence
     * waiting comes first, then a licence this phone is not activated on
     * (ADR-033), which is Demo here until Activate.
     */
    fun resultOf(fetch: ConfigFetch, after: LicenseState): LicenseRefreshResult = when {
        fetch != ConfigFetch.ANSWERED -> LicenseRefreshResult.UNREACHABLE
        after.upgrade -> LicenseRefreshResult.UPGRADE_READY
        !after.licensed && after.activation == ActivationDto.STATE_AVAILABLE -> LicenseRefreshResult.NOT_ACTIVATED
        !after.licensed && after.activation == ActivationDto.STATE_FULL -> LicenseRefreshResult.PHONES_FULL
        !after.licensed -> LicenseRefreshResult.DEMO
        after.kind == KIND_INSTITUTION -> LicenseRefreshResult.LICENSED_INSTITUTION
        else -> LicenseRefreshResult.LICENSED_INDIVIDUAL
    }
}
