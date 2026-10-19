package com.sempermechanics.semper.ui.settings

import android.app.Application
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.cloud.restore.RestoreStart
import com.sempermechanics.semper.ui.common.transfer.RestoreRequest
import com.sempermechanics.semper.ui.common.transfer.request
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settings' requests that must not die with a rotation (ADR-016): a backup's
 * restore and the product-improvement consent.
 *
 * Both used to run on [SettingsActivity]'s own scope, so a rotation while the
 * request was in flight cancelled it, or let it land unseen: the row stayed
 * busy with nothing queued, or the switch showed an answer the server never
 * confirmed. Here they run on [viewModelScope], and what each came to is
 * handed out once ([restoreOutcomes], [consentOutcomes]) to whichever
 * Settings is current.
 */
@MainThread
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    /** A restore of the row [key] (its cloud id), named [name], and what asking for it came to. */
    data class RestoreAsked(val key: String, val name: String, val request: RestoreRequest)

    /** What a consent change came to: [granted] was asked for, and the server took it or not. */
    data class ConsentAnswer(val granted: Boolean, val saved: Boolean)

    private val restoreChannel = Channel<RestoreAsked>(Channel.UNLIMITED)

    /** Each restore request's outcome once; buffered while no Settings is there to take it. */
    val restoreOutcomes: Flow<RestoreAsked> = restoreChannel.receiveAsFlow()

    private val restoring = mutableSetOf<String>()

    /** The rows whose restore is being asked for now, so a recreated screen shows them busy. */
    val restoringKeys: Set<String> get() = restoring.toSet()

    private val _consentSending = MutableStateFlow<Boolean?>(null)

    /** The consent value being sent, or null when none is: the switch shows it, and waits. */
    val consentSending: StateFlow<Boolean?> = _consentSending.asStateFlow()

    private val consentChannel = Channel<ConsentAnswer>(Channel.UNLIMITED)

    /** Each consent change's answer once. */
    val consentOutcomes: Flow<ConsentAnswer> = consentChannel.receiveAsFlow()

    /** How a restore is asked for; tests stand in for it. */
    @VisibleForTesting
    internal var requestRestore: suspend (RestoreStart.Target) -> RestoreRequest =
        { target -> RestoreStart.request(getApplication(), listOf(target)) }

    /** How the consent is sent; tests stand in for it. */
    @VisibleForTesting
    internal var sendConsent: suspend (Boolean) -> Result<Unit> =
        { granted -> AuthRepository(getApplication()).setImprovementConsent(granted) }

    /** Asks for [target]'s restore for the row [key]; the outcome goes to [restoreOutcomes]. */
    fun restore(key: String, target: RestoreStart.Target) {
        restoring += key
        viewModelScope.launch {
            val asked = try {
                requestRestore(target)
            } finally {
                restoring -= key
            }
            restoreChannel.send(RestoreAsked(key, target.name, asked))
        }
    }

    /** Grants or withdraws the improvement consent; one change at a time. */
    fun setImprovementConsent(granted: Boolean) {
        if (_consentSending.value != null) return
        _consentSending.value = granted
        viewModelScope.launch {
            val result = try {
                sendConsent(granted)
            } finally {
                _consentSending.value = null
            }
            result.onFailure { Timber.w(it, "Improvement consent update failed") }
            consentChannel.send(ConsentAnswer(granted, saved = result.isSuccess))
        }
    }

    override fun onCleared() {
        restoreChannel.close()
        consentChannel.close()
        super.onCleared()
    }
}
