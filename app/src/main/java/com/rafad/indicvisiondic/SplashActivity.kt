package com.rafad.indicvisiondic

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    private val authRepo = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Using lifecycleScope ensures that if the user minimizes or closes
        // the app while it's loading, it doesn't crash trying to update UI.
        lifecycleScope.launch {
            performRoutingCheck()
        }
    }

    private suspend fun performRoutingCheck() {
        try {

            if (BuildConfig.DEBUG) {
                navigateTo(StaticAnalysisActivity::class.java)
                return
            }

            // 1. SILENT VAULT CHECK: Is there a session saved on the device?
            val session = SupabaseManager.client.auth.currentSessionOrNull()

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
                        "APPROVED" -> navigateTo(StaticAnalysisActivity::class.java)
                        "OFFLINE_CACHE_APPROVED" -> {
                            // 🚀 THE OFFLINE BYPASS: They have a token but no Wi-Fi. Let them work!
                            android.widget.Toast.makeText(this, "Offline Mode", android.widget.Toast.LENGTH_LONG).show()
                            navigateTo(StaticAnalysisActivity::class.java)
                        }
                        "PENDING" -> navigateTo(PendingApprovalActivity::class.java)
                        else -> navigateTo(AuthActivity::class.java, "System error: Unknown account status.")
                    }
                },
                onFailure = { exception ->
                    navigateTo(AuthActivity::class.java, exception.message ?: "Could not verify account securely.")
                }
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
            intent.putExtra("ROUTING_ERROR", errorMessage)
        }

        startActivity(intent)
        // CRITICAL: finish() destroys the SplashActivity so the user can't hit "Back" to return to it.
        finish()
    }
}