package com.sempermechanics.semper.ui.account

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the activation screen shows. */
sealed interface ActivationState {
    /** Ready for a key. */
    data object Idle : ActivationState

    /** The request is out. */
    data object Running : ActivationState

    /** A message about the key [input] (its [LicenseKeyFormat.canonical] form); editing the key clears it. */
    sealed interface Problem : ActivationState {
        val input: String
    }

    /** The key does not have a licence key's shape; nothing was sent. */
    data class Invalid(override val input: String, val check: LicenseKeyFormat.Check) : Problem

    /** The backend did not activate it. */
    data class Refused(override val input: String, val error: ActivationError) : Problem

    /** The licence is active on this account; the screen closes. */
    data object Activated : ActivationState
}

/**
 * The activation screen's state and its one request, held across a rotation
 * of [ActivateLicenseActivity] (ADR-016): a recreated screen shows the same
 * spinner and gets the answer, rather than the request being cancelled
 * half-way with the key perhaps already redeemed, and a message stays up.
 * Leaving the screen for good cancels the request.
 *
 * [JvmOverloads] gives the `(Application)` constructor the default
 * `viewModels()` factory looks up; tests pass their own [activation].
 */
class ActivateLicenseViewModel @JvmOverloads constructor(
    app: Application,
    private val activation: suspend (Context, String) -> ActivationOutcome = { context, key ->
        LicenseActivation.activate(context, key)
    },
) : AndroidViewModel(app) {

    private val mutableState = MutableStateFlow<ActivationState>(ActivationState.Idle)
    val state: StateFlow<ActivationState> = mutableState.asStateFlow()

    /**
     * Activate the key typed as [input], once it has a key's shape. Ignored
     * while a request is out or after one succeeded.
     */
    fun submit(input: CharSequence) {
        val now = mutableState.value
        if (now == ActivationState.Running || now == ActivationState.Activated) return
        when (val check = LicenseKeyFormat.check(input)) {
            is LicenseKeyFormat.Check.Valid -> start(check.key)
            else -> mutableState.value = ActivationState.Invalid(LicenseKeyFormat.canonical(input), check)
        }
    }

    /**
     * The key in the field is now [input]. A message about another key is
     * cleared; the same key (a reformat, or the field restoring its text after
     * a rotation) keeps it.
     */
    fun edited(input: CharSequence) {
        val problem = mutableState.value as? ActivationState.Problem ?: return
        if (LicenseKeyFormat.canonical(input) != problem.input) mutableState.value = ActivationState.Idle
    }

    private fun start(key: String) {
        mutableState.value = ActivationState.Running
        viewModelScope.launch {
            mutableState.value = when (val outcome = activation(getApplication(), key)) {
                ActivationOutcome.Activated -> ActivationState.Activated
                is ActivationOutcome.Refused ->
                    ActivationState.Refused(LicenseKeyFormat.canonical(key), outcome.error)
            }
        }
    }
}
