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
import com.rafad.indicvisiondic.ui.Insets
import com.rafad.indicvisiondic.ui.home.HomeActivity
import kotlinx.coroutines.launch

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
        tvEmailLink = findViewById(R.id.tvForgotPassword) // repurposed → passwordless link
        progressBar = findViewById(R.id.progressBar)
        btnGoogle = findViewById(R.id.btnGoogleSignIn)

        tvEmailLink.text = getString(R.string.auth_email_link)
        btnGoogle.visibility = if (GoogleSignInHelper.isConfigured(this)) View.VISIBLE else View.GONE

        btnMain.setOnClickListener { onMainAction() }
        tvToggle.setOnClickListener { registerMode = !registerMode; updateMode() }
        tvEmailLink.setOnClickListener { onSendEmailLink() }
        btnGoogle.setOnClickListener { onGoogleSignIn() }
        updateMode()

        Insets.padVertical(findViewById(R.id.authColumn))

        // Arriving via a tapped email sign-in link?
        intent?.data?.toString()?.let { link ->
            if (authRepo.isEmailSignInLink(link)) completeEmailLink(link)
        }

        intent.getStringExtra(DicKeys.ROUTING_ERROR)?.let { msg ->
            showSnackbar(msg, isError = !msg.contains("successfully logged out"))
        }
    }

    private fun updateMode() {
        findViewById<TextView>(R.id.tvSubtitle).text = getString(R.string.secure_access_portal)
        layoutConfirm.visibility = if (registerMode) View.VISIBLE else View.GONE
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
            showSnackbar(getString(R.string.error_password_short), isError = true); return
        }
        if (registerMode && password != etConfirm.text.toString()) {
            showSnackbar("Passwords do not match.", isError = true); return
        }
        runAuth {
            if (registerMode) authRepo.signUpWithPassword(email, password)
            else authRepo.signInWithPassword(email, password)
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
                onFailure = { showSnackbar(it.message ?: "Could not send the link.", isError = true) },
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
                setLoading(false)
                showSnackbar(getString(R.string.auth_google_unconfigured), isError = true)
            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                setLoading(false)
            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                setLoading(false)
                showSnackbar("No Google account available on this device.", isError = true)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                setLoading(false)
                showSnackbar(e.message ?: "Google sign-in failed.", isError = true)
            }
        }
    }

    private fun completeEmailLink(link: String) {
        val email = authRepo.pendingLinkEmail()
        if (email.isNullOrBlank()) {
            showSnackbar("Open the sign-in link on the device that requested it.", isError = true)
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
                val target = if (status == "PENDING") {
                    PendingApprovalActivity::class.java
                } else {
                    HomeActivity::class.java // APPROVED / OFFLINE_CACHE_APPROVED
                }
                startActivity(Intent(this, target))
                finish()
            },
            onFailure = { showSnackbar(it.message ?: "Sign-in failed.", isError = true) },
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
