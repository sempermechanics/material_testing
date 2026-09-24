// Pending-approval screen: literal poll interval / UI constants read clearest inline.
@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.auth

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.BuildConfig
import com.indicvision.semper.DicKeys
import com.indicvision.semper.R
import com.indicvision.semper.data.AuthRepository
import com.indicvision.semper.data.DeviceKeyManager
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.ui.home.HomeActivity
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Holding screen for authenticated accounts whose backend access_status is
 * still PENDING. Polls for approval and routes onward once granted.
 */
class PendingApprovalActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }

    // UI Elements
    private lateinit var tvUserEmail: TextView
    private lateinit var tvDeviceId: TextView
    private lateinit var btnRequestAccess: Button
    private lateinit var btnRefreshStatus: Button
    private lateinit var tvLogout: TextView
    private lateinit var progressLoading: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pending_approval)
        window.decorView.post { reportFullyDrawn() }

        Insets.padVertical(findViewById(R.id.pendingRoot))

        tvUserEmail = findViewById(R.id.tvUserEmail)
        tvDeviceId = findViewById(R.id.tvDeviceId)
        btnRequestAccess = findViewById(R.id.btnRequestAccess)
        btnRefreshStatus = findViewById(R.id.btnRefreshStatus)
        tvLogout = findViewById(R.id.tvLogout)
        progressLoading = findViewById(R.id.progressLoading)

        // 1. Load Profile Data immediately
        loadProfileData()

        // 2. Set up Listeners
        btnRequestAccess.setOnClickListener {
            requestAccessByEmail()
        }

        btnRefreshStatus.setOnClickListener {
            checkStatusAgain()
        }

        tvLogout.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    /** Opens the user's email app pre-filled to support so they can request access. */
    private fun requestAccessByEmail() {
        val email = authRepo.cachedEmail() ?: getString(R.string.pending_unknown_account)
        val deviceId = DeviceKeyManager.deviceId(this)
        val body = buildString {
            append("I'd like access to Semper.\n\n")
            append("Account: ").append(email).append('\n')
            append("Device ID: ").append(deviceId).append('\n')
            append("App: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" — Android ").append(Build.VERSION.RELEASE)
        }
        val support = getString(R.string.support_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = "mailto:".toUri()
            putExtra(Intent.EXTRA_EMAIL, arrayOf(support))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.request_access_subject) + " — " + email)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No email app to send the access request")
            Toast.makeText(this, getString(R.string.request_access_none, support), Toast.LENGTH_LONG).show()
        }
    }

    private fun loadProfileData() {
        // Identity comes from the cached backend session (ID-token claims).
        val email = authRepo.cachedEmail()
        val deviceId = DeviceKeyManager.deviceId(this)

        tvUserEmail.text = email ?: getString(R.string.pending_unknown_user)
        tvDeviceId.text = getString(R.string.pending_device_id_fmt, deviceId.take(8), deviceId.takeLast(4))
    }

    private fun checkStatusAgain() {
        setLoadingState(true)

        lifecycleScope.launch {
            authRepo.refreshStatus().fold(
                onSuccess = { status ->
                    setLoadingState(false)
                    when (val target = AccessRouter.afterRefresh(status, stayOnPending = true)) {
                        null -> {
                            // Still PENDING — stay on this screen.
                            Toast.makeText(
                                this@PendingApprovalActivity,
                                R.string.status_still_pending,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        HomeActivity::class.java -> {
                            Toast.makeText(
                                this@PendingApprovalActivity,
                                R.string.status_access_granted,
                                Toast.LENGTH_SHORT,
                            ).show()
                            startActivity(AccessRouter.intentFor(this@PendingApprovalActivity, target))
                            finish()
                        }
                        else -> {
                            // Unexpected status — force a clean re-login.
                            routeToLogin(getString(R.string.status_changed_relogin))
                        }
                    }
                },
                onFailure = { exception ->
                    setLoadingState(false)
                    val message = exception.message ?: getString(R.string.error_network_retry)
                    Toast.makeText(this@PendingApprovalActivity, message, Toast.LENGTH_LONG).show()
                },
            )
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout_confirm_title)
            .setMessage(R.string.logout_confirm_body)
            .setPositiveButton(R.string.action_log_out) { _, _ ->
                performLogout()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun performLogout() {
        setLoadingState(true)
        lifecycleScope.launch {
            try {
                authRepo.signOut() // clears the local session token
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Timber.e(e, "Logout cleanup failed, forcing local exit.")
            } finally {
                routeToLogin(getString(R.string.logout_success))
            }
        }
    }

    private fun routeToLogin(message: String) {
        val intent = Intent(this, AuthActivity::class.java)
        intent.putExtra(DicKeys.ROUTING_ERROR, message)
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
            btnRefreshStatus.text = getString(R.string.action_check_status)
            btnRefreshStatus.isEnabled = true
            tvLogout.isEnabled = true
            progressLoading.visibility = View.GONE
        }
    }
}
