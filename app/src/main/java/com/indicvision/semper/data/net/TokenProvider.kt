package com.indicvision.semper.data.net

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * Single source of a currently-valid **Firebase** ID token.
 *
 * Firebase manages the session and auto-refreshes the ID token, so background
 * work (uploads, reconcile) can always get a fresh token with no UI — which
 * fixes the silent-refresh pain the raw Google-token flow had. Returns null
 * only when nobody is signed in.
 */
object TokenProvider {

    suspend fun usableIdToken(): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return try {
            // getIdToken(false) returns the cached token, refreshing it if within
            // ~5 min of expiry — handled by the Firebase SDK.
            user.getIdToken(false).await().token
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Could not get Firebase ID token")
            null
        }
    }
}
