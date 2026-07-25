package com.rafad.indicvisiondic.data

import android.content.Context
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import com.rafad.indicvisiondic.data.net.IndicApi
import com.rafad.indicvisiondic.data.net.TokenProvider
import com.rafad.indicvisiondic.data.net.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException

/**
 * Authentication + access-gate.
 *
 * Identity is federated through **Firebase Auth** — Google, email/password, or
 * a passwordless email link — so external collaborators on any email provider
 * can sign in, not just Google accounts. After a Firebase sign-in the backend
 * verifies the Firebase ID token, enforces the APPROVED allow-list, and (on the
 * first approved call) registers the device key.
 *
 * Status strings returned:
 *  - "APPROVED"               → route to the app
 *  - "PENDING"                → route to the pending-approval screen
 *  - "OFFLINE_CACHE_APPROVED" → offline but previously approved (offline-first bypass)
 */
class AuthRepository(context: Context) {

    private val appContext = context.applicationContext
    private val api = IndicApi(appContext)
    private val auth = FirebaseAuth.getInstance()

    val cloudConfigured: Boolean get() = api.enabled

    /** Google sign-in: exchange the Google ID token for a Firebase credential. */
    suspend fun signInWithGoogle(googleIdToken: String): Result<String> = firebaseThen {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null)).await()
    }

    /** Existing account: email + password. */
    suspend fun signInWithPassword(email: String, password: String): Result<String> = firebaseThen {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    /**
     * New account: email + password. Fires a verification email — until the user
     * verifies, the backend keeps a domain user PENDING (see AUTO_APPROVE_HD).
     */
    suspend fun signUpWithPassword(email: String, password: String): Result<String> = firebaseThen {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        runCatching { result.user?.sendEmailVerification()?.await() }
            .onFailure { Timber.w(it, "Could not send verification email") }
        result
    }

    /**
     * Passwordless: email the user a sign-in link. The email is remembered so
     * [completeEmailLink] can finish when the link is tapped. Requires the
     * continue-URL domain to be authorized in Firebase and handled as an App
     * Link (see docs).
     */
    suspend fun sendSignInLink(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val clean = email.trim()
        val settings = ActionCodeSettings.newBuilder()
            .setUrl(EMAIL_LINK_CONTINUE_URL)
            .setHandleCodeInApp(true)
            .setAndroidPackageName(appContext.packageName, true, null)
            .build()
        try {
            auth.sendSignInLinkToEmail(clean, settings).await()
            linkPrefs().edit().putString(K_PENDING_EMAIL, clean).apply()
            Result.success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Could not send sign-in link")
            Result.failure(Exception(e.message ?: "Could not send the sign-in link."))
        }
    }

    /**
     * Password recovery: email a reset link. This is a pure Firebase identity
     * operation — it does not sign in and does not touch the backend, so it
     * works whether or not the cloud is configured. The user follows the link,
     * sets a new password, then returns here to sign in.
     *
     * A missing account is reported as success on purpose: surfacing "no account
     * for this email" here would let anyone probe which emails are registered.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            auth.sendPasswordResetEmail(email.trim()).await()
            Result.success(Unit)
        } catch (e: FirebaseAuthInvalidUserException) {
            Result.success(Unit) // don't reveal whether the email is registered
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Could not send password reset")
            Result.failure(Exception(e.message ?: "Could not send the reset email."))
        }
    }

    /** True if [link] is a Firebase email sign-in link. */
    fun isEmailSignInLink(link: String): Boolean = auth.isSignInWithEmailLink(link)

    /** The email a link was last sent to (needed to complete the sign-in). */
    fun pendingLinkEmail(): String? = linkPrefs().getString(K_PENDING_EMAIL, null)

    /** Finish a passwordless email-link sign-in from the tapped link. */
    suspend fun completeEmailLink(email: String, link: String): Result<String> {
        val result = firebaseThen { auth.signInWithEmailLink(email.trim(), link).await() }
        if (result.isSuccess) linkPrefs().edit().remove(K_PENDING_EMAIL).apply()
        return result
    }

    private fun linkPrefs() = appContext.getSharedPreferences("indic_emaillink", Context.MODE_PRIVATE)

    /** Re-check the account status for the currently signed-in Firebase user. */
    suspend fun refreshStatus(): Result<String> = withContext(Dispatchers.IO) {
        if (auth.currentUser == null) {
            return@withContext Result.failure(Exception("Not signed in."))
        }
        resolveStatus()
    }

    fun signOut() {
        auth.signOut()
        TokenStore.clear(appContext)
    }

    fun cachedEmail(): String? = auth.currentUser?.email ?: TokenStore.cachedEmail(appContext)

    fun hasSession(): Boolean = auth.currentUser != null

    // ------------------------------------------------------------------ internal

    /** Run a Firebase sign-in, cache identity, then resolve backend access status. */
    private suspend fun firebaseThen(signIn: suspend () -> Any?): Result<String> = withContext(Dispatchers.IO) {
        if (!api.enabled) {
            return@withContext Result.failure(
                Exception("Cloud backend is not configured (INDIC_API_BASE_URL)."),
            )
        }
        try {
            signIn()
        } catch (e: FirebaseAuthWeakPasswordException) {
            return@withContext Result.failure(Exception("Password is too weak (min 6 characters)."))
        } catch (e: FirebaseAuthUserCollisionException) {
            return@withContext Result.failure(Exception("An account already exists for this email. Sign in instead."))
        } catch (e: FirebaseAuthInvalidUserException) {
            return@withContext Result.failure(Exception("No account for this email."))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            return@withContext Result.failure(Exception("Incorrect email or password."))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Firebase sign-in failed")
            return@withContext Result.failure(Exception(e.message ?: "Sign-in failed."))
        }
        val user = auth.currentUser
            ?: return@withContext Result.failure(Exception("Sign-in did not complete."))
        TokenStore.saveIdentity(appContext, user.uid, user.email)
        resolveStatus()
    }

    private suspend fun resolveStatus(): Result<String> {
        val token = TokenProvider.usableIdToken() ?: return offlineOrExpired()
        return try {
            val me = api.me(token) // 200 = APPROVED
            TokenStore.setStatus(appContext, "APPROVED")
            TokenStore.setRole(appContext, me.role ?: "user")
            ensureDeviceRegistered(token)
            Result.success("APPROVED")
        } catch (e: IndicApi.NotApprovedException) {
            TokenStore.setStatus(appContext, "PENDING")
            Result.success("PENDING")
        } catch (e: IndicApi.DeviceConflictException) {
            Result.failure(
                Exception(
                    "This device is already linked to another account, or this account to " +
                        "another device. Sign in with that account, or ask an admin to reset the binding.",
                ),
            )
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

    private fun offlineOrExpired(): Result<String> = if (TokenStore.cachedStatus(appContext) == "APPROVED") {
        Result.success("OFFLINE_CACHE_APPROVED")
    } else {
        Result.failure(Exception("Could not verify account. Check your connection and sign in again."))
    }

    private companion object {
        const val K_PENDING_EMAIL = "pending_email"

        // Where the email link returns to. Must be an Authorized Domain in the
        // Firebase project and handled as an App Link by this app (see docs).
        // Keep in sync with the backend's FIREBASE_PROJECT_ID — this is that
        // project's default hosting domain.
        const val EMAIL_LINK_CONTINUE_URL = "https://indicvision-dic-app-auth.firebaseapp.com/finishSignIn"
    }
}
