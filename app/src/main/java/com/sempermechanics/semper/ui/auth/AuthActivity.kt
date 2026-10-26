// Auth screen: one handler per sign-in path (Google, email link, password) plus
// their validation guards, so TooManyFunctions / ReturnCount are suppressed here.
@file:Suppress("TooManyFunctions", "ReturnCount")

package com.sempermechanics.semper.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.isTrustedAuthLink
import com.sempermechanics.semper.data.account.userMessageOr
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.databinding.ActivityAuthBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.dialog.CrispToast
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.util.suspendRunCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Sign-in screen backed by Firebase Auth. Three ways in:
 *  - **Email + password** (works for any email provider — Outlook, etc.),
 *  - **Passwordless email link** (a one-time link mailed to the address),
 *  - **Google** (shown only when configured — needs the SHA-1 on the Firebase app).
 *
 * Whichever is used, the backend then verifies the Firebase ID token and applies
 * the APPROVED allow-list; routing depends on the resulting access status.
 */
@MainThread
class AuthActivity : AppCompatActivity() {

    /** The form, the calls and their outcomes; kept across a rotation. */
    internal val viewModel: AuthViewModel by viewModels()
    private lateinit var binding: ActivityAuthBinding

    private lateinit var totp: AuthTotpController
    private lateinit var passwordReset: AuthPasswordReset

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A sign-out that routed here from the application has arrived.
        SignOutRun.claimUnclaimed()
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.decorView.post { reportFullyDrawn() }
        totp = AuthTotpController(viewModel, binding)
        passwordReset = AuthPasswordReset(this, viewModel, binding)

        binding.btnGeneratePassword.setOnClickListener {
            val generated = PasswordPolicy.generate()
            binding.etPassword.setText(generated)
            binding.etConfirmPassword.setText(generated)
            binding.etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            binding.etPassword.setSelection(binding.etPassword.length())
            showMessage(getString(R.string.password_generated))
        }

        val reauth = intent.getBooleanExtra(EXTRA_REAUTH, false)
        viewModel.begin(if (reauth) AuthMode.REAUTH else AuthMode.SIGN_IN)
        if (reauth) {
            // Re-auth must prove *this* account, so the address is fixed. Read
            // from the cached session rather than Firebase: it is only shown, and
            // the re-auth calls take the address from the live user themselves.
            binding.etEmail.setText(AccountCache.cachedEmail(this).orEmpty())
            binding.etEmail.isEnabled = false
        }

        binding.btnMainAction.setOnClickListener { onMainAction() }
        binding.tvToggleMode.setOnClickListener {
            viewModel.toggleRegister()
            render()
        }
        binding.tvForgotPassword.setOnClickListener { passwordReset.sendResetMail() }
        binding.tvEmailLink.setOnClickListener { onSendEmailLink() }
        binding.btnGoogleSignIn.setOnClickListener { onGoogleSignIn() }
        render()

        // Pad the scroll container (not the inner column) so the keyboard inset
        // shrinks the viewport and the focused field scrolls clear of the IME.
        Insets.padTopAndImeBottom(binding.rootLayout)
        observeViewModel()

