package com.sempermechanics.semper.ui.auth

import androidx.core.view.isVisible
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityAuthBinding

/**
 * [AuthActivity]'s second-factor form: the authenticator-code card shown
 * while the screen is an [AuthScreen.Totp] challenge, and its submit, for a
 * sign-in or a re-authentication alike ([AuthViewModel.submitTotp]).
 */
internal class AuthTotpController(
    private val viewModel: AuthViewModel,
    private val binding: ActivityAuthBinding,
) {
    /** Whether the main button submits a code rather than the first factor. */
    val isOpen: Boolean get() = viewModel.screen is AuthScreen.Totp

    /**
     * A challenge has just opened; show the authenticator field, empty and
     * focused. Enrolment stays on the websites — the phone only completes a
     * challenge already set up there.
     */
    fun onOpened() {
        enterChallengeUi()
        binding.etTotp.setText("")
        binding.etTotp.requestFocus()
    }

    fun submit() {
        viewModel.submitTotp(binding.etTotp.text.toString().trim())
    }

    /**
     * The authenticator form, as the open challenge shows it (also redrawn
     * after a rotation, keeping the code typed so far). Unit tests call it
     * without a live Firebase resolver to prove the prompt appears.
     */
    fun enterChallengeUi() {
        binding.credentialsCard.isVisible = false
        binding.totpCard.isVisible = true
        binding.btnGoogleSignIn.isVisible = false
        binding.googleOrDivider.isVisible = false
        binding.tvToggleMode.isVisible = false
        binding.tvSubtitle.setText(R.string.auth_totp_subtitle)
        binding.btnMainAction.setText(R.string.auth_totp_verify)
    }

    /** Back to the first-factor card (the form [AuthActivity.render] fills in). */
    fun leaveChallengeUi() {
        binding.credentialsCard.isVisible = true
        binding.totpCard.isVisible = false
    }
}
