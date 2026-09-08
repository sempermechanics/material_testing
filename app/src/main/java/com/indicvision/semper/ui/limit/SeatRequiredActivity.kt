package com.indicvision.semper.ui.limit

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.indicvision.semper.R
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.data.net.IndicApi
import com.indicvision.semper.data.net.TokenProvider
import com.indicvision.semper.ui.common.Insets
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
            val outcome = runCatching { api.checkoutLease(token) }
            setLoading(false)
            outcome
                .onSuccess { config ->
                    AppRemoteConfig.apply(this@SeatRequiredActivity, config)
                    Toast.makeText(this@SeatRequiredActivity, R.string.seat_taken, Toast.LENGTH_SHORT).show()
                    finish()
                }
                .onFailure { error ->
                    // A full pool is the expected answer, not a fault: say so
                    // plainly and leave the screen up to try again.
                    val message = if (error is IndicApi.NoSeatAvailableException) {
                        R.string.seat_still_full
                    } else {
                        Timber.w(error, "Could not take a seat")
                        R.string.seat_error
                    }
                    Toast.makeText(this@SeatRequiredActivity, message, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        // INVISIBLE, not GONE, so the layout does not jump while it spins.
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        btnTake.isEnabled = !loading
    }
}