        // Arriving via a tapped email sign-in or password-reset link? Once: a
        // rotation brings the same intent back, and its call is already running.
        if (savedInstanceState == null) {
            maybeCompleteEmailLink(intent)
            passwordReset.handleLink(intent)
            AuthRoute.messageOf(intent)?.let { showMessage(it) }
        }
    }

    /** Busy while a call runs; each call's outcome once, on whichever screen is current. */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.busy.collect { setLoading(it) } }
                viewModel.outcomes.collect { onOutcome(it) }
            }
        }
    }

    /** The email sign-in / reset link may arrive while this activity is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeCompleteEmailLink(intent)
        passwordReset.handleLink(intent)
    }

    /**
     * True when [data] is one of our own auth continue links.
     *
     * This activity is exported, so an explicit `Intent` from any installed app
     * reaches these handlers with a URI of its choosing — the manifest's App
     * Link filter constrains implicit matching only, and never sees an explicit
     * start. Firebase does validate the `oobCode` server-side, so a foreign link
     * cannot actually reset anything; the check is so we never hand a code from
     * an unrelated host to Firebase, nor show a reset form a stranger opened.
     */
    internal fun isTrustedAuthLink(data: Uri): Boolean = isTrustedAuthLink(data.scheme, data.host)

    private fun maybeCompleteEmailLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!isTrustedAuthLink(data)) return
        val link = data.toString()
        if (viewModel.repo.isEmailSignInLink(link)) viewModel.completeEmailLink(link)
    }

    /** Draws [AuthViewModel.screen]: the authenticator card for a challenge, else the form for its mode. */
    internal fun render() {
        if (viewModel.screen is AuthScreen.Totp) {
            totp.enterChallengeUi()
            return
        }
        totp.leaveChallengeUi()
        val mode = viewModel.screen.mode
        binding.tvSubtitle.setText(mode.subtitle)
        binding.tilEmail.isVisible = mode.showsEmail
        binding.tilConfirmPassword.isVisible = mode.choosesPassword
        binding.recoveryLinks.isVisible = mode.showsRecoveryLinks
        binding.tvPasswordRules.isVisible = mode.choosesPassword
        binding.tvPasswordRules.setText(R.string.password_hint_rules)
        // The clickwrap itself is TermsActivity, reached through AccessRouter
        // for every provider; this only tells password sign-ups what comes next.
        binding.tvRegisterTermsHint.isVisible = mode == AuthMode.REGISTER
        binding.btnGeneratePassword.isVisible = mode.choosesPassword
        binding.tvToggleMode.isVisible = mode.showsToggle
        val google = GoogleSignInHelper.isConfigured(this) && mode.offersGoogle
        binding.btnGoogleSignIn.isVisible = google
        binding.googleOrDivider.isVisible = google
        binding.btnMainAction.setText(mode.mainAction)
        binding.tvToggleMode.setText(
            if (mode == AuthMode.REGISTER) R.string.auth_toggle_to_login else R.string.auth_toggle_to_register,
        )
    }

    private fun onMainAction() {
        if (totp.isOpen) {
            totp.submit()
            return
        }
        val mode = viewModel.screen.mode
        if (mode == AuthMode.RESET_PASSWORD) {
            passwordReset.confirm()
            return
        }
        val email = binding.etEmail.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (!isValidEmail(email)) return
        if (mode == AuthMode.REGISTER) {
            val failure = PasswordPolicy.validate(password)
            if (failure != null) {
                showMessage(passwordFailureText(failure))
                return
            }
        } else {
            if (password.length < MIN_PASSWORD) {
                showMessage(getString(R.string.error_password_short))
                return
            }
        }
        if (mode == AuthMode.REAUTH) {
            viewModel.runReauth { reauthenticateWithPassword(password) }
            return
        }
        if (mode == AuthMode.REGISTER && password != binding.etConfirmPassword.text.toString()) {
            showMessage(getString(R.string.error_passwords_mismatch))
            return
        }
        // Kept until the outcome is known: a password is only worth saving once
        // the app has accepted it.
        viewModel.pendingCredential = email to password
        viewModel.runAuth {
            if (mode == AuthMode.REGISTER) {
                signUpWithPassword(email, password)
            } else {
                signInWithPassword(email, password)
            }
        }
    }

    private fun onSendEmailLink() {
        val email = binding.etEmail.text.toString().trim()
        if (!isValidEmail(email)) return
        viewModel.sendSignInLink(email)
    }

    /** Google's account picker is hosted by this screen; the token then goes to [AuthViewModel]. */
    private fun onGoogleSignIn() {
        viewModel.setBusy(true)
        lifecycleScope.launch {
            try {
                val idToken = GoogleSignInHelper.requestIdToken(this@AuthActivity)
                viewModel.withGoogleToken(idToken)
            } catch (e: GoogleSignInHelper.NotConfiguredException) {
                Timber.w(e, "Google sign-in is not configured")
                viewModel.setBusy(false)
                showMessage(getString(R.string.auth_google_unconfigured))
            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                Timber.d(e, "Google sign-in cancelled by user")
                viewModel.setBusy(false)
            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                Timber.w(e, "No Google account available for sign-in")
                viewModel.setBusy(false)
                showMessage(getString(R.string.auth_google_no_account))
            } catch (e: CancellationException) {
                // The screen is going away (or being recreated): there is no one
                // to tell, and "Job was cancelled" is not a sign-in failure.
                viewModel.setBusy(false)
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Credential Manager's own text is for the log (AE1-009).
                Timber.w(e, "Google sign-in failed")
                viewModel.setBusy(false)
                showMessage(getString(R.string.auth_google_failed))
            }
        }
    }

    /** What a call in [AuthViewModel] came to; each arrives once. */
    private suspend fun onOutcome(outcome: AuthViewModel.Outcome) {
        render()
        when (outcome) {
            is AuthViewModel.Outcome.SignedIn -> {
                // Awaited, not fired and forgotten: the save prompt is hosted by
                // this Activity, so navigating away first cancels it out from
                // under the user — which is exactly what it reported.
                outcome.credential?.let { (email, password) -> offerToSavePassword(email, password) }
                // Through the router so the Terms gate runs before Pending/Home
                // for every provider: password, Google, email link alike.
                startActivity(AccessRouter.intentFor(this, AccessRouter.afterSignIn(outcome.status)))
                finish()
            }
            AuthViewModel.Outcome.Reauthed -> {
                setResult(RESULT_OK)
                finish()
            }
            is AuthViewModel.Outcome.VerificationPending -> onVerificationPending(outcome.error)
            AuthViewModel.Outcome.TotpRequired -> totp.onOpened()
            is AuthViewModel.Outcome.ResetReady -> passwordReset.onReady(outcome.email)
            is AuthViewModel.Outcome.Say -> showMessage(outcome.text, outcome.long)
        }
    }

    /**
     * Asks the user's password manager to keep this pair.
     *
     * Best-effort and deliberately quiet: declining, having no provider, or an
     * older device all land in the same place — nothing was saved, and nothing
     * about the sign-in changes. Only offered for credentials the app itself
     * accepted, so a rejected password is never stored.
     */
    private suspend fun offerToSavePassword(email: String, password: String) {
        suspendRunCatching {
            CredentialManager.create(this)
                .createCredential(this, CreatePasswordRequest(email, password))
        }.onFailure { Timber.d(it, "Password not saved (declined or unsupported)") }
    }

    /** Shows the authenticator form with no live resolver; for tests ([AuthTotpController.enterChallengeUi]). */
    internal fun enterTotpChallengeUi() = totp.enterChallengeUi()

    /**
     * The account was created and its verification mail sent; there is no
     * session yet. Signing in is the next thing the user will do, so the screen
     * goes back to sign-in rather than leaving them on a Create account form
     * that has already done its job.
     */
    internal fun onVerificationPending(error: AuthRepository.EmailVerificationRequired) {
        // The account exists now, so this is the moment the password becomes
        // worth keeping — they will need it to sign in once the link is opened.
        // This screen stays up, so the prompt has a host and can be launched
        // without blocking the switch back to sign-in.
        viewModel.pendingCredential?.let { (email, password) ->
            lifecycleScope.launch { offerToSavePassword(email, password) }
        }
        viewModel.leaveRegister()
        render()
        binding.etEmail.setText(error.email)
        binding.etPassword.setText("")
        binding.etConfirmPassword.setText("")
        // Not an error: what they asked for happened, and the next step is theirs.
        // Held to the short 2000ms duration, not the default long 3500ms.
        showMessage(error.userMessageOr(getString(R.string.auth_verify_first)), long = false)
    }

    /** The bound is part of the message for the length rules, so they format it in. */
    internal fun passwordFailureText(failure: PasswordPolicy.Failure): String = when (failure) {
        is PasswordPolicy.Failure.TooShort ->
            resources.getQuantityString(R.plurals.password_too_short, failure.minLength, failure.minLength)
        is PasswordPolicy.Failure.TooLong ->
            resources.getQuantityString(R.plurals.password_too_long, failure.maxLength, failure.maxLength)
        is PasswordPolicy.Failure.Missing -> getString(failure.message)
    }

    internal fun isValidEmail(email: String): Boolean {
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showMessage(getString(R.string.error_email_invalid))
            return false
        }
        return true
    }

    internal fun setLoading(loading: Boolean) {
        binding.progressBar.setBusy(
            loading,
            binding.btnMainAction,
            binding.btnGoogleSignIn,
            binding.tvForgotPassword,
            binding.tvEmailLink,
            idleVisibility = View.GONE,
        )
    }

    /** Errors and confirmations alike: a crisp toast, long unless [long] says otherwise. */
    internal fun showMessage(message: String, long: Boolean = true) {
        CrispToast.show(this, message, long = long)
    }

    companion object {
        private const val MIN_PASSWORD = 6
        private const val EXTRA_REAUTH = "reauth"

        /**
         * Launch this screen to re-confirm the signed-in identity. Finishes with
         * `RESULT_OK` once any of its methods proves the account, `RESULT_CANCELED`
         * if the user backs out. The caller stays where it is either way.
         */
        fun reauthIntent(context: Context): Intent =
            Intent(context, AuthActivity::class.java).putExtra(EXTRA_REAUTH, true)
    }
}
