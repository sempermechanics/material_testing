package com.rafad.indicvisiondic.data

import android.content.Context
import com.rafad.indicvisiondic.data.net.IndicApi
import com.rafad.indicvisiondic.data.net.TokenProvider
import com.rafad.indicvisiondic.data.net.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * Authentication + access-gate against the inDIC GCP backend (Cloud Run).
 *
 * Sign-in is Google-only: the app obtains a Google ID token and the backend
 * verifies it (signature, audience, issuer, hosted domain) and enforces the
 * APPROVED allow-list. First sign-in creates a PENDING user server-side; an
 * admin approves it. On the first APPROVED call the device's public key is
 * registered (one-user-one-device binding).
 *
 * Status strings returned:
 *  - "APPROVED"               → route to the app
 *  - "PENDING"                → route to the pending-approval screen
 *  - "OFFLINE_CACHE_APPROVED" → offline but previously approved (offline-first bypass)
 */
class AuthRepository(context: Context) {

    private val appContext = context.applicationContext
    private val api = IndicApi(appContext)

    val cloudConfigured: Boolean get() = api.enabled

    /** Complete a Google sign-in: verify with the backend and resolve access status. */
    suspend fun signInWithGoogle(idToken: String): Result<String> = withContext(Dispatchers.IO) {
        if (!api.enabled) {
            return@withContext Result.failure(Exception("Cloud backend is not configured (INDIC_API_BASE_URL)."))
        }
        TokenStore.saveToken(appContext, idToken)
        resolveStatus(idToken)
    }

    /** Re-check the account status using a currently-valid token (silent refresh if needed). */
    suspend fun refreshStatus(): Result<String> = withContext(Dispatchers.IO) {
        val token = TokenProvider.usableIdToken(appContext)
            ?: return@withContext offlineOrExpired()
        resolveStatus(token)
    }

    fun signOut() = TokenStore.clear(appContext)

    fun cachedEmail(): String? = TokenStore.cachedEmail(appContext)

    fun hasSession(): Boolean = TokenStore.hasSession(appContext)

    // ------------------------------------------------------------------ internal

    private suspend fun resolveStatus(idToken: String): Result<String> {
        return try {
            api.me(idToken) // 200 = APPROVED
            TokenStore.setStatus(appContext, "APPROVED")
            ensureDeviceRegistered(idToken)
            Result.success("APPROVED")
        } catch (e: IndicApi.NotApprovedException) {
            TokenStore.setStatus(appContext, "PENDING")
            Result.success("PENDING")
        } catch (e: IndicApi.DeviceConflictException) {
            // User is approved but the account is bound to another device.
            Result.failure(Exception("This account is locked to a different device. An admin must re-bind it."))
        } catch (e: IndicApi.ApiException) {
            if (e.code == 401) {
                Result.failure(Exception("Session expired. Please sign in again."))
            } else {
                Result.failure(Exception("Could not verify account (server error ${e.code})."))
            }
        } catch (e: IOException) {
            offlineOrExpired()
        }
    }

    private suspend fun ensureDeviceRegistered(idToken: String) {
        if (TokenStore.isDeviceRegistered(appContext)) return
        api.registerDevice(idToken) // throws DeviceConflictException on 409
        TokenStore.setDeviceRegistered(appContext, true)
        Timber.d("Device registered with backend")
    }

    private fun offlineOrExpired(): Result<String> =
        if (TokenStore.cachedStatus(appContext) == "APPROVED") {
            Result.success("OFFLINE_CACHE_APPROVED")
        } else {
            Result.failure(Exception("Could not verify account. Check your connection and sign in again."))
        }
}
