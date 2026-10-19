package com.sempermechanics.semper.ui.limit

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.LicenseConfigWorker
import com.sempermechanics.semper.data.account.SeatLease
import com.sempermechanics.semper.data.net.ApiErrors
import com.sempermechanics.semper.data.net.ApiException
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.net.NoSeatAvailableException
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
import timber.log.Timber

/**
 * The seat request of [SeatRequiredActivity], kept across a rotation (ADR-016).
 *
 * It used to run on the screen's own scope: a rotation while the backend
 * answered cancelled the checkout's follow-up (the config applied, the
 * entitlement fetched, the licence refresh queued), or dropped the answer, so
 * a seat could be taken with the screen still asking for one. Here it runs on
 * [viewModelScope], and its outcome is handed out once ([outcomes]) to
 * whichever screen is current.
 */
@MainThread
class SeatRequiredViewModel(app: Application) : AndroidViewModel(app) {

    /** What asking for a seat came to. */
    sealed interface Outcome {
        /** A seat is this user's: the screen says so and closes. */
        data object Taken : Outcome

        /** No seat; [message] says why, and the screen stays to try again. */
        data class NotTaken(@StringRes val message: Int) : Outcome
    }

    private val _busy = MutableStateFlow(false)

    /** A request is out: the button waits. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val outcomeChannel = Channel<Outcome>(Channel.UNLIMITED)

    /** Each request's outcome once; buffered while no screen is there to take it. */
    val outcomes: Flow<Outcome> = outcomeChannel.receiveAsFlow()

    /** How a seat is asked for; tests stand in for it. */
    @VisibleForTesting
    internal var ask: suspend () -> Outcome = { askForSeat(getApplication()) }

    /** Asks for a seat, once at a time; the outcome goes to [outcomes]. */
    fun takeSeat() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = try {
                ask()
            } finally {
                _busy.value = false
            }
            outcomeChannel.send(outcome)
        }
    }

    override fun onCleared() {
        outcomeChannel.close()
        super.onCleared()
    }

    private companion object {
        /**
         * On success the backend's response already carries the new
         * entitlement, so it is applied straight to the cache rather than
         * waiting for the next config fetch — otherwise the user would return
         * to Home still looking blocked.
         */
        suspend fun askForSeat(app: Application): Outcome {
            val api = SemperApi.get(app)
            val token = TokenProvider.usableIdToken()
            if (!api.enabled || token == null) return Outcome.NotTaken(R.string.seat_offline)
            return suspendRunCatching { api.checkoutLease(token) }.fold(
                onSuccess = { config ->
                    AppRemoteConfig.apply(app, config)
                    // The checkout answer has no entitlement token; /v1/config
                    // does, and a failed fetch is retried in the background.
                    SeatLease.refreshConfigAfterCheckout(app)
                    LicenseConfigWorker.enqueue(app)
                    Outcome.Taken
                },
                onFailure = { error -> Outcome.NotTaken(messageFor(error)) },
            )
        }

        /** A full pool is the expected answer, not a fault: it is said plainly. */
        @StringRes
        fun messageFor(error: Throwable): Int = when {
            error is NoSeatAvailableException -> R.string.seat_still_full
            // Past RetryOnTransient's three attempts, so this is a sustained
            // throttle, not a blip — blaming the connection would send the
            // user to their wifi settings for a limit that clears on its own.
            error.hasApiCode(ApiErrors.RATE_LIMITED) -> R.string.error_rate_limited
            error.hasApiCode(ApiErrors.APP_CHECK_REQUIRED) -> R.string.error_app_check_required
            else -> {
                Timber.w(error, "Could not take a seat")
                R.string.seat_error
            }
        }

        /** True when this failure is the backend answering with [code]. */
        fun Throwable.hasApiCode(code: String): Boolean =
            this is ApiException && ApiErrors.isCode(parsedDetail, code)
    }
}
