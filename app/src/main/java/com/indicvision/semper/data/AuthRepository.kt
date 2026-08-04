package com.indicvision.semper.data

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.data.net.TokenStore
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
 * Status strings returned — see [AccessStatus]:
 *  - [AccessStatus.APPROVED]               → route to the app
 *  - [AccessStatus.PENDING]                → route to the pending-approval screen
 *  - [AccessStatus.OFFLINE_CACHE_APPROVED] → offline but previously approved
 */
@Suppress("TooManyFunctions") // one method per auth action (sign-in variants, reset, status, session)
class AuthRepository(context: Context) {

    private val appContext = context.applicationContext
    private val api = IndicApi.get(appContext)
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
     * New account: email + password. Fires a verification email and stops there —
     * [firebaseThen] blocks the session until the address is confirmed, so a new
     * account never reaches the backend before its owner has proved the mailbox
     * is theirs.
     */
    suspend fun signUpWithPassword(email: String, password: String): Result<String> = firebaseThen {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        runCatching { result.user?.sendEmailVerification()?.await() }
            .onFailure { Timber.w(it, "Could not send verification email") }
        result
    }

    /**
     * Prove the session still belongs to whoever is holding the phone, so
     * destructive identity operations cannot ride an old sign-in. Firebase
     * requires this within minutes of the action for [deleteIdentity].
     */
    suspend fun reauthenticateWithPassword(password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.failure(Exception("Not signed in."))
        val email = user.email ?: return@withContext Result.failure(Exception("This account has no email."))
        try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
            Result.success(Unit)
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            Result.failure(Exception("Incorrect password.", e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Re-authentication failed")
            Result.failure(Exception(e.message ?: "Could not verify your identity."))
        }
    }

    /** As [reauthenticateWithPassword], for accounts that sign in with Google. */
    suspend fun reauthenticateWithGoogle(googleIdToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.failure(Exception("Not signed in."))
        try {
            user.reauthenticate(GoogleAuthProvider.getCredential(googleIdToken, null)).await()
            Result.success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Google re-authentication failed")
            Result.failure(Exception(e.message ?: "Could not verify your identity."))
        }
    }

    /**
     * As [reauthenticateWithPassword], for accounts that only ever sign in with
     * an emailed link. Without this such an account could not be deleted at all:
     * it has no password to type and no Google credential to present.
     */
    suspend fun reauthenticateWithEmailLink(link: String): Result<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.failure(Exception("Not signed in."))
        val email = user.email ?: return@withContext Result.failure(Exception("This account has no email."))
        try {
            user.reauthenticate(EmailAuthProvider.getCredentialWithLink(email, link)).await()
            Result.success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Email-link re-authentication failed")
            Result.failure(Exception(e.message ?: "Could not verify your identity."))
        }
    }

