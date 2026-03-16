package com.rafad.indicvisiondic

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private lateinit var cardStatus: CardView
    private lateinit var tvStatusMessage: TextView
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
        cardStatus = findViewById(R.id.cardStatus)
        tvStatusMessage = findViewById(R.id.tvStatusMessage)
        progressBar = findViewById(R.id.progressBar)
        layoutConfirmPassword = findViewById(R.id.layoutConfirmPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)

        setupUIForCurrentMode()

        // Setup Listeners
        tvToggleMode.setOnClickListener {
            isLoginMode = !isLoginMode // Flip the mode
            setupUIForCurrentMode()
            hideStatus() // Clear any old errors
        }

        tvForgotPassword.setOnClickListener {
            handleForgotPassword()
        }

        btnMainAction.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                showStatus("Please enter both email and password.", isError = true)
                return@setOnClickListener
            }

            if (password.length < 6) {
                showStatus("Password must be at least 6 characters.", isError = true)
                return@setOnClickListener
            }

            // ONLY check confirm password if we are in Registration Mode
            if (!isLoginMode) {
                if (password != confirmPassword) {
                    showStatus("Passwords do not match!", isError = true)
                    return@setOnClickListener
                }
            }

            executeAuthAction(email, password)
        }
    }

    // --- LOGIC FUNCTIONS ---

    private fun setupUIForCurrentMode() {
        if (isLoginMode) {
            tvSubtitle.text = "Secure Access Portal"
            btnMainAction.text = "SECURE LOGIN"
            tvToggleMode.text = "Need access? Request an account"
            tvForgotPassword.visibility = View.VISIBLE
            layoutConfirmPassword.visibility = View.GONE // Hide confirm box
        } else {
            tvSubtitle.text = "Beta Registration Request"
            btnMainAction.text = "SUBMIT REQUEST"
            tvToggleMode.text = "Already have an account? Login here"
            tvForgotPassword.visibility = View.GONE
            layoutConfirmPassword.visibility = View.VISIBLE // Show confirm box
        }
    }

    private fun executeAuthAction(email: String, pass: String) {
        setLoadingState(true)

        CoroutineScope(Dispatchers.Main).launch {
            // Explicitly tell the compiler this is a Kotlin Result<String>
            val result: Result<String> = if (isLoginMode) {
                authRepo.loginUser(email, pass)
            } else {
                authRepo.registerUser(email, pass)
            }

            setLoadingState(false)

            // Fold automatically splits into Success and Failure paths!
            result.fold(
                onSuccess = { message ->
                    showStatus(message, isError = false)
                    if (isLoginMode) {
                        // The user is fully approved and logged in!
                        val intent = android.content.Intent(this@AuthActivity, StaticAnalysisActivity::class.java)
                        startActivity(intent)
                        finish()
                    }
                },
                onFailure = { exception ->
                    showStatus(exception.message ?: "An unknown error occurred", isError = true)
                }
            )
        }
    }

    private fun handleForgotPassword() {
        val email = etEmail.text.toString().trim()
        if (email.isEmpty()) {
            showStatus("Please enter your email address to reset password.", isError = true)
            return
        }

        setLoadingState(true)
        CoroutineScope(Dispatchers.Main).launch {
            val result: Result<String> = authRepo.resetPassword(email)
            setLoadingState(false)

            result.fold(
                onSuccess = { message ->
                    showStatus(message, isError = false)
                },
                onFailure = { exception ->
                    showStatus(exception.message ?: "Failed to send reset email.", isError = true)
                }
            )
        }
    }

    // --- UI HELPER FUNCTIONS ---

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            btnMainAction.text = ""
            btnMainAction.isEnabled = false
            progressBar.visibility = View.VISIBLE
            hideStatus()
        } else {
            btnMainAction.isEnabled = true
            progressBar.visibility = View.GONE
            setupUIForCurrentMode() // Restores the button text
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        cardStatus.visibility = View.VISIBLE
        tvStatusMessage.text = message
        if (isError) {
            cardStatus.setCardBackgroundColor(Color.parseColor("#44FF0000")) // Faded Red
            tvStatusMessage.setTextColor(Color.parseColor("#FF6B6B"))
        } else {
            cardStatus.setCardBackgroundColor(Color.parseColor("#4400FF00")) // Faded Green
            tvStatusMessage.setTextColor(Color.parseColor("#4CAF50"))
        }
    }

    private fun hideStatus() {
        cardStatus.visibility = View.GONE
    }
}