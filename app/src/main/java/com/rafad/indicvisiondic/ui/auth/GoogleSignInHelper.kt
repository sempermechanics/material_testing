package com.rafad.indicvisiondic.ui.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import timber.log.Timber

/**
 * Native "Sign in with Google" via AndroidX Credential Manager.
 *
 * Returns a Google **ID token** which the inDIC backend (Cloud Run) verifies
 * against the Google Web Client ID (signature, audience, issuer, hosted domain).
 * No password or account UI is built here — the system credential sheet handles
 * account selection.
 *
 * Requires:
 *  - GOOGLE_WEB_CLIENT_ID (Web OAuth client) — see docs/GOOGLE_SSO_SETUP.md
 *  - The app's signing SHA-1 registered on an Android OAuth client in the
 *    same Google Cloud project.
 *  - Google Play services on the device (Google-API emulator image or real device).
 */
object GoogleSignInHelper {

    class NotConfigured : Exception("Google sign-in is not configured (missing GOOGLE_WEB_CLIENT_ID).")

    /**
     * Launches the credential sheet and returns the Google ID token.
     * Throws [NotConfigured] if no web client id, or a
     * `GetCredentialException` if the user cancels / no credential is available.
     */
    suspend fun getIdToken(activity: Activity, webClientId: String): String {
        if (webClientId.isBlank()) throw NotConfigured()

        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = CredentialManager.create(activity).getCredential(activity, request)
        val credential = response.credential
        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        return googleCredential.idToken
    }

    /**
     * Best-effort **silent** ID-token refresh (no UI). Used to re-obtain a token
     * for background uploads once the previous one expires. Returns null if a
     * fresh token can't be issued without user interaction — the caller then
     * defers (WorkManager retry) until the app is next foregrounded.
     */
    suspend fun getIdTokenSilent(context: Context, webClientId: String): String? {
        if (webClientId.isBlank()) return null
        return try {
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(true)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(context).getCredential(context, request)
            GoogleIdTokenCredential.createFrom(response.credential.data).idToken
        } catch (e: Exception) {
            Timber.d("Silent Google token refresh unavailable: ${e.message}")
            null
        }
    }
}
