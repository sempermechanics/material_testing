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
import com.indicvision.semper.analytics.SemperAnalytics
import com.indicvision.semper.data.net.ApiErrors
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
 * Host both Firebase auth continue links return to — the project's default
 * hosting domain. Must be an Authorized Domain in the Firebase project and
 * handled as an App Link by this app (see docs); keep in sync with the
 * backend's FIREBASE_PROJECT_ID.
 *
 * Top-level rather than on [AuthRepository]'s private companion because
 * `AuthActivity` checks arriving links against it, and one constant beats a
 * second copy of the domain drifting out of step with the manifest.
 */
const val AUTH_HOST = "indicvision-dic-app-auth.firebaseapp.com"

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
    suspend fun signInWithGoogle(googleIdToken: String): Result<String> = firebaseThen("google") {
        auth.signInWithCredential(GoogleAuthProvider.getCredential(googleIdToken, null)).await()
    }

    /** Existing account: email + password. */
    suspend fun signInWithPassword(email: String, password: String): Result<String> = firebaseThen("password") {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    /**
     * New account: email + password. Fires a verification email and stops there —
     * [firebaseThen] blocks the session until the address is confirmed, so a new
     * account never reaches the backend before its owner has proved the mailbox
     * is theirs.
     */
    suspend fun signUpWithPassword(email: String, password: String): Result<String> = firebaseThen("password_signup") {
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
     * Password recovery: email a reset link that opens this app via App Link
     * ([RESET_CONTINUE_URL]). The user sets a new password in-app, then signs in.
     * Works whether or not the cloud backend is configured.
     *
     * A missing account is reported as success on purpose: surfacing "no account
     * for this email" here would let anyone probe which emails are registered.
     *
     * **Ops:** Firebase Console → Authentication → Templates → Password reset
     * must set the custom action URL to [RESET_CONTINUE_URL], or the email still
     * opens Firebase's hosted form instead of the app.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        val settings = ActionCodeSettings.newBuilder()
            .setUrl(RESET_CONTINUE_URL)
            .setHandleCodeInApp(true)
            .setAndroidPackageName(appContext.packageName, true, null)
            .build()
        try {
            auth.sendPasswordResetEmail(email.trim(), settings).await()
            Result.success(Unit)
        } catch (e: FirebaseAuthInvalidUserException) {
            Timber.d(e, "Password reset for an unregistered email (existence not revealed)")
            Result.success(Unit) // don't reveal whether the email is registered
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Could not send password reset")
            Result.failure(Exception(e.message ?: "Could not send the reset email."))
        }
    }

    /** Validate a password-reset oobCode; returns the account email on success. */
    suspend fun verifyPasswordResetCode(oobCode: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(auth.verifyPasswordResetCode(oobCode).await())
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Password-reset code invalid or expired")
            Result.failure(Exception(e.message ?: "This reset link is invalid or has expired."))
        }
    }

    /** Set a new password from a verified reset oobCode. Does not sign in. */
    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                auth.confirmPasswordReset(oobCode, newPassword).await()
                Result.success(Unit)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.w(e, "Could not confirm password reset")
                Result.failure(Exception(e.message ?: "Could not reset the password."))
            }
        }

    /** True if [link] is a Firebase email sign-in link. */
    fun isEmailSignInLink(link: String): Boolean = auth.isSignInWithEmailLink(link)

    /** The email a link was last sent to (needed to complete the sign-in). */
    fun pendingLinkEmail(): String? = linkPrefs().getString(K_PENDING_EMAIL, null)

    /** Finish a passwordless email-link sign-in from the tapped link. */
    suspend fun completeEmailLink(email: String, link: String): Result<String> {
        val result = firebaseThen("email_link") { auth.signInWithEmailLink(email.trim(), link).await() }
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
    @Suppress("LongMethod") // method-tagged analytics on every early-exit keeps one linear flow
    private suspend fun firebaseThen(
        method: String,
        signIn: suspend () -> Any?,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!api.enabled) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "api_off"),
            )
            return@withContext Result.failure(
                Exception("Cloud backend is not configured (INDIC_API_BASE_URL)."),
            )
        }
        try {
            signIn()
        } catch (e: FirebaseAuthWeakPasswordException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "weak_password"),
            )
            return@withContext Result.failure(Exception("Password is too weak (min 6 characters).", e))
        } catch (e: FirebaseAuthUserCollisionException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "collision"),
            )
            return@withContext Result.failure(
                Exception("An account already exists for this email. Sign in instead.", e),
            )
        } catch (e: FirebaseAuthInvalidUserException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "unknown_user"),
            )
            return@withContext Result.failure(Exception("No account for this email.", e))
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "bad_credentials"),
            )
            return@withContext Result.failure(Exception("Incorrect email or password.", e))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Firebase sign-in failed")
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "other"),
            )
            return@withContext Result.failure(Exception(e.message ?: "Sign-in failed."))
        }
        val user = auth.currentUser
            ?: run {
                SemperAnalytics.event(
                    appContext,
                    SemperAnalytics.SIGN_IN_FAILED,
                    mapOf("method" to method, "reason" to "incomplete"),
                )
                return@withContext Result.failure(Exception("Sign-in did not complete."))
            }
        unverifiedEmailError(user)?.let {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "unverified_email"),
            )
            return@withContext Result.failure(it)
        }
        TokenStore.saveIdentity(appContext, user.uid, user.email)
        val status = resolveStatus()
        if (status.isSuccess) {
            SemperAnalytics.event(appContext, SemperAnalytics.SIGN_IN, mapOf("method" to method))
        } else {
            SemperAnalytics.event(
                appContext,
                SemperAnalytics.SIGN_IN_FAILED,
                mapOf("method" to method, "reason" to "access"),
            )
        }
        status
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
                // Gateway/backend rejected the Firebase ID token (wrong audience,
                // expired, or malformed). Surface a short server hint when present
                // so "Session expired" is not the only clue for a misconfigured
                // FIREBASE_PROJECT_ID / API Gateway JWT audience.
                val hint = ApiErrors.detailOf(e.detail).lineSequence().firstOrNull().orEmpty()
                    .take(API_ERROR_HINT_MAX_CHARS)
                    .ifBlank { null }
                val message = if (hint != null) {
                    "Sign-in rejected by the API (401). $hint"
                } else {
                    "Session expired. Please sign in again."
                }
                Result.failure(Exception(message))
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

        /** Cap server error detail length in user-facing 401 snackbars. */
        const val API_ERROR_HINT_MAX_CHARS = 120

        const val K_PENDING_EMAIL = "pending_email"

        /** Email sign-in link continue URL — see [AUTH_HOST]. */
        const val EMAIL_LINK_CONTINUE_URL = "https://$AUTH_HOST/finishSignIn"

        /** Password-reset App Link continue URL — keep in sync with the manifest filter. */
        const val RESET_CONTINUE_URL = "https://$AUTH_HOST/finishReset"
    }
}
