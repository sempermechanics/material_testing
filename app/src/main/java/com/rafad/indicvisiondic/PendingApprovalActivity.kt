package com.rafad.indicvisiondic

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PendingApprovalActivity : AppCompatActivity() {

    private val authRepo = AuthRepository()
    private lateinit var keyManager: DeviceKeyManager

    // UI Elements
    private lateinit var tvUserEmail: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var btnRefreshStatus: Button
    private lateinit var tvLogout: TextView
    private lateinit var progressLoading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending_approval)

        keyManager = DeviceKeyManager(this)

        // Bind UI
        tvUserEmail = findViewById(R.id.tvUserEmail)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)
        tvLogout = findViewById(R.id.tvLogout)
        progressLoading = findViewById(R.id.progressLoading)

        // 1. Load Profile Data immediately
        loadProfileData()

        // 2. Set up Listeners
        btnRefreshStatus.setOnClickListener {
            checkStatusAgain()
        }

        tvLogout.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    private fun loadProfileData() {
        // Safely fetch current user info from the local Supabase session vault
        val user = SupabaseManager.client.auth.currentUserOrNull()
        val deviceId = keyManager.getDeviceId()

        tvUserEmail.text = user?.email ?: "Unknown User"
        tvDeviceId.text = "Hardware ID: ${deviceId.take(8)}...${deviceId.takeLast(4)}"
    }

    private fun checkStatusAgain() {
        setLoadingState(true)

        lifecycleScope.launch {
            val deviceId = keyManager.getDeviceId()

            authRepo.checkUserAccessStatus(deviceId).fold(
                onSuccess = { status ->
                    setLoadingState(false)
                    when (status) {
                        "APPROVED" -> {
                            // The admin approved them! Route to the Main App.
                            Toast.makeText(this@PendingApprovalActivity, "Access Granted!", Toast.LENGTH_SHORT).show()
                            val intent = Intent(this@PendingApprovalActivity, StaticAnalysisActivity::class.java)
                            startActivity(intent)
                            finish()
                        }
                        "PENDING" -> {
                            // Still waiting.
                            Toast.makeText(this@PendingApprovalActivity, "Account is still pending approval.", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            // Something went wrong (e.g., REVOKED). The repo already signed them out.
                            routeToLogin("Your access status has changed. Please log in again.")
                        }
                    }
                },
                onFailure = { exception ->
                    setLoadingState(false)
                    Toast.makeText(this@PendingApprovalActivity, exception.message ?: "Network error. Try again.", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    // --- SECURE EXIT PROTOCOL ---

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Log Out?")
            .setMessage("Are you sure you want to log out of inDIC on this device?")
            .setPositiveButton("Log Out") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        setLoadingState(true)
        lifecycleScope.launch {
            try {
                // 1. Destroy the session on the server and local vault
                SupabaseManager.client.auth.signOut()
            } catch (e: Exception) {
                // Even if the network fails, we force them out locally to ensure security
                android.util.Log.e("inDIC_Auth", "Server logout failed, forcing local exit.", e)
            } finally {
                // 2. Burn the bridge and route to login
                routeToLogin("You have been successfully logged out.")
            }
        }
    }

    private fun routeToLogin(message: String) {
        val intent = Intent(this, AuthActivity::class.java)
        intent.putExtra("ROUTING_ERROR", message)
        // CLEAR_TASK and NEW_TASK wipe the Android backstack completely
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            btnRefreshStatus.text = ""
            btnRefreshStatus.isEnabled = false
            tvLogout.isEnabled = false
            progressLoading.visibility = View.VISIBLE
        } else {
            btnRefreshStatus.text = "CHECK STATUS"
            btnRefreshStatus.isEnabled = true
            tvLogout.isEnabled = true
            progressLoading.visibility = View.GONE
        }
    }
}