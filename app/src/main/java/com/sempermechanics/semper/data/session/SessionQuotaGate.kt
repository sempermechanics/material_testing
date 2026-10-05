package com.sempermechanics.semper.data.session

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.sempermechanics.semper.data.account.LicenseEntitlements
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.SemperApi
import timber.log.Timber

/**
 * Analysis-quota gate for new local sessions. Every account stops at
 * [LicenseEntitlements.analysisCap]: the backend's ceiling once known, else
 * [LicenseEntitlements.DEMO_MAX_ANALYSES] for demo and none for licensed.
 * [SessionStore.upsert] stays CRUD-only and consults this gate when inserting
 * a new id (unless [allowOverLimit] on the caller).
 */
object SessionQuotaGate {

    /**
     * How the gate reaches the backend client. A JVM test build has no API URL,
     * so the real client reads as disabled; tests swap in `FakeCloudApi` and put
     * the original back.
     */
    @VisibleForTesting
    internal var api: (Context) -> CloudApi = { SemperApi.get(it) }

    /**
     * Whether a *new* session may be persisted.
     *
     * A quota that is *known and full* is a hard stop; an *unknown* quota
     * (max ≤ 0) is not — the analysis is already computed and must be saved
     * locally (upload is separately gated in CloudSync until config arrives).
     * Offline / cloud-disabled accounts always pass.
     *
     * @param existingCount current index size (used as a floor on "used").
     * @return false if the insert must be refused.
     */
    fun allowNewSession(context: Context, existingCount: Int): Boolean {
        if (!api(context).enabled || LicenseEntitlements.hasUnlimitedAnalysis(context)) return true
        val max = LicenseEntitlements.analysisCap(context)
        val used = maxOf(AccountCache.quotaUsed(context), existingCount)
        val full = used >= max
        if (full) {
            AccountCache.refreshSessionLimit(context, existingCount)
            Timber.w("Hard stop: refusing new session (at %d/%d)", used, max)
        }
        return !full
    }
}
