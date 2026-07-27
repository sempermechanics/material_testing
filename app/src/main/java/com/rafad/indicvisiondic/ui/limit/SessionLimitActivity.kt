package com.rafad.indicvisiondic.ui.limit

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rafad.indicvisiondic.BuildConfig
import com.rafad.indicvisiondic.R
import com.rafad.indicvisiondic.data.CloudSync
import com.rafad.indicvisiondic.data.DeviceKeyManager
import com.rafad.indicvisiondic.data.net.TokenStore
import com.rafad.indicvisiondic.ui.common.Insets
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Persistent gate shown when the account's cloud analysis quota is full. Unlike
 * a transient warning, this screen stays until the limit is resolved: it directs
 * the user to email support@indicvision.com to raise their limit, and lets them
 * re-check or go back to manage (delete) existing analyses.
 */
class SessionLimitActivity : AppCompatActivity() {

    private lateinit var tvBody: TextView
    private lateinit var tvQuota: TextView
    private lateinit var btnEmail: Button
    private lateinit var btnRecheck: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_limit)
        Insets.padVertical(findViewById(R.id.limitRoot))

        tvBody = findViewById(R.id.tvLimitBody)
        tvQuota = findViewById(R.id.tvLimitQuota)
        btnEmail = findViewById(R.id.btnEmailSupport)
        btnRecheck = findViewById(R.id.btnRecheckLimit)
        progress = findViewById(R.id.progressLimit)

        tvBody.text = getString(R.string.limit_body, getString(R.string.support_email))
        renderQuota()

        btnEmail.setOnClickListener { emailSupport() }
        btnRecheck.setOnClickListener { recheck() }
        findViewById<TextView>(R.id.tvLimitBack).setOnClickListener { finish() }
    }

    private fun renderQuota() {
        val used = TokenStore.quotaUsed(this)
        val max = TokenStore.quotaMax(this)
        // Only show the counter when the backend actually reported numbers.
        tvQuota.visibility = if (max > 0) View.VISIBLE else View.GONE
        if (max > 0) tvQuota.text = getString(R.string.limit_quota_fmt, used, max)
    }

    /** Opens the mail app pre-filled to support with account + device context. */
    private fun emailSupport() {
        val email = TokenStore.cachedEmail(this) ?: "(unknown account)"
        val deviceId = DeviceKeyManager(this).getDeviceId()
        val used = TokenStore.quotaUsed(this)
        val max = TokenStore.quotaMax(this)
        val body = buildString {
            append("I've reached my inDIC analysis limit and would like it raised.\n\n")
            append("Account: ").append(email).append('\n')
            append("Quota: ").append(used).append('/').append(max).append('\n')
            append("Device ID: ").append(deviceId).append('\n')
            append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" — Android ").append(Build.VERSION.RELEASE)
        }
        val support = getString(R.string.support_email)
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(support))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.limit_subject) + " — " + email)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No email app to send the access request")
            Toast.makeText(this, getString(R.string.request_access_none, support), Toast.LENGTH_LONG).show()
        }
    }

    /** Re-query the backend; if the account is under the limit again, dismiss. */
    private fun recheck() {
        setLoading(true)
        lifecycleScope.launch {
            // deep=true: the user explicitly tapped Recheck, so bypass the
            // reconcile throttle — a silently skipped check would report
            // "still full" from stale data.
            CloudSync.reconcile(this@SessionLimitActivity, deep = true)
            setLoading(false)
            if (!TokenStore.isSessionLimitReached(this@SessionLimitActivity)) {
                Toast.makeText(this@SessionLimitActivity, R.string.limit_cleared, Toast.LENGTH_SHORT).show()
                finish()
            } else {
                renderQuota()
                Toast.makeText(this@SessionLimitActivity, R.string.limit_still_full, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        btnRecheck.isEnabled = !loading
        btnEmail.isEnabled = !loading
    }
}
