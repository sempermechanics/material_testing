package com.sempermechanics.semper.ui.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.account.AuthRepository
import com.sempermechanics.semper.data.account.LegalTerms
import com.sempermechanics.semper.databinding.ActivityTermsBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.auth.AuthRoute
import com.sempermechanics.semper.ui.common.auth.ExternalLinks
import com.sempermechanics.semper.ui.common.auth.SignOutRun
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.setBusy
import com.sempermechanics.semper.ui.home.HomeActivity
import kotlinx.coroutines.launch

/**
 * The clickwrap gate. Shown once per Terms version, after sign-in and before
 * Pending/Home, for every sign-in method (see [AccessRouter.intentFor]).
 *
 * Two separate choices:
 *  - agreeing to the Terms and Privacy Policy — required, starts unticked;
 *  - allowing synced data to be used to improve Semper — optional, starts
 *    ticked (owner's decision), never bundled into the first box, and
 *    changeable later in Settings. Only the required box unlocks the button.
 *
 * Declining (button or back) signs the user out: the account cannot be used
 * under terms that were not accepted.
 */
@MainThread
class TermsActivity : AppCompatActivity() {

    private val authRepo by lazy { AuthRepository(applicationContext) }

    private val viewModel: TermsViewModel by viewModels()

    /** Seam for tests: declining must sign out, and the JVM has no Firebase to sign out of. */
    @VisibleForTesting
    internal var signOut: suspend () -> Unit = { authRepo.signOut() }

    private lateinit var binding: ActivityTermsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTermsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.termsRoot)

        binding.tvTermsVersion.text = getString(R.string.terms_version_fmt, LegalTerms.requiredVersion(this))
        binding.tvOpenTerms.setOnClickListener {
            ExternalLinks.open(this, getString(R.string.legal_terms_url))
        }
        binding.tvOpenPrivacy.setOnClickListener {
            ExternalLinks.open(this, getString(R.string.legal_privacy_url))
        }

        // The affirmative act: the button only becomes usable once the required
        // box is ticked by the user — never pre-ticked, never implied by "Continue".
        binding.btnAgree.isEnabled = false
        binding.cbAgreeTerms.setOnCheckedChangeListener { _, checked ->
            binding.btnAgree.isEnabled = checked && !viewModel.busy.value
        }
        binding.btnAgree.setOnClickListener { onAgree() }
        binding.btnDecline.setOnClickListener { onDecline() }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = onDecline()
            },
        )
        SignOutRun.observe(this, onRunning = { setLoading(true) }) { AuthRoute.toSignIn(this) }
        observeViewModel()
    }

    /** Records the acceptance in [TermsViewModel], so a rotation cannot cancel it. */
    private fun onAgree() {
        if (!binding.cbAgreeTerms.isChecked) return
        viewModel.agree(
            version = LegalTerms.requiredVersion(this),
            improvementConsent = binding.cbImprovementConsent.isChecked,
        )
    }

    /**
     * The spinner while an acceptance is recorded, and what each came to once,
     * on whichever gate is started. Only busy is drawn from the state: idle is
     * drawn by the outcome, so a sign-out's spinner ([SignOutRun]) is never undone.
     */
    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.busy.collect { busy -> if (busy) setLoading(true) } }
                viewModel.outcomes.collect(::showOutcome)
            }
        }
    }

    private fun showOutcome(outcome: TermsViewModel.Outcome) {
        setLoading(false)
        when (outcome) {
            TermsViewModel.Outcome.Accepted -> continueToDestination()
            is TermsViewModel.Outcome.Refused -> Feedback.toast(this, outcome.message, long = true)
        }
    }

    /**
     * Sign-out releases a floating seat over the network first, so it runs in
     * [SignOutRun], where a rotation cannot cut it short; this screen (or the
     * one recreated in its place) routes to sign-in when it is done, whether
     * or not the release succeeded.
     */
    private fun onDecline() {
        SignOutRun.start(TermsActivity::class.java, signOut)
    }

    private fun continueToDestination() {
        val next = intent.getStringExtra(EXTRA_NEXT)
            ?.let { runCatching { Class.forName(it) }.getOrNull() }
            ?: HomeActivity::class.java
        startActivity(Intent(this, next))
        finish()
    }

    /** Agree follows the required box once the spinner stops. */
    private fun setLoading(loading: Boolean) {
        binding.progressTerms.setBusy(
            loading,
            binding.btnDecline,
            binding.cbAgreeTerms,
            binding.cbImprovementConsent,
            idleVisibility = View.INVISIBLE,
        )
        binding.btnAgree.isEnabled = !loading && binding.cbAgreeTerms.isChecked
    }

    companion object {
        private const val EXTRA_NEXT = "com.sempermechanics.semper.terms.NEXT"

        /** Start the gate, continuing to [next] once the Terms are accepted. */
        fun intent(context: Context, next: Class<out Activity>): Intent =
            Intent(context, TermsActivity::class.java).putExtra(EXTRA_NEXT, next.name)
    }
}
