package com.rafad.indicvisiondic.ui.auth
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.AuthRepository
import com.rafad.indicvisiondic.ui.home.HomeActivity
import kotlinx.coroutines.launch

/**
 * App entry point: restores an existing backend session and routes to
 * [com.rafad.indicvisiondic.ui.home.HomeActivity] (approved user), [PendingApprovalActivity]
 * (account awaiting admin approval), or [AuthActivity] (signed out).
 */
class SplashActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }

    private val spinnerHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Spinner only if routing takes longer than 400 ms — a flash on a
        // fast session restore reads as slowness.
        val spinner = findViewById<android.widget.ProgressBar>(R.id.progressBar)
        spinnerHandler.postDelayed({ spinner.visibility = android.view.View.VISIBLE }, SPINNER_DELAY_MS)

        // Using lifecycleScope ensures that if the user minimizes or closes
        // the app while it's loading, it doesn't crash trying to update UI.
        lifecycleScope.launch {
            performRoutingCheck()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        spinnerHandler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val SPINNER_DELAY_MS = 400L
    }

    private suspend fun performRoutingCheck() {
        try {
            // Offline dev convenience: skip auth ONLY when no backend is
            // configured. With INDIC_API_BASE_URL set, always run real auth
            // (so device testing exercises the full sign-in + upload path).
            if (BuildConfig.DEBUG && !authRepo.cloudConfigured) {
                navigateTo(HomeActivity::class.java)
                return
            }

            // 1. Is there a saved backend session on this device?
            if (!authRepo.hasSession()) {
                navigateTo(AuthActivity::class.java)
                return
            }

            // 2. Ask the backend for the current authorization status (with an
            //    offline bypass when previously approved).
            authRepo.refreshStatus().fold(
                onSuccess = { status ->
                    when (status) {
                        "APPROVED" -> navigateTo(HomeActivity::class.java)
                        "OFFLINE_CACHE_APPROVED" -> {
                            android.widget.Toast.makeText(this, "Offline Mode", android.widget.Toast.LENGTH_LONG).show()
                            navigateTo(HomeActivity::class.java)
                        }
                        "PENDING" -> navigateTo(PendingApprovalActivity::class.java)
                        else -> navigateTo(AuthActivity::class.java, "System error: Unknown account status.")
                    }
                },
                onFailure = { exception ->
                    navigateTo(AuthActivity::class.java, exception.message ?: "Could not verify account securely.")
                },
            )
        } catch (e: Exception) {
            navigateTo(AuthActivity::class.java, "A critical system error occurred during startup.")
        }
    }

    private fun navigateTo(targetActivity: Class<*>, errorMessage: String? = null) {
        val intent = Intent(this, targetActivity)

        // If an error occurred, package it up and send it to AuthActivity
        // so we can display it nicely in the UI.
        if (errorMessage != null) {
            intent.putExtra(DicKeys.ROUTING_ERROR, errorMessage)
        }

        startActivity(intent)
        // CRITICAL: finish() destroys the SplashActivity so the user can't hit "Back" to return to it.
        finish()
    }
}
