@file:SuppressLint("DiscouragedApi")

package com.indicvision.semper.ui.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Native "Sign in with Google" via AndroidX Credential Manager.
 *
 * Returns a Google **ID token** which the app then exchanges for a Firebase
 * credential (see [com.indicvision.semper.data.AuthRepository.signInWithGoogle]).
 * The server client id is the **Firebase project's** web client id, published by
 * the google-services plugin as the `default_web_client_id` string resource — it
 * only exists once the app's SHA-1 is registered on the Firebase Android app, so
 * it's resolved at runtime and Google sign-in is simply unavailable until then.
 */
object GoogleSignInHelper {

    class NotConfigured :
        Exception(
            "Google sign-in isn't configured: add your app's SHA-1 to the Firebase project " +
                "and re-download google-services.json.",
        )

    /** The Firebase web client id, or null if the SHA-1/OAuth client isn't set up yet. */
    fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id != 0) context.getString(id) else null
    }

    fun isConfigured(context: Context): Boolean = !webClientId(context).isNullOrBlank()

    /**
     * Launches the credential sheet and returns the Google ID token.
     * Throws [NotConfigured] if Google isn't set up, or a `GetCredentialException`
     * if the user cancels / no credential is available.
     */
    suspend fun getIdToken(activity: Activity): String {
        val webClientId = webClientId(activity) ?: throw NotConfigured()

        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val response = CredentialManager.create(activity).getCredential(activity, request)
        val credential = response.credential
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
}
