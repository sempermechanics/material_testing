// Moved out of AuthActivity with its suppression: each step bails on the
// first check it fails.
@file:Suppress("ReturnCount")

package com.sempermechanics.semper.ui.auth

import android.content.Intent
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivityAuthBinding

/**
 * [AuthActivity]'s password reset: mailing the reset link ("Forgot
 * password"), opening the reset form from that link, and setting the new
 * password, which then signs the user in. The calls run in [AuthViewModel];
 * this checks the form first.
 */
internal class AuthPasswordReset(
    private val activity: AuthActivity,
    private val viewModel: AuthViewModel,
    private val binding: ActivityAuthBinding,
) {
    /**
     * Password-reset App Link: `…/finishReset?mode=resetPassword&oobCode=…`.
     * Verifies the code, then the form becomes the new-password form ([onReady]).
     */
    fun handleLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!activity.isTrustedAuthLink(data)) return
        if (data.getQueryParameter("mode") != "resetPassword") return
        val oobCode = data.getQueryParameter("oobCode") ?: return
        viewModel.verifyResetLink(oobCode)
    }

    /** The reset link checked out: the form for [email], its new password typed twice. */
    fun onReady(email: String) {
        binding.etEmail.setText(email)
        binding.etPassword.setText("")
        binding.etConfirmPassword.setText("")
        activity.render()
    }

    /** Sets the new password from the reset form, then signs in with it. */
    fun confirm() {
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        val failure = PasswordPolicy.validate(password)
        if (failure != null) {
            activity.showMessage(activity.passwordFailureText(failure))
            return
        }
        if (password != binding.etConfirmPassword.text.toString()) {
            activity.showMessage(activity.getString(R.string.error_passwords_mismatch))
            return
        }
        viewModel.confirmReset(email, password)
    }

    /** "Forgot password": mails a reset link to the address in the form. */
    fun sendResetMail() {
        val email = binding.etEmail.text.toString().trim()
        if (!activity.isValidEmail(email)) return
        viewModel.sendResetMail(email)
    }
}
