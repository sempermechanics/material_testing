package com.rafad.indicvisiondic.data.net

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import timber.log.Timber

/**
 * Lightweight session cache for the GCP backend: the current Google ID token
 * (short-lived, ~1 h) plus identity/status parsed from it.
 *
 * No refresh tokens are stored (a stated security requirement) — when the ID
 * token expires the app re-obtains one from Google Identity, silently when
 * possible. The token is a short-lived bearer credential; it lives in app-
 * private SharedPreferences.
 */
object TokenStore {

    private const val PREFS = "indic_session"
    private const val K_ID_TOKEN = "id_token"
    private const val K_UID = "uid"
    private const val K_EMAIL = "email"
    private const val K_STATUS = "last_status" // last server-confirmed access_status
    private const val K_ROLE = "role"          // "admin" | "user"
    private const val K_DEVICE_REGISTERED = "device_registered"

    // Refresh a little before the hard expiry so a request in flight doesn't 401.
    private const val EXPIRY_SKEW_SEC = 120L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Persist a freshly obtained Google ID token and the identity it carries. */
    fun saveToken(context: Context, idToken: String) {
        val claims = decodeJwtClaims(idToken)
        prefs(context).edit()
            .putString(K_ID_TOKEN, idToken)
            .putString(K_UID, claims?.optString("sub"))
            .putString(K_EMAIL, claims?.optString("email"))
            .apply()
    }

    /** The stored ID token if still valid (with skew), else null. */
    fun validIdToken(context: Context): String? {
        val token = prefs(context).getString(K_ID_TOKEN, null) ?: return null
        val exp = decodeJwtClaims(token)?.optLong("exp", 0L) ?: 0L
        val now = System.currentTimeMillis() / 1000L
        return if (exp - EXPIRY_SKEW_SEC > now) token else null
    }

    fun cachedUid(context: Context): String? = prefs(context).getString(K_UID, null)
    fun cachedEmail(context: Context): String? = prefs(context).getString(K_EMAIL, null)

    fun cachedStatus(context: Context): String? = prefs(context).getString(K_STATUS, null)
    fun setStatus(context: Context, status: String) =
        prefs(context).edit().putString(K_STATUS, status).apply()

    fun cachedRole(context: Context): String? = prefs(context).getString(K_ROLE, null)
    fun setRole(context: Context, role: String?) =
        prefs(context).edit().putString(K_ROLE, role).apply()

    fun isAdmin(context: Context): Boolean = cachedRole(context) == "admin"

    fun isDeviceRegistered(context: Context): Boolean =
        prefs(context).getBoolean(K_DEVICE_REGISTERED, false)
    fun setDeviceRegistered(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(K_DEVICE_REGISTERED, v).apply()

    fun hasSession(context: Context): Boolean =
        prefs(context).getString(K_ID_TOKEN, null) != null

    /** Wipe the local session (sign-out). Keystore device key is left intact. */
    fun clear(context: Context) = prefs(context).edit().clear().apply()

    /** Decode the (unverified) payload of a JWT. Verification happens server-side. */
    private fun decodeJwtClaims(jwt: String): JSONObject? = try {
        val payload = jwt.split(".").getOrNull(1) ?: return null
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        JSONObject(json)
    } catch (e: Exception) {
        Timber.w(e, "Could not decode ID token claims")
        null
    }
}
