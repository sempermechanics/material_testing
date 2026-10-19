package com.sempermechanics.semper.ui.auth

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.net.TermsVersionMismatchException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The Terms acceptance of [TermsActivity], kept across a rotation (ADR-016).
 *
 * It used to run on the gate's own scope: a rotation while the backend
 * recorded the acceptance cancelled it, or dropped its answer, and left the
 * user on a gate they had already agreed to. Here it runs on
 * [viewModelScope], and its outcome is handed out once ([outcomes]) to
 * whichever gate is current.
 */
@MainThread
class TermsViewModel(app: Application) : AndroidViewModel(app) {

    /** What agreeing came to. */
    sealed interface Outcome {
        /** Accepted (or kept on this phone to send later): the gate moves on. */
        data object Accepted : Outcome

        /** Not accepted; [message] says why, and the gate stays. */
        data class Refused(val message: String) : Outcome
    }

    private val _busy = MutableStateFlow(false)

    /** An acceptance is being recorded: the gate waits. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val outcomeChannel = Channel<Outcome>(Channel.UNLIMITED)

    /** Each acceptance's outcome once; buffered while no gate is there to take it. */
    val outcomes: Flow<Outcome> = outcomeChannel.receiveAsFlow()

    /** How the acceptance is recorded; tests stand in for it. */
    @VisibleForTesting
    internal var accept: suspend (version: String, improvementConsent: Boolean) -> Result<Unit> =
        { version, consent ->
            AuthRepository(getApplication()).acceptTerms(version = version, improvementConsent = consent)
        }

    /** Records acceptance of Terms [version], with the optional [improvementConsent]; once at a time. */
    fun agree(version: String, improvementConsent: Boolean) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val result = try {
                accept(version, improvementConsent)
            } finally {
                _busy.value = false
            }
            val outcome = result.fold(
                onSuccess = { Outcome.Accepted },
                onFailure = { error ->
                    val app = getApplication<Application>()
                    Outcome.Refused(
                        if (error is TermsVersionMismatchException) {
                            app.getString(R.string.terms_error_update_app)
                        } else {
                            error.message ?: app.getString(R.string.terms_error_generic)
                        },
                    )
                },
            )
            outcomeChannel.send(outcome)
        }
    }

    override fun onCleared() {
        outcomeChannel.close()
        super.onCleared()
    }
}
