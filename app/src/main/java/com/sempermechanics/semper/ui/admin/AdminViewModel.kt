package com.sempermechanics.semper.ui.admin

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AccessStatus
import com.sempermechanics.semper.data.net.AdminUserDto
import com.sempermechanics.semper.data.net.HttpFailure
import com.sempermechanics.semper.data.net.SemperApi
import com.sempermechanics.semper.data.net.TokenProvider
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The access-requests list of [AdminActivity] and the approvals and denials
 * sent from it, kept across a rotation (ADR-016).
 *
 * An approval used to run on the screen's own scope: a rotation while the
 * backend answered dropped the answer (no confirmation, a stale list), or
 * cancelled the request before it was sent. Here the list ([users]) and the
 * calls live on [viewModelScope], and what each call came to is handed out
 * once ([outcomes]) to whichever screen is current.
 */
@MainThread
class AdminViewModel(app: Application) : AndroidViewModel(app) {

    /** What a call came to, for the screen to act on once. */
    sealed interface Outcome {
        /** A confirmation or an error for the admin; [long] for errors. */
        data class Say(val text: String, val long: Boolean = false) : Outcome

        /** Nobody is signed in: the screen says so and closes. */
        data object SignedOut : Outcome
    }

    /** The calls this screen makes, behind one seam for tests. */
    internal interface Calls {
        suspend fun token(): String?

        suspend fun listPending(token: String): List<AdminUserDto>

        suspend fun setStatus(token: String, uid: String, action: String)
    }

    private val _users = MutableStateFlow<List<AdminUserDto>?>(null)

    /** The pending users as last listed; null before the first list answers. */
    val users: StateFlow<List<AdminUserDto>?> = _users.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** A call is out: the spinner shows. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var callsOut = 0

    private val outcomeChannel = Channel<Outcome>(Channel.UNLIMITED)

    /** Each call's outcome once; buffered while no screen is there to take it. */
    val outcomes: Flow<Outcome> = outcomeChannel.receiveAsFlow()

    private val calls: Calls = callsFor(app)

    private var started = false

    /** Lists the pending users the first time a screen asks; a recreated screen shows [users] as kept. */
    fun start() {
        if (started) return
        started = true
        load()
    }

    /** Approves (`approve`) or denies (`revoke`) [user]; the list is read again after. */
    fun act(user: AdminUserDto, action: String) {
        track(1)
        viewModelScope.launch {
            val token = calls.token()
            if (token == null) {
                track(-1)
                return@launch
            }
            suspendRunCatching { calls.setStatus(token, user.uid, action) }
                .onSuccess {
                    val label = user.email ?: user.uid
                    val said = if (action == ACTION_APPROVE) {
                        R.string.admin_approved_toast_fmt
                    } else {
                        R.string.admin_denied_toast_fmt
                    }
                    outcomeChannel.send(Outcome.Say(text(said, label)))
                    load() // refresh the list
                }
                .onFailure { e -> say(R.string.admin_action_error_fmt, e) }
            track(-1)
        }
    }

    private fun load() {
        track(1)
        viewModelScope.launch {
            val token = calls.token()
            if (token == null) {
                track(-1)
                outcomeChannel.send(Outcome.SignedOut)
                return@launch
            }
            // Cancellation (the screen closed for good) propagates: it is not a load error.
            suspendRunCatching { calls.listPending(token) }
                .onSuccess { _users.value = it }
                .onFailure { e -> say(R.string.admin_load_error_fmt, e) }
            track(-1)
        }
    }

    /** Counts the calls out, so one ending does not hide the spinner of another. */
    private fun track(change: Int) {
        callsOut += change
        _busy.value = callsOut > 0
    }

    /** An error for the admin: [res] formatted with what [error] means, never its own text (AE1-009). */
    private suspend fun say(@StringRes res: Int, error: Throwable) {
        val app = getApplication<Application>()
        val meaning = app.getString(HttpFailure.classify(error).userMessage(R.string.error_generic))
        outcomeChannel.send(Outcome.Say(text(res, meaning), long = true))
    }

    private fun text(@StringRes res: Int, arg: String): String = getApplication<Application>().getString(res, arg)

    override fun onCleared() {
        outcomeChannel.close()
        super.onCleared()
    }

    /** The backend's admin endpoints, for the signed-in admin. */
    private class ApiCalls(app: Application) : Calls {
        private val api by lazy { SemperApi.get(app) }

        override suspend fun token(): String? = TokenProvider.usableIdToken()

        override suspend fun listPending(token: String) = api.listUsers(token, AccessStatus.PENDING)

        override suspend fun setStatus(token: String, uid: String, action: String) =
            api.setUserStatus(token, uid, action)
    }

    internal companion object {
        const val ACTION_APPROVE = "approve"
        const val ACTION_REVOKE = "revoke"

        /** Where a new view model's calls go; tests stand in for the backend, then put this back. */
        @VisibleForTesting
        internal var callsFor: (Application) -> Calls = ::ApiCalls
    }
}
