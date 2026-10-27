package com.sempermechanics.semper.data.account

import android.content.Context
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.GoogleAuthProvider
import com.sempermechanics.semper.R

/**
 * The credentials [AuthRepository]'s re-authentications present, one per way
 * of signing in. Each must be the kind Firebase expects for that account: an
 * emailed link presented as a password is refused, every time.
 */
internal object ReauthCredentials {

    fun password(email: String, password: String): AuthCredential = EmailAuthProvider.getCredential(email, password)

    fun emailLink(email: String, link: String): AuthCredential = EmailAuthProvider.getCredentialWithLink(email, link)

    fun google(googleIdToken: String): AuthCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
}

/**
 * A re-authentication failure with its own outcome: the account wants its
 * second factor (any credential can meet that), or, given [wrongPassword],
 * Firebase refused the password. Null for every other failure.
 */
internal fun reauthFailure(context: Context, e: Exception, wrongPassword: String? = null): Result<Unit>? = when {
    e is FirebaseAuthMultiFactorException -> Result.failure(mfaRequired(context, e))
    wrongPassword != null -> wrongSecret(e, wrongPassword)
    else -> null
}

/** [message] for a credential Firebase rejected; null for any other failure. */
internal fun <T> wrongSecret(e: Exception, message: String): Result<T>? =
    if (e is FirebaseAuthInvalidCredentialsException) Result.failure(UserMessageException(message, e)) else null

/** Map Firebase's multi-factor exception to a TOTP challenge the UI can run. */
internal fun mfaRequired(context: Context, e: FirebaseAuthMultiFactorException): Exception {
    val enrollmentId = TotpMfa.enrollmentId(e.resolver.hints)
        ?: return UserMessageException(context.getString(R.string.auth_totp_needs_enrolment), e)
    return AuthRepository.MfaTotpRequired(e.resolver, enrollmentId, context.getString(R.string.auth_totp_empty))
}