    /**
     * Erase the Firebase identity itself. Only succeeds soon after a
     * re-authentication, which is why the delete flow asks for one first.
     */
    suspend fun deleteIdentity(): Result<Unit> = withContext(Dispatchers.IO) {
        val user = auth.currentUser ?: return@withContext Result.success(Unit)
        try {
            user.delete().await()
            Result.success(Unit)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Firebase identity delete failed")
            Result.failure(e)
        }
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
            linkPrefs().edit { putString(K_PENDING_EMAIL, clean) }
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
            Timber.d(e, "Password reset for an unregistered email (existence not revealed)")
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
        if (result.isSuccess) linkPrefs().edit { remove(K_PENDING_EMAIL) }
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
            return@withContext Result.failure(Exception("Password is too weak (min 6 characters).", e))
        } catch (e: FirebaseAuthUserCollisionException) {
            return@withContext Result.failure(
                Exception("An account already exists for this email. Sign in instead.", e),
            )
        } catch (e: FirebaseAuthInvalidUserException) {
            return@withContext Result.failure(Exception("No account for this email.", e))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            return@withContext Result.failure(Exception("Incorrect email or password.", e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Firebase sign-in failed")
            return@withContext Result.failure(Exception(e.message ?: "Sign-in failed."))
        }
        val user = auth.currentUser
            ?: return@withContext Result.failure(Exception("Sign-in did not complete."))
        unverifiedEmailError(user)?.let { return@withContext Result.failure(it) }
        TokenStore.saveIdentity(appContext, user.uid, user.email)
        resolveStatus()
    }

    /**
     * Password accounts must confirm their address before the session counts.
     * Google users and email-link users arrive already verified, so this only
     * bites the email + password path.
     *
     * On a block the session is torn down again — an unverified user is never
     * left half signed-in — and a fresh link is sent so the mail they need is
     * always the most recent one.
     */
    private suspend fun unverifiedEmailError(user: FirebaseUser): Exception? {
        if (!needsEmailVerification(user)) return null
        runCatching { auth.currentUser?.sendEmailVerification()?.await() }
            .onFailure { Timber.w(it, "Could not re-send verification email") }
        val email = user.email.orEmpty()
        signOut()
        return EmailVerificationRequired(email)
    }

    /**
     * The account exists and its verification mail has just gone out, but the
     * address is not confirmed yet, so there is no session.
     *
     * A distinct type rather than a message: the sign-in screen switches itself
     * back out of "create account" mode on this outcome, and deciding that by
     * matching an error string would break the first time the wording changed.
     */
    class EmailVerificationRequired(val email: String) :
        Exception(
            "Verify your email first. We've sent a link to $email — open it, then sign in again.",
        )

    /** True when this account signs in with a password and has not confirmed its address. */
    private suspend fun needsEmailVerification(user: FirebaseUser): Boolean {
        if (user.providerData.none { it.providerId == EmailAuthProvider.PROVIDER_ID }) return false
        // Someone who just clicked the link in a browser is still unverified in
        // this cached user object; reload before judging them.
        runCatching { user.reload().await() }
            .onFailure { Timber.d(it, "Could not refresh verification state; using cached value") }
        return auth.currentUser?.isEmailVerified == false
    }

    private suspend fun resolveStatus(): Result<String> {
        val token = TokenProvider.usableIdToken() ?: return offlineOrExpired()
        return try {
            val me = api.me(token) // 200 = APPROVED
            TokenStore.setStatus(appContext, AccessStatus.APPROVED)
            TokenStore.setRole(appContext, me.role ?: "user")
            runCatching { api.getConfig(token) }
                .onSuccess { AppRemoteConfig.apply(appContext, it) }
                .onFailure {
                    AppRemoteConfig.recordFetchFailure(appContext)
                    Timber.d(it, "Could not fetch app remote config")
                }
            ensureDeviceRegistered(token)
            Result.success(AccessStatus.APPROVED)
        } catch (e: IndicApi.NotApprovedException) {
            Timber.d(e, "Account is pending approval")
            TokenStore.setStatus(appContext, AccessStatus.PENDING)
            Result.success(AccessStatus.PENDING)
        } catch (e: IndicApi.DeviceConflictException) {
            Result.failure(
                Exception(
                    "This device is already linked to another account, or this account to " +
                        "another device. Sign in with that account, or ask an admin to reset the binding.",
                    e,
                ),
            )
        } catch (e: IndicApi.ApiException) {
            if (e.code == HTTP_UNAUTHORIZED) {
                Result.failure(Exception("Session expired. Please sign in again."))
            } else {
                Result.failure(Exception("Could not verify account (server error ${e.code})."))
            }
        } catch (e: IOException) {
            Timber.d(e, "Status check failed offline; using cached status")
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
        if (TokenStore.cachedStatus(appContext) == AccessStatus.APPROVED) {
            Result.success(AccessStatus.OFFLINE_CACHE_APPROVED)
        } else {
            Result.failure(Exception("Could not verify account. Check your connection and sign in again."))
        }

    private companion object {
        /** HTTP 401 from the backend: the session token is no longer valid. */
        const val HTTP_UNAUTHORIZED = 401

        const val K_PENDING_EMAIL = "pending_email"

        // Where the email link returns to. Must be an Authorized Domain in the
        // Firebase project and handled as an App Link by this app (see docs).
        // Keep in sync with the backend's FIREBASE_PROJECT_ID — this is that
        // project's default hosting domain.
        const val EMAIL_LINK_CONTINUE_URL = "https://indicvision-dic-app-auth.firebaseapp.com/finishSignIn"
    }
}
