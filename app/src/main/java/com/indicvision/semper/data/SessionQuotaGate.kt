package com.indicvision.semper.data

import android.content.Context
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenStore
import timber.log.Timber

/**
 * Analysis-quota gate for new local sessions. Cloud-backed accounts use the
 * server quota; [SessionStore.upsert] stays CRUD-only and consults this gate
 * when inserting a new id (unless [allowOverLimit] on the caller).
 */
object SessionQuotaGate {

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
    @Suppress("ReturnCount") // early-outs for disabled / unknown / full / allow
    fun allowNewSession(context: Context, existingCount: Int): Boolean {
        if (!IndicApi.get(context).enabled) return true
        val max = TokenStore.quotaMax(context)
        if (max <= 0) return true
        val used = maxOf(TokenStore.quotaUsed(context), existingCount)
        if (used >= max) {
            // Persist the used count; isSessionLimitReached recomputes
            // the hard stop live (used >= max) from this.
            TokenStore.setQuota(context, used, existingCount)
            Timber.w("Hard stop: refusing new session (at %d/%d)", used, max)
            return false
        }
        return true
    }
}
