package com.sempermechanics.semper.data.account

import android.content.Context
import androidx.annotation.StringRes
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.sempermechanics.semper.R
import com.sempermechanics.semper.util.rethrowIfCallerCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The user's text for a Firebase failure [e] its caller did not name: no
 * network and too many attempts by name, anything else [fallback]. Firebase's
 * own message ("An internal error has occurred. [ … ]") goes to the log only.
 */
internal fun firebaseFailureText(context: Context, e: Throwable, @StringRes fallback: Int): String =
    context.getString(
        when (e) {
            is FirebaseNetworkException -> R.string.error_offline
            is FirebaseTooManyRequestsException -> R.string.auth_too_many_requests
            else -> fallback
        },
    )

/**
 * Runs one Firebase Auth call, [op], on IO and turns what it throws into a
 * failed [Result].
 *
 * [known] gives the outcome for the exceptions the caller names (a wrong
 * password, an unknown user) and null for the rest. Those are logged as
 * [failureLog] and fail with [firebaseFailureText] ([fallback] unless offline
 * or throttled) as a [UserMessageException]; the cause is kept.
 *
 * Only the caller's own cancellation is rethrown. A Firebase Task cancelled
 * while the caller still waits is an ordinary failure ([rethrowIfCallerCancelled]).
 */
internal suspend fun <T> firebaseOp(
    context: Context,
    failureLog: String,
    @StringRes fallback: Int,
    known: (Exception) -> Result<T>? = { null },
    op: suspend () -> T,
): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(op())
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        e.rethrowIfCallerCancelled()
        known(e) ?: run {
            Timber.w(e, failureLog)
            Result.failure(UserMessageException(firebaseFailureText(context, e, fallback), e))
        }
    }
}

/**
 * Runs a Firebase call nothing waits on the outcome of (a verification mail,
 * a refresh of cached state): whatever it throws is logged as [failureLog]
 * and dropped, except the caller's own cancellation.
 */
internal suspend fun firebaseBestEffort(failureLog: String, op: suspend () -> Unit) {
    try {
        op()
    } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
        e.rethrowIfCallerCancelled()
        Timber.w(e, failureLog)
    }
}
