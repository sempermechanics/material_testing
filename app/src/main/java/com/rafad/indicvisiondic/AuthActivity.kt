package com.rafad.indicvisiondic

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar // Added for modern error messages
import com.rafad.indicvisiondic.ui.Motion
import com.rafad.indicvisiondic.ui.Insets
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {

    private val authRepo = AuthRepository()
    private var isLoginMode = true // Tracks which screen we are currently showing

    // UI Elements
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnMainAction: Button
    private lateinit var tvToggleMode: TextView
    private lateinit var tvForgotPassword: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var layoutConfirmPassword: View
    private lateinit var etConfirmPassword: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        // Initialize UI Elements
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnMainAction = findViewById(R.id.btnMainAction)
        tvToggleMode = findViewById(R.id.tvToggleMode)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        progressBar = findViewById(R.id.progressBar)
        layoutConfirmPassword = findViewById(R.id.layoutConfirmPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)

        setupUIForCurrentMode()

        // Edge-to-edge: keep the form clear of the status bar and nav bar
        Insets.padVertical(findViewById(R.id.authColumn))

        // Gentle entrance on first show
        if (savedInstanceState == null) {
            Motion.enterStaggered(findViewById(R.id.authColumn))
        }

        // Setup Listeners
        tvToggleMode.setOnClickListener {
            isLoginMode = !isLoginMode // Flip the mode
            // Animate the confirm-password field sliding in/out instead of snapping
            Motion.animateExpandCollapse(findViewById<ViewGroup>(R.id.authColumn))
            setupUIForCurrentMode()
        }

        tvForgotPassword.setOnClickListener {
            handleForgotPassword()
        }

        btnMainAction.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                showSnackbar("Please enter both email and password.", isError = true)
                return@setOnClickListener
            }

            if (password.length < 6) {
                showSnackbar("Password must be at least 6 characters.", isError = true)
                return@setOnClickListener
            }

            if (!isLoginMode && password != confirmPassword) {
                showSnackbar("Passwords do not match!", isError = true)
                return@setOnClickListener
            }

            executeAuthAction(email, password)
        }

        // Check if the Gatekeeper (SplashActivity) routed us here with an error
        val routingError = intent.getStringExtra("ROUTING_ERROR")
        if (routingError != null) {
            // Check if it's a successful logout message vs a real error
            val isError = !routingError.contains("successfully logged out")
            showSnackbar(routingError, isError = isError)
        }
    }

    private fun setupUIForCurrentMode() {
        if (isLoginMode) {
            tvSubtitle.text = "Secure Access Portal"
            btnMainAction.text = "Secure Login"
            tvToggleMode.text = "Need access? Request an account"
            tvForgotPassword.visibility = View.VISIBLE
            layoutConfirmPassword.visibility = View.GONE
        } else {
            tvSubtitle.text = "Beta Registration Request"
            btnMainAction.text = "Submit Request"
            tvToggleMode.text = "Already have an account? Login here"
            tvForgotPassword.visibility = View.GONE
            layoutConfirmPassword.visibility = View.VISIBLE
        }
    }

    private fun executeAuthAction(email: String, pass: String) {
        setLoadingState(true)

        val keyManager = DeviceKeyManager(this)
        val myDeviceId = keyManager.getDeviceId()
        val myPublicKey = keyManager.getPublicKeyBase64()

        lifecycleScope.launch {
            val result: Result<String> = if (isLoginMode) {
                // 🚀 FIX: Pass the public key during login so it can self-heal!
                authRepo.loginUser(email, pass, myDeviceId, myPublicKey)
            } else {
                authRepo.registerUser(email, pass, myDeviceId, myPublicKey)
            }

            setLoadingState(false)

            result.fold(
                onSuccess = {
                    // Success! Let the Gatekeeper handle the routing.
                    val intent = Intent(this@AuthActivity, SplashActivity::class.java)
                    startActivity(intent)
                    finish()
                },
                onFailure = { exception ->
                    val errorMsg = exception.message ?: "An unknown error occurred"

                    if (errorMsg.contains("pending", ignoreCase = true)) {
                        val intent = Intent(this@AuthActivity, PendingApprovalActivity::class.java)
                        startActivity(intent)
                        finish()
                    } else {
                        showSnackbar(errorMsg, isError = true)
                    }
                }
            )
        }
    }

    private fun handleForgotPassword() {
        val email = etEmail.text.toString().trim()
        if (email.isEmpty()) {
            showSnackbar("Please enter your email address to reset password.", isError = true)
            return
        }

        setLoadingState(true)
        lifecycleScope.launch {
            val result: Result<String> = authRepo.resetPassword(email)
            setLoadingState(false)

            result.fold(
                onSuccess = { message -> showSnackbar(message, isError = false) },
                onFailure = { exception -> showSnackbar(exception.message ?: "Failed to send reset email.", isError = true) }
            )
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            btnMainAction.text = ""
            btnMainAction.isEnabled = false
            progressBar.visibility = View.VISIBLE
        } else {
            btnMainAction.isEnabled = true
            progressBar.visibility = View.GONE
            setupUIForCurrentMode() // Restores the button text
        }
    }

    // --- NEW SNACKBAR FUNCTION ---
    private fun showSnackbar(message: String, isError: Boolean) {
        // This natively grabs the screen root, making it 100% crash-proof
        val rootView = findViewById<View>(android.R.id.content)
        val snackbar = Snackbar.make(rootView, message, Snackbar.LENGTH_LONG)

        if (isError) {
            snackbar.setBackgroundTint(Color.parseColor("#D32F2F")) // Material Red
        } else {
            snackbar.setBackgroundTint(Color.parseColor("#388E3C")) // Material Green
        }

        snackbar.setTextColor(Color.WHITE)
        snackbar.show()
    }
}