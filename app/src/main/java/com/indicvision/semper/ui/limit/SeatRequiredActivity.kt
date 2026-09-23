package com.indicvision.semper.ui.limit

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.LicenseConfigWorker
import com.indicvision.semper.data.net.ApiErrors
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.ui.common.Insets
import com.indicvision.semper.util.suspendRunCatching
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Gate shown when every seat on a floating institution license is in use.
 *
 * Deliberately not shaped like [SessionLimitActivity], which it otherwise
 * resembles. Being at the analysis limit needs a human — an email to support to
 * raise the cap. Having no seat needs nobody: seats free themselves as
 * colleagues finish, so the whole screen is one button that tries again.
 *
 * The user is **not blocked from the app**. They are eligible, in demo mode,
 * and everything already on the device is still theirs to open — only starting
 * new work waits.
 */
@MainThread
class SeatRequiredActivity : AppCompatActivity() {

    private lateinit var tvBody: TextView
    private lateinit var btnTake: Button
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_seat_required)
        Insets.padVertical(findViewById(R.id.seatRoot))

        tvBody = findViewById(R.id.tvSeatBody)
        btnTake = findViewById(R.id.btnTakeSeat)
        progress = findViewById(R.id.progressSeat)

        tvBody.text = getString(R.string.seat_body)
        btnTake.setOnClickListener { takeSeat() }
        findViewById<TextView>(R.id.tvSeatBack).setOnClickListener { finish() }
    }

    /**
     * Ask for a seat. On success the backend's response already carries the
     * new entitlement, so it is applied straight to the cache rather than
     * waiting for the next config fetch — otherwise the user would return to
     * Home still looking blocked.
     */
    private fun takeSeat() {
        setLoading(true)
        lifecycleScope.launch {
            val api = IndicApi.get(this@SeatRequiredActivity)
            val token = TokenProvider.usableIdToken()
            if (!api.enabled || token == null) {
                setLoading(false)
                Toast.makeText(this@SeatRequiredActivity, R.string.seat_offline, Toast.LENGTH_LONG).show()
                return@launch
            }
            val outcome = suspendRunCatching { api.checkoutLease(token) }
            setLoading(false)
            outcome
                .onSuccess { config ->
                    AppRemoteConfig.apply(this@SeatRequiredActivity, config)
                    LicenseConfigWorker.enqueue(this@SeatRequiredActivity)
                    Toast.makeText(this@SeatRequiredActivity, R.string.seat_taken, Toast.LENGTH_SHORT).show()
                    finish()
                }
                .onFailure { error ->
                    // A full pool is the expected answer, not a fault: say so
                    // plainly and leave the screen up to try again.
                    val message = when {
                        error is IndicApi.NoSeatAvailableException -> R.string.seat_still_full
                        // Past RetryOnTransient's three attempts, so this is a
                        // sustained throttle, not a blip — blaming the
                        // connection would send the user to their wifi settings
                        // for a limit that clears on its own.
                        error.hasApiCode(ApiErrors.RATE_LIMITED) -> R.string.error_rate_limited
                        error.hasApiCode(ApiErrors.APP_CHECK_REQUIRED) ->
                            R.string.error_app_check_required
                        else -> {
                            Timber.w(error, "Could not take a seat")
                            R.string.seat_error
                        }
                    }
                    Toast.makeText(this@SeatRequiredActivity, message, Toast.LENGTH_LONG).show()
                }
        }
    }

    /** True when this failure is the backend answering with [code]. */
    private fun Throwable.hasApiCode(code: String): Boolean =
        this is IndicApi.ApiException && ApiErrors.isCode(parsedDetail, code)

    private fun setLoading(loading: Boolean) {
        // INVISIBLE, not GONE, so the layout does not jump while it spins.
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        btnTake.isEnabled = !loading
    }
}
