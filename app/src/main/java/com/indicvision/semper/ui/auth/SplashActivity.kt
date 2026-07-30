package com.indicvision.semper.ui.auth
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.AccessStatus
import com.indicvision.semper.data.AuthRepository
import com.indicvision.semper.data.DevAuth
import com.indicvision.semper.ui.home.HomeActivity
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * App entry point: restores an existing backend session and routes to
 * [com.indicvision.semper.ui.home.HomeActivity] (approved user), [PendingApprovalActivity]
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
            // Dev shortcuts: emulator bypass, or no backend configured at all.
            if (routeDevShortcut()) return

            // 1. Is there a saved backend session on this device?
            if (!authRepo.hasSession()) {
                navigateTo(AuthActivity::class.java)
                return
            }

            // 2. Ask the backend for the current authorization status (with an
            //    offline bypass when previously approved).
            authRepo.refreshStatus().fold(
                onSuccess = { status ->
                    val target = AccessRouter.afterRefresh(status)
                    when (target) {
                        HomeActivity::class.java -> {
                            if (status == AccessStatus.OFFLINE_CACHE_APPROVED) {
                                android.widget.Toast.makeText(
                                    this,
                                    R.string.status_offline_mode,
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                            navigateTo(target)
                        }
                        PendingApprovalActivity::class.java -> navigateTo(target)
                        else -> navigateTo(
                            AuthActivity::class.java,
                            getString(R.string.error_unknown_status),
                        )
                    }
                },
                onFailure = { exception ->
                    val message = exception.message ?: getString(R.string.error_verify_failed)
                    navigateTo(AuthActivity::class.java, message)
                },
            )
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Timber.w(e, "Splash startup failed; routing to sign-in")
            navigateTo(AuthActivity::class.java, getString(R.string.error_startup_failed))
        }
    }

    /**
     * Debug-build routes that skip sign-in. Returns true when one applied and
     * the user has already been sent on to Home.
     *
     *  1. Emulator dev run: boot as a local-only dev account (see [DevAuth],
     *     which also switches the cloud off for the run).
     *  2. No backend configured: skip auth ONLY then. With INDIC_API_BASE_URL
     *     set on a real device, always run real auth so device testing
     *     exercises the full sign-in + upload path.
     */
    private fun routeDevShortcut(): Boolean {
        if (DevAuth.active) {
            DevAuth.install(this)
            android.widget.Toast.makeText(
                this,
                "Dev sign-in bypass (emulator) — cloud disabled",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } else if (!(BuildConfig.DEBUG && !authRepo.cloudConfigured)) {
            return false
        }
        navigateTo(HomeActivity::class.java)
        return true
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
