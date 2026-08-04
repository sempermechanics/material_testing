// Token/claims store: one small accessor per stored field, so TooManyFunctions
// is suppressed for this whole file.
@file:Suppress("TooManyFunctions")

package com.indicvision.semper.data.net

import android.content.Context
import androidx.core.content.edit

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
    private const val K_LIMIT_FORCED = "session_limit_forced"
    private const val K_BETA_ACKED_PREFIX = "beta_notice_acked_"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun onboardingPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(ONBOARDING_PREFS, Context.MODE_PRIVATE)

    /** Cache the signed-in identity (from the Firebase user) for offline UI. */
    fun saveIdentity(context: Context, uid: String?, email: String?) {
        prefs(context).edit {
            putString(K_UID, uid)
            putString(K_EMAIL, email)
        }
    }

    fun cachedUid(context: Context): String? = prefs(context).getString(K_UID, null)
    fun cachedEmail(context: Context): String? = prefs(context).getString(K_EMAIL, null)

    fun cachedStatus(context: Context): String? = prefs(context).getString(K_STATUS, null)
    fun setStatus(context: Context, status: String) = prefs(context).edit { putString(K_STATUS, status) }

    fun cachedRole(context: Context): String? = prefs(context).getString(K_ROLE, null)
    fun setRole(context: Context, role: String?) = prefs(context).edit { putString(K_ROLE, role) }

    fun isAdmin(context: Context): Boolean = cachedRole(context) == "admin"

    fun isDeviceRegistered(context: Context): Boolean = prefs(context).getBoolean(K_DEVICE_REGISTERED, false)
    fun setDeviceRegistered(context: Context, v: Boolean) {
        prefs(context).edit { putBoolean(K_DEVICE_REGISTERED, v) }
    }

    // ── Cloud analysis quota (max sessions per account) ──────────────────
    // The ceiling is owned by [AppRemoteConfig] (from /v1/config); TokenStore
    // holds only the runtime USED count and a forced-stop flag and reads the
    // ceiling from there. One dependency direction (TokenStore → AppRemoteConfig),
    // no cycle. The hard stop is computed live, so a changed ceiling takes effect
    // without any write-back from AppRemoteConfig.

    fun quotaUsed(context: Context): Int = prefs(context).getInt(K_QUOTA_USED, 0)

    /** Session ceiling, owned by [AppRemoteConfig]; 0 until the backend reports it. */
    fun quotaMax(context: Context): Int = AppRemoteConfig.maxSessions(context)

    /** True when the backend has reported a positive quota ceiling. */
    fun isQuotaKnown(context: Context): Boolean = quotaMax(context) > 0

    /**
     * Cached cloud max, or 0 when the backend has not reported one yet.
     * Callers must not invent a local default — use [isQuotaKnown] / fail closed.
     */
    fun effectiveQuotaMax(context: Context): Int = quotaMax(context)

    /**
     * Refresh the cached USED count. [localCount] is folded in so the client
     * blocks new analyses even before the next cloud reconcile. Fresh numbers
     * clear any forced stop; the hard stop itself is recomputed live in
     * [isSessionLimitReached] from used vs the [AppRemoteConfig] ceiling.
     */
    fun setQuota(context: Context, used: Int, localCount: Int = 0) {
        val effectiveUsed = maxOf(used, localCount)
        prefs(context).edit {
            putInt(K_QUOTA_USED, effectiveUsed)
            putBoolean(K_LIMIT_FORCED, false)
        }
    }

    /** Refresh the cached USED count from the local session count (+ cached cloud used). */
    fun refreshSessionLimit(context: Context, localCount: Int) =
        setQuota(context, quotaUsed(context), localCount)

    /** Force the hard stop (e.g. an upload rejected 409 without fresh numbers). */
    fun setSessionLimitReached(context: Context, v: Boolean) {
        prefs(context).edit { putBoolean(K_LIMIT_FORCED, v) }
    }

    /**
     * True when the account may not create another analysis (hard stop): either a
     * forced stop is set, or the ceiling is known and the used count has reached
     * it. An unknown ceiling is never a hard stop — analysis is on-device; only
     * its upload is gated (see [com.indicvision.semper.data.CloudSync]).
     */
    fun isSessionLimitReached(context: Context): Boolean {
        if (prefs(context).getBoolean(K_LIMIT_FORCED, false)) return true
        val max = quotaMax(context)
        return max > 0 && quotaUsed(context) >= max
    }

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
        onboardingPrefs(context).edit { putBoolean(K_BETA_ACKED_PREFIX + uid, true) }
    }

    /** Wipe the local session cache (sign-out). Keystore device key is left intact. */
    fun clear(context: Context) {
        prefs(context).edit { clear() }
        AppRemoteConfig.clear(context)
    }
}
