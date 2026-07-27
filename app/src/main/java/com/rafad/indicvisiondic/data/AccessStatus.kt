package com.rafad.indicvisiondic.data

/**
 * Access-gate status strings returned by [AuthRepository] and cached in
 * [com.rafad.indicvisiondic.data.net.TokenStore]. UI routing lives in
 * [com.rafad.indicvisiondic.ui.auth.AccessRouter].
 */
object AccessStatus {
    /** Backend allow-list says the account may use the app. */
    const val APPROVED = "APPROVED"

    /** Signed in, waiting for an admin to approve. */
    const val PENDING = "PENDING"

    /**
     * No network (or token refresh failed) but this device previously cached
     * [APPROVED] — offline-first bypass for already-approved users.
     */
    const val OFFLINE_CACHE_APPROVED = "OFFLINE_CACHE_APPROVED"
}
