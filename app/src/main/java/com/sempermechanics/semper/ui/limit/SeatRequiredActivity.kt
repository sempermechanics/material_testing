package com.sempermechanics.semper.ui.limit

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sempermechanics.semper.R
import com.sempermechanics.semper.databinding.ActivitySeatRequiredBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import kotlinx.coroutines.launch

/**
 * Gate shown when every seat on a floating institution license is in use.
 *
 * Deliberately not shaped like [SessionLimitActivity], which it otherwise
 * resembles. Being at the analysis limit needs the user to free room (or a
 * licence refresh). Having no seat needs nobody: seats free themselves as
 * colleagues finish, so the whole screen is one button that tries again.
 *
 * The user is **not blocked from the app**. They are eligible, in demo mode,
 * and everything already on the device is still theirs to open — only starting
 * new work waits.
 */
@MainThread
class SeatRequiredActivity : AppCompatActivity() {

    private val viewModel: SeatRequiredViewModel by viewModels()

    private lateinit var binding: ActivitySeatRequiredBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeatRequiredBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.seatRoot)

        binding.tvSeatBody.setText(R.string.seat_body)
        // In the view model, so a rotation cannot cancel the request.
        binding.btnTakeSeat.setOnClickListener { viewModel.takeSeat() }
        binding.tvSeatBack.setOnClickListener { finish() }
        observeViewModel()
    }

    /** The spinner while a seat request is out, and what each came to once, on whichever screen is current. */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.busy.collect(::setLoading) }
                viewModel.outcomes.collect(::showOutcome)
            }
        }
    }

    private fun showOutcome(outcome: SeatRequiredViewModel.Outcome) {
        when (outcome) {
            SeatRequiredViewModel.Outcome.Taken -> {
                Feedback.toast(this, R.string.seat_taken)
                finish()
            }
            // A full pool or no connection: leave the screen up to try again.
            is SeatRequiredViewModel.Outcome.NotTaken -> Feedback.toast(this, outcome.message, long = true)
        }
    }

    /** INVISIBLE, not GONE, so the layout does not jump while it spins. */
    private fun setLoading(loading: Boolean) {
        binding.progressSeat.setBusy(loading, binding.btnTakeSeat, idleVisibility = View.INVISIBLE)
    }
}
