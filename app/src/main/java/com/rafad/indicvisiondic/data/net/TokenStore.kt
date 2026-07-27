// Token/claims store: one small accessor per stored field, so TooManyFunctions
// is suppressed for this whole file.
@file:Suppress("TooManyFunctions")

package com.rafad.indicvisiondic.data.net

import android.content.Context

/**
 * Local session cache alongside Firebase Auth: the signed-in identity plus the
 * backend-confirmed role/status/quota.
 *
 * Firebase owns the actual credential (the ID token, auto-refreshed) — we no
 * longer store any token here. This holds only the cached identity and the
 * app-layer state the backend tells us (approval status, admin role, quota).
 */
object TokenStore {

    private const val PREFS = "indic_session"

    /** Survives sign-out so a per-account beta ack is not re-prompted on every login. */
    private const val ONBOARDING_PREFS = "indic_onboarding"
    private const val K_UID = "uid"
    private const val K_EMAIL = "email"
    private const val K_STATUS = "last_status" // last server-confirmed access_status
    private const val K_ROLE = "role" // "admin" | "user"
    private const val K_DEVICE_REGISTERED = "device_registered"
    private const val K_QUOTA_USED = "quota_used"
    private const val K_QUOTA_MAX = "quota_max"
    private const val K_LIMIT_REACHED = "session_limit_reached"
    private const val K_BETA_ACKED_PREFIX = "beta_notice_acked_"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun onboardingPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)

    /** Cache the signed-in identity (from the Firebase user) for offline UI. */
    fun saveIdentity(context: Context, uid: String?, email: String?) {
        prefs(context).edit()
            .putString(K_UID, uid)
            .putString(K_EMAIL, email)
            .apply()
    }

    fun cachedUid(context: Context): String? = prefs(context).getString(K_UID, null)
    fun cachedEmail(context: Context): String? = prefs(context).getString(K_EMAIL, null)

    fun cachedStatus(context: Context): String? = prefs(context).getString(K_STATUS, null)
    fun setStatus(context: Context, status: String) = prefs(context).edit().putString(K_STATUS, status).apply()

    fun cachedRole(context: Context): String? = prefs(context).getString(K_ROLE, null)
    fun setRole(context: Context, role: String?) = prefs(context).edit().putString(K_ROLE, role).apply()

    fun isAdmin(context: Context): Boolean = cachedRole(context) == "admin"

    fun isDeviceRegistered(context: Context): Boolean = prefs(context).getBoolean(K_DEVICE_REGISTERED, false)
    fun setDeviceRegistered(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(K_DEVICE_REGISTERED, v).apply()
    }

    // ── Cloud analysis quota (max sessions per account) ──────────────────
    /**
     * Client hard-stop default when the backend hasn't reported a max yet.
     * Keep in sync with backend `MAX_SESSIONS_PER_USER` default.
     */
    const val DEFAULT_MAX_SESSIONS = 4

    fun quotaUsed(context: Context): Int = prefs(context).getInt(K_QUOTA_USED, 0)
    fun quotaMax(context: Context): Int = prefs(context).getInt(K_QUOTA_MAX, 0)

    /** Backend max if known, otherwise [DEFAULT_MAX_SESSIONS]. */
    fun effectiveQuotaMax(context: Context): Int {
        val max = quotaMax(context)
        return if (max > 0) max else DEFAULT_MAX_SESSIONS
    }

    /**
     * Update stored quota and the hard-stop flag. [localCount] is folded in so
     * the client blocks new analyses even before the next cloud reconcile.
     */
    fun setQuota(context: Context, used: Int, max: Int, localCount: Int = 0) {
        val effectiveMax = if (max > 0) max else DEFAULT_MAX_SESSIONS
        val effectiveUsed = maxOf(used, localCount)
        prefs(context).edit()
            .putInt(K_QUOTA_USED, effectiveUsed)
            .putInt(K_QUOTA_MAX, effectiveMax)
            .putBoolean(K_LIMIT_REACHED, effectiveUsed >= effectiveMax)
            .apply()
    }

    /** Recompute the hard-stop flag from local session count (+ cached cloud used). */
    fun refreshSessionLimit(context: Context, localCount: Int) {
        setQuota(context, quotaUsed(context), effectiveQuotaMax(context), localCount)
    }

    /** Force the limit flag (e.g. an upload rejected 409 without fresh numbers). */
    fun setSessionLimitReached(context: Context, v: Boolean) {
        prefs(context).edit().putBoolean(K_LIMIT_REACHED, v).apply()
    }

    /** True when the account may not create another analysis (hard stop). */
    fun isSessionLimitReached(context: Context): Boolean = prefs(context).getBoolean(K_LIMIT_REACHED, false)

    /**
     * Whether the current account has acknowledged the beta / data-use notice.
     * Stored outside the session prefs so it survives [clear].
     */
    fun hasAckedBetaNotice(context: Context): Boolean {
        val uid = cachedUid(context) ?: return false
        return onboardingPrefs(context).getBoolean(K_BETA_ACKED_PREFIX + uid, false)
    }

    fun setBetaNoticeAcked(context: Context) {
        val uid = cachedUid(context) ?: return
        onboardingPrefs(context).edit().putBoolean(K_BETA_ACKED_PREFIX + uid, true).apply()
    }

    /** Wipe the local session cache (sign-out). Keystore device key is left intact. */
    fun clear(context: Context) = prefs(context).edit().clear().apply()
}
