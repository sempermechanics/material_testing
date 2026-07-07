package com.rafad.indicvisiondic.ui

import android.app.Activity
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Native "Sign in with Google" via AndroidX Credential Manager.
 *
 * Returns a Google **ID token** which the backend (Supabase) verifies against
 * the Google Web Client ID. No password or account UI is built here — the
 * system credential sheet handles account selection.
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
}
