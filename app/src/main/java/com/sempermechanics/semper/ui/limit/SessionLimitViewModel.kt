package com.sempermechanics.semper.ui.limit

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.cloud.CloudSync
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.session.SessionQuota
import com.sempermechanics.semper.data.session.SessionStore
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * The checks [SessionLimitActivity] runs when the user taps Re-check or
 * Refresh licence, kept across a rotation (ADR-016).
 *
 * They used to run on the screen's own scope: a rotation while the backend
 * answered cancelled the check, so a limit that had cleared left the screen
 * up with nothing said, and a refreshed licence was never checked again.
 * Here they run on [viewModelScope], and what each came to is handed out once
 * ([outcomes]) to whichever screen is current.
 */
@MainThread
class SessionLimitViewModel(app: Application) : AndroidViewModel(app) {

    /** What a check came to. */
    sealed interface Outcome {
        /** A licence refresh said [message]; when the licence [changed], a re-check follows. */
        data class Refreshed(@StringRes val message: Int, val changed: Boolean) : Outcome

        /** There is room again: the screen says so and closes. */
        data object Cleared : Outcome

        /** Still full; [message] says which half of the cap holds the stop. */
        data class StillFull(@StringRes val message: Int) : Outcome
    }

    private val _busy = MutableStateFlow(false)

    /** A check is out: the buttons wait. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val outcomeChannel = Channel<Outcome>(Channel.UNLIMITED)

    /** Each check's outcome once; buffered while no screen is there to take it. */
    val outcomes: Flow<Outcome> = outcomeChannel.receiveAsFlow()

    /** How the licence is refreshed; tests stand in for it. */
    @VisibleForTesting
    internal var refresh: suspend () -> LicenseRefresh.Outcome = { LicenseRefresh.run(getApplication()) }

    /** How the limit is checked again; tests stand in for it. */
    @VisibleForTesting
    internal var check: suspend () -> Outcome = { checkLimit(getApplication()) }

    /**
     * For an account that expects a seat or a licence: ask `/v1/config` now
     * ([LicenseRefresh]), say what it found, and when the licence changed run
     * the limit check again, which closes the screen once there is room.
     */
    fun refreshLicense() {
        runCheck {
            val outcome = refresh()
            outcomeChannel.send(Outcome.Refreshed(outcome.result.messageRes, outcome.changed))
            if (outcome.changed) outcomeChannel.send(check())
        }
    }

    /** Re-query the backend; the outcome says whether there is room again. */
    fun recheck() {
        runCheck { outcomeChannel.send(check()) }
    }

    /** One check at a time; busy until it ends. */
    private fun runCheck(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    override fun onCleared() {
        outcomeChannel.close()
        super.onCleared()
    }

    private companion object {
        /**
         * The device's stop outlives a re-check: the reconcile reports the
         * account's count only, and lifting the stop on it let the next upload
         * be refused again.
         */
        suspend fun checkLimit(app: Application): Outcome {
            // deep=true: the user explicitly tapped Recheck, so bypass the
            // reconcile throttle — a silently skipped check would report
            // "still full" from stale data.
            val outcome = CloudSync.reconcile(app, deep = true)
            // The rows after the reconcile, which may have repaired some.
            val localCount = SessionStore.listOnIo(app).size
            if (outcome is CloudSync.Outcome.Ok) {
                // Ceiling is owned by AppRemoteConfig (refreshed by the same
                // reconcile's config fetch); only the used count is stored here.
                AccountCache.setQuota(app, outcome.quotaUsed, localCount)
            }
            return when {
                !SessionQuota.blocked(app, localCount) -> Outcome.Cleared
                SessionQuota.deviceStop(app) -> Outcome.StillFull(R.string.limit_still_full_device)
                else -> Outcome.StillFull(R.string.limit_still_full)
            }
        }
    }
}
