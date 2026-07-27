// Auth screen: one handler per sign-in path (Google, email link, password) plus
// their validation guards, so TooManyFunctions / ReturnCount are suppressed here.
@file:Suppress("TooManyFunctions", "ReturnCount")

package com.rafad.indicvisiondic.ui.auth
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.AuthRepository
import com.rafad.indicvisiondic.ui.common.Insets
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
class AuthActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }
    private var registerMode = false

    private lateinit var progressBar: ProgressBar
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var layoutConfirm: View
    private lateinit var etConfirm: EditText
    private lateinit var btnMain: Button
    private lateinit var tvToggle: TextView
    private lateinit var recoveryLinks: View
    private lateinit var tvForgotPassword: TextView
    private lateinit var tvEmailLink: TextView
    private lateinit var btnGoogle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        layoutConfirm = findViewById(R.id.layoutConfirmPassword)
        etConfirm = findViewById(R.id.etConfirmPassword)
        btnMain = findViewById(R.id.btnMainAction)
        tvToggle = findViewById(R.id.tvToggleMode)
        recoveryLinks = findViewById(R.id.recoveryLinks)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        tvEmailLink = findViewById(R.id.tvEmailLink)
        progressBar = findViewById(R.id.progressBar)
        btnGoogle = findViewById(R.id.btnGoogleSignIn)
        val googleOrDivider = findViewById<View>(R.id.googleOrDivider)
        val googleConfigured = GoogleSignInHelper.isConfigured(this)
        btnGoogle.visibility = if (googleConfigured) View.VISIBLE else View.GONE
        googleOrDivider.visibility = if (googleConfigured) View.VISIBLE else View.GONE

        btnMain.setOnClickListener { onMainAction() }
        tvToggle.setOnClickListener {
            registerMode = !registerMode
            updateMode()
        }
        tvForgotPassword.setOnClickListener { onForgotPassword() }
        tvEmailLink.setOnClickListener { onSendEmailLink() }
        btnGoogle.setOnClickListener { onGoogleSignIn() }
        updateMode()

        // Pad the scroll container (not the inner column) so the keyboard inset
        // shrinks the viewport and the focused field scrolls clear of the IME.
        Insets.padTopAndImeBottom(findViewById(R.id.rootLayout))

        // Arriving via a tapped email sign-in link?
        maybeCompleteEmailLink(intent)

        intent.getStringExtra(DicKeys.ROUTING_ERROR)?.let { msg ->
            showSnackbar(msg, isError = msg != getString(R.string.logout_success))
        }
    }

    /** The email sign-in link may arrive while this activity is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        maybeCompleteEmailLink(intent)
    }

    private fun maybeCompleteEmailLink(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        if (authRepo.isEmailSignInLink(link)) completeEmailLink(link)
    }

    private fun updateMode() {
        findViewById<TextView>(R.id.tvSubtitle).text = getString(R.string.secure_access_portal)
        layoutConfirm.visibility = if (registerMode) View.VISIBLE else View.GONE
        // Password recovery and the sign-in link only make sense when signing in.
        recoveryLinks.visibility = if (registerMode) View.GONE else View.VISIBLE
        btnMain.text = getString(if (registerMode) R.string.auth_create_account else R.string.auth_sign_in)
        tvToggle.text = getString(
            if (registerMode) R.string.auth_toggle_to_login else R.string.auth_toggle_to_register,
        )
    }

    private fun onMainAction() {
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()
        if (!validEmail(email)) return
        if (password.length < MIN_PASSWORD) {
            showSnackbar(getString(R.string.error_password_short), isError = true)
            return
        }
        if (registerMode && password != etConfirm.text.toString()) {
            showSnackbar(getString(R.string.error_passwords_mismatch), isError = true)
            return
        }
        runAuth {
            if (registerMode) {
                authRepo.signUpWithPassword(email, password)
            } else {
                authRepo.signInWithPassword(email, password)
            }
        }
    }

    private fun onForgotPassword() {
        val email = etEmail.text.toString().trim()
        if (!validEmail(email)) return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.sendPasswordReset(email)
            setLoading(false)
            result.fold(
                onSuccess = { showSnackbar(getString(R.string.auth_reset_sent, email), isError = false) },
                onFailure = {
                    showSnackbar(it.message ?: getString(R.string.auth_reset_failed), isError = true)
                },
            )
        }
    }

    private fun onSendEmailLink() {
        val email = etEmail.text.toString().trim()
        if (!validEmail(email)) return
        setLoading(true)
        lifecycleScope.launch {
            val result = authRepo.sendSignInLink(email)
            setLoading(false)
            result.fold(
                onSuccess = { showSnackbar(getString(R.string.auth_link_sent, email), isError = false) },
                onFailure = {
                    showSnackbar(it.message ?: getString(R.string.auth_link_send_failed), isError = true)
                },
            )
        }
    }

    private fun onGoogleSignIn() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val idToken = GoogleSignInHelper.getIdToken(this@AuthActivity)
                routeResult(authRepo.signInWithGoogle(idToken))
            } catch (e: GoogleSignInHelper.NotConfigured) {
                Timber.w(e, "Google sign-in is not configured")
                setLoading(false)
                showSnackbar(getString(R.string.auth_google_unconfigured), isError = true)
            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                Timber.d(e, "Google sign-in cancelled by user")
                setLoading(false)
            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                Timber.w(e, "No Google account available for sign-in")
                setLoading(false)
                showSnackbar(getString(R.string.auth_google_no_account), isError = true)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                setLoading(false)
                showSnackbar(e.message ?: getString(R.string.auth_google_failed), isError = true)
            }
        }
    }

    private fun completeEmailLink(link: String) {
        val email = authRepo.pendingLinkEmail()
        if (email.isNullOrBlank()) {
            showSnackbar(getString(R.string.auth_link_wrong_device), isError = true)
            return
        }
        runAuth { authRepo.completeEmailLink(email, link) }
    }

    /** Run an auth call that resolves to an access status, and route on the result. */
    private fun runAuth(call: suspend () -> Result<String>) {
        setLoading(true)
        lifecycleScope.launch {
            routeResult(call())
        }
    }

    private fun routeResult(result: Result<String>) {
        setLoading(false)
        result.fold(
            onSuccess = { status ->
                startActivity(Intent(this, AccessRouter.afterSignIn(status)))
                finish()
            },
            onFailure = { showSnackbar(it.message ?: getString(R.string.auth_sign_in_failed), isError = true) },
        )
    }

    private fun validEmail(email: String): Boolean {
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showSnackbar(getString(R.string.error_email_invalid), isError = true)
            return false
        }
        return true
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnMain.isEnabled = !loading
        btnGoogle.isEnabled = !loading
        tvForgotPassword.isEnabled = !loading
        tvEmailLink.isEnabled = !loading
    }

    private fun showSnackbar(message: String, isError: Boolean) {
        val snackbar = Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG)
        snackbar.setBackgroundTint(if (isError) Color.parseColor("#D32F2F") else Color.parseColor("#388E3C"))
        snackbar.setTextColor(Color.WHITE)
        snackbar.show()
    }

    private companion object {
        const val MIN_PASSWORD = 6
    }
}
