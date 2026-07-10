package com.rafad.indicvisiondic.ui.auth
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.DicKeys
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.AuthRepository
import com.rafad.indicvisiondic.data.DeviceKeyManager
import com.rafad.indicvisiondic.data.SupabaseManager
import com.rafad.indicvisiondic.ui.home.HomeActivity
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

/**
 * App entry point: restores an existing Supabase session and routes to
 * [com.rafad.indicvisiondic.ui.home.HomeActivity] (approved user), [PendingApprovalActivity]
 * (account awaiting admin approval), or [AuthActivity] (signed out).
 */
class SplashActivity : AppCompatActivity() {

    private val authRepo = AuthRepository()

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
            // 1. SILENT VAULT CHECK: Is there a session saved on the device?
            val session = SupabaseManager.client.auth.currentSessionOrNull()

            if (BuildConfig.DEBUG) {
                navigateTo(HomeActivity::class.java)
                return
            }

            if (session == null) {
                navigateTo(AuthActivity::class.java)
                return
            }

            // 2. SESSION EXISTS: Grab the physical hardware ID
            val deviceKeyManager = DeviceKeyManager(this@SplashActivity)
            val currentDeviceId = deviceKeyManager.getDeviceId()

            // 3. SERVER TRUTH: Ask Supabase for their real-time authorization status
            val statusResult = authRepo.checkUserAccessStatus(currentDeviceId)

            statusResult.fold(
                onSuccess = { status ->
                    when (status) {
                        "APPROVED" -> navigateTo(HomeActivity::class.java)
                        "OFFLINE_CACHE_APPROVED" -> {
                            // THE OFFLINE BYPASS: They have a token but no Wi-Fi. Let them work!
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
