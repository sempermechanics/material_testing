// One entry point per sign-in path (password, link, Google, TOTP, reset), as
// AuthActivity had before them, so TooManyFunctions is suppressed here.
@file:Suppress("TooManyFunctions")

package com.sempermechanics.semper.ui.auth

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.userMessageOr
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The sign-in screen's state and calls, kept across a rotation (ADR-016).
 *
 * Sign-in used to run on [AuthActivity]'s own scope with its form in a plain
 * field: a rotation while Firebase or the backend answered cancelled the
 * call's routing, dropped a Create account form back to Sign in, and lost the
 * offer to save the password. Here the calls run on [viewModelScope], the
 * form ([screen]) lives in the [SavedStateHandle], and what each call came to
 * is handed out once ([outcomes]) to whichever screen is current.
 */
@MainThread
class AuthViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    /** What a call came to, for the screen to act on once. */
    sealed interface Outcome {
        /** Signed in with access [status]; [credential] is the password pair worth offering to save. */
        data class SignedIn(val status: String, val credential: Pair<String, String>?) : Outcome

        /** The identity was proved again: the caller gets `RESULT_OK`. */
        data object Reauthed : Outcome

        /** The account was created and its verification mail sent; there is no session yet. */
        data class VerificationPending(
            val error: AuthRepository.EmailVerificationRequired,
            val credential: Pair<String, String>?,
        ) : Outcome

        /** A TOTP challenge is open: [screen] is its form now. */
        data object TotpRequired : Outcome

        /** The reset link checked out: [screen] is the new-password form for [email]. */
        data class ResetReady(val email: String) : Outcome

        /** An error or a confirmation for the user. */
        data class Say(val text: String, val long: Boolean = true) : Outcome
    }

    internal val repo by lazy { AuthRepository(getApplication()) }

    /** An open TOTP challenge. In memory only: Firebase's resolver does not go in a Bundle. */
    private var totp: AuthScreen.Totp? = null

    /** The form shown; a Form or Reset survives process death, a TOTP challenge falls back to its form. */
    var screen: AuthScreen
        get() = totp ?: savedScreen()
        set(value) {
            totp = value as? AuthScreen.Totp
            when (value) {
                is AuthScreen.Form -> {
                    saved[KEY_MODE] = value.mode.name
                    saved.remove<String>(KEY_RESET_CODE)
                }
                is AuthScreen.Reset -> saved[KEY_RESET_CODE] = value.oobCode
                is AuthScreen.Totp -> saved[KEY_MODE] = value.returnTo.name
            }
        }

    /** The email/password just submitted, pending the outcome that validates it. */
    var pendingCredential: Pair<String, String>? = null

    private val _busy = MutableStateFlow(false)

    /** A call is running: the screen's buttons wait. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val outcomeChannel = Channel<Outcome>(Channel.UNLIMITED)

    /** Each call's outcome once; buffered while no screen is there to take it. */
    val outcomes: Flow<Outcome> = outcomeChannel.receiveAsFlow()

    /** Opens on [mode] unless a form is already saved (a rotation, a restored process). */
    fun begin(mode: AuthMode) {
        if (KEY_MODE !in saved && KEY_RESET_CODE !in saved) screen = AuthScreen.Form(mode)
    }

    /** Sign in ↔ Create account. */
    fun toggleRegister() {
        screen = AuthScreen.Form(if (screen.mode == AuthMode.REGISTER) AuthMode.SIGN_IN else AuthMode.REGISTER)
    }

    /** A Create account form whose account now exists goes back to signing in. */
    fun leaveRegister() {
        if (screen.mode == AuthMode.REGISTER) screen = AuthScreen.Form(AuthMode.SIGN_IN)
    }

    /** Marks the screen busy (or not) around work the Activity itself does, the Google picker. */
    fun setBusy(busy: Boolean) {
        _busy.value = busy
    }

    /** Runs an auth call that resolves to an access status, and hands out where it leads. */
    fun runAuth(call: suspend AuthRepository.() -> Result<String>) {
        _busy.value = true
        viewModelScope.launch { route(repo.call()) }
    }

    /** Runs a re-authentication call and hands out its outcome. */
    fun runReauth(call: suspend AuthRepository.() -> Result<Unit>) {
        _busy.value = true
        viewModelScope.launch { finishReauth(repo.call()) }
    }

    /** The Google ID token the Activity obtained: a re-auth or a sign-in, by the form. */
    fun withGoogleToken(idToken: String) {
        if (screen.mode == AuthMode.REAUTH) {
            runReauth { reauthenticateWithGoogle(idToken) }
        } else {
            runAuth { signInWithGoogle(idToken) }
        }
    }

    /** An email sign-in link arrived: it signs in, or proves a re-auth's address. */
    fun completeEmailLink(link: String) {
        if (screen.mode == AuthMode.REAUTH) {
            // Already signed in: the link proves the address rather than opening
            // a new session, so it must not go through the sign-in path.
            runReauth { reauthenticateWithEmailLink(link) }
            return
        }
        val email = repo.pendingLinkEmail()
        if (email.isNullOrBlank()) {
            say(R.string.auth_link_wrong_device)
        } else {
            runAuth { completeEmailLink(email, link) }
        }
    }

    /** The authenticator code for the open challenge. */
    fun submitTotp(code: String) {
        val challenge = totp
        when {
            challenge == null -> say(R.string.auth_sign_in_failed)
            code.isEmpty() -> say(R.string.auth_totp_empty)
            challenge.returnTo == AuthMode.REAUTH ->
                runReauth { resolveTotpChallenge(challenge.resolver, challenge.enrollmentId, code) }
            else -> runAuth { completeTotpChallenge(challenge.resolver, challenge.enrollmentId, code) }
        }
    }

    /** Mails a one-time sign-in link to [email]. */
    fun sendSignInLink(email: String) {
        _busy.value = true
        viewModelScope.launch {
            val result = repo.sendSignInLink(email)
            _busy.value = false
            result.fold(
                onSuccess = { say(text(R.string.auth_link_sent_fmt, email)) },
                onFailure = { say(it.userMessageOr(text(R.string.auth_link_send_failed))) },
            )
        }
    }

    /** "Forgot password": mails a reset link to [email]. */
    fun sendResetMail(email: String) {
        _busy.value = true
        viewModelScope.launch {
            val result = repo.sendPasswordReset(email)
            _busy.value = false
            result.fold(
                onSuccess = { say(text(R.string.auth_reset_sent_fmt, email)) },
                onFailure = { say(it.userMessageOr(text(R.string.auth_reset_failed))) },
            )
        }
    }

    /** Verifies a reset link's [oobCode]; on success the screen becomes the new-password form. */
    fun verifyResetLink(oobCode: String) {
        _busy.value = true
        viewModelScope.launch {
            val result = repo.verifyPasswordResetCode(oobCode)
            _busy.value = false
            result.fold(
                onSuccess = { email ->
                    screen = AuthScreen.Reset(oobCode)
                    outcomeChannel.send(Outcome.ResetReady(email))
                },
                onFailure = { say(it.userMessageOr(text(R.string.auth_reset_link_invalid))) },
            )
        }
    }

    /** Sets the new password from the reset form, then signs in with it. */
    fun confirmReset(email: String, password: String) {
        val reset = screen as? AuthScreen.Reset ?: return
        _busy.value = true
        viewModelScope.launch {
            val confirmed = repo.confirmPasswordReset(reset.oobCode, password)
            if (confirmed.isFailure) {
                _busy.value = false
                val fallback = text(R.string.auth_reset_failed)
                say(confirmed.exceptionOrNull()?.userMessageOr(fallback) ?: fallback)
                return@launch
            }
            pendingCredential = email to password
            screen = AuthScreen.Form(AuthMode.SIGN_IN)
            route(repo.signInWithPassword(email, password))
        }
    }

    private suspend fun route(result: Result<String>) {
        _busy.value = false
        val outcome = result.fold(
            onSuccess = { status ->
                totp = null
                Outcome.SignedIn(status, pendingCredential)
            },
            onFailure = { error ->
                when (error) {
                    is AuthRepository.EmailVerificationRequired -> Outcome.VerificationPending(error, pendingCredential)
                    is AuthRepository.MfaTotpRequired -> openTotp(error)
                    else -> Outcome.Say(error.userMessageOr(text(R.string.auth_sign_in_failed)))
                }
            },
        )
        outcomeChannel.send(outcome)
    }

    private suspend fun finishReauth(result: Result<Unit>) {
        _busy.value = false
        val outcome = result.fold(
            onSuccess = { Outcome.Reauthed },
            onFailure = { error ->
                if (error is AuthRepository.MfaTotpRequired) {
                    openTotp(error)
                } else {
                    Outcome.Say(error.userMessageOr(text(R.string.reauth_failed)))
                }
            },
        )
        outcomeChannel.send(outcome)
    }

    /** First factor accepted; the authenticator code is next. */
    private fun openTotp(error: AuthRepository.MfaTotpRequired): Outcome {
        screen = AuthScreen.Totp(error.resolver, error.enrollmentId, returnTo = screen.mode)
        return Outcome.TotpRequired
    }

    private fun savedScreen(): AuthScreen {
        saved.get<String>(KEY_RESET_CODE)?.let { return AuthScreen.Reset(it) }
        val mode = saved.get<String>(KEY_MODE)?.let { name -> AuthMode.entries.firstOrNull { it.name == name } }
        return AuthScreen.Form(mode ?: AuthMode.SIGN_IN)
    }

    private fun say(@StringRes res: Int) = say(text(res))

    private fun say(text: String) {
        outcomeChannel.trySend(Outcome.Say(text))
    }

    private fun text(@StringRes res: Int): String = getApplication<Application>().getString(res)

    private fun text(@StringRes res: Int, arg: String): String = getApplication<Application>().getString(res, arg)

    override fun onCleared() {
        outcomeChannel.close()
        super.onCleared()
    }

    internal companion object {
        const val KEY_MODE = "auth_mode"
        const val KEY_RESET_CODE = "auth_reset_oob_code"
    }
}
