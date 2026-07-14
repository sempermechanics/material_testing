package com.rafad.indicvisiondic.ui.auth
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.AuthRepository
import com.rafad.indicvisiondic.ui.Insets
import com.rafad.indicvisiondic.ui.Motion
import com.rafad.indicvisiondic.ui.home.HomeActivity
import kotlinx.coroutines.launch

/**
 * Sign-in screen. Authentication is **Google-only** (Credential Manager one-tap
 * → Google ID token), verified by the inDIC backend, which enforces the
 * corporate hosted-domain and the APPROVED allow-list. On success, routing
 * depends on the account's approval status — see docs/CLOUD_ARCHITECTURE_GCP.md.
 */
class AuthActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }

    private lateinit var progressBar: ProgressBar
    private lateinit var btnGoogleSignIn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        // Email/password sign-in has been retired — hide those controls,
        // leaving Google sign-in as the single entry point.
        intArrayOf(
            R.id.layoutEmail, R.id.layoutPassword, R.id.layoutConfirmPassword,
            R.id.btnMainAction, R.id.tvToggleMode, R.id.tvForgotPassword,
        ).forEach { id -> findViewById<View?>(id)?.visibility = View.GONE }

        findViewById<TextView>(R.id.tvSubtitle).text = getString(R.string.secure_access_portal)

        progressBar = findViewById(R.id.progressBar)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)
        btnGoogleSignIn.setOnClickListener { handleGoogleSignIn() }

        Insets.padVertical(findViewById(R.id.authColumn))
        if (savedInstanceState == null) {
            Motion.enterStaggered(findViewById(R.id.authColumn))
        }

        val routingError = intent.getStringExtra(DicKeys.ROUTING_ERROR)
        if (routingError != null) {
            val isError = !routingError.contains("successfully logged out")
            showSnackbar(routingError, isError = isError)
        }
    }

    private fun handleGoogleSignIn() {
        setLoadingState(true)
        lifecycleScope.launch {
            try {
                val idToken = GoogleSignInHelper.getIdToken(
                    this@AuthActivity,
                    BuildConfig.GOOGLE_WEB_CLIENT_ID,
                )
                val result = authRepo.signInWithGoogle(idToken)
                setLoadingState(false)
                result.fold(
                    onSuccess = { status ->
                        val target = if (status == "PENDING") {
                            PendingApprovalActivity::class.java
                        } else {
                            HomeActivity::class.java // APPROVED / OFFLINE_CACHE_APPROVED
                        }
                        startActivity(Intent(this@AuthActivity, target))
                        finish()
                    },
                    onFailure = { showSnackbar(it.message ?: "Google sign-in failed.", isError = true) },
                )
            } catch (e: GoogleSignInHelper.NotConfigured) {
                setLoadingState(false)
                showSnackbar("Google sign-in isn't set up yet (see GOOGLE_SSO_SETUP.md).", isError = true)
            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                setLoadingState(false) // user dismissed the sheet — not an error
            } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                setLoadingState(false)
                showSnackbar("No Google account available on this device.", isError = true)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                setLoadingState(false)
                showSnackbar(e.message ?: "Google sign-in failed.", isError = true)
            }
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        btnGoogleSignIn.isEnabled = !isLoading
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun showSnackbar(message: String, isError: Boolean) {
        val rootView = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)
        snackbar.setBackgroundTint(if (isError) Color.parseColor("#D32F2F") else Color.parseColor("#388E3C"))
        snackbar.setTextColor(Color.WHITE)
        snackbar.show()
    }
}
