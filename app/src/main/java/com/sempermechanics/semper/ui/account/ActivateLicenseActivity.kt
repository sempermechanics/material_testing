package com.sempermechanics.semper.ui.account

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.policy.ManagedConfigReader
import com.sempermechanics.semper.databinding.ActivityActivateLicenseBinding
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.Feedback
import com.sempermechanics.semper.ui.common.hideKeyboard
import com.sempermechanics.semper.ui.common.setBusy
import kotlinx.coroutines.launch

/**
 * Enter a licence key and activate it on this account (TD-259).
 *
 * The field formats the key as it is typed (`SEMP-XXXX-XXXX-XXXX-XXXX`,
 * [LicenseKeyFormat]) and checks its shape before anything is sent. The
 * request and the screen's message live in [ActivateLicenseViewModel]; a
 * refusal is shown with its [ActivationError] message and the screen stays
 * up. On success the account's config is refreshed ([LicenseActivation]),
 * the screen answers [RESULT_OK] and closes.
 *
 * Opened from Settings' account section and the analysis-limit screen while
 * the account is on demo ([LicenseActivation.isOffered]). An EMM-set
 * `enrollmentInvite` with a licence key's shape fills the field
 * ([LicenseKeyFormat.prefillFrom]).
 */
@MainThread
class ActivateLicenseActivity : AppCompatActivity() {

    private lateinit var binding: ActivityActivateLicenseBinding
    private val viewModel: ActivateLicenseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActivateLicenseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padTopAndImeBottom(binding.activateRoot)

        // Only on first creation: after a rotation the field restores what the user typed.
        if (savedInstanceState == null) {
            LicenseKeyFormat.prefillFrom(ManagedConfigReader.current(this).enrollmentInvite)
                ?.let { binding.etLicenseKey.setText(it) }
        }
        binding.etLicenseKey.doAfterTextChanged { it?.let(::reformat) }
        binding.etLicenseKey.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { done -> if (done) submit() }
        }
        binding.btnActivateLicense.setOnClickListener { submit() }
        binding.tvActivateBack.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.state.collect { render(it) } }
        }
    }

    private fun submit() {
        binding.etLicenseKey.hideKeyboard()
        viewModel.submit(binding.etLicenseKey.text?.toString().orEmpty())
    }

    /**
     * Rewrites the field as [LicenseKeyFormat.format] has it, keeping the
     * cursor after the same letters and digits. The rewrite calls back in
     * here once more and finds nothing to change.
     */
    private fun reformat(text: Editable) {
        val raw = text.toString()
        val formatted = LicenseKeyFormat.format(raw)
        if (formatted != raw) {
            val keyChars = LicenseKeyFormat.keyCharsBefore(raw, binding.etLicenseKey.selectionEnd)
            text.replace(0, text.length, formatted)
            binding.etLicenseKey.setSelection(LicenseKeyFormat.cursorAfter(formatted, keyChars))
        }
        viewModel.edited(formatted)
    }

    private fun render(state: ActivationState) {
        binding.progressActivate.setBusy(
            state == ActivationState.Running,
            binding.btnActivateLicense,
            binding.etLicenseKey,
            idleVisibility = View.INVISIBLE,
        )
        binding.tilLicenseKey.error = (state as? ActivationState.Invalid)?.let { getString(invalidMessage(it.check)) }
        val refused = state as? ActivationState.Refused
        binding.tvActivateError.isVisible = refused != null
        binding.tvActivateError.text = refused?.let { getString(it.error.messageRes) }
        if (state == ActivationState.Activated && !isFinishing) {
            Feedback.toast(this, R.string.activate_success, long = true)
            setResult(RESULT_OK)
            finish()
        }
    }

    companion object {

        /** The intent that opens the screen. */
        fun intent(context: Context): Intent = Intent(context, ActivateLicenseActivity::class.java)

        /** What the field says about a key of the wrong shape. */
        @StringRes
        internal fun invalidMessage(check: LicenseKeyFormat.Check): Int = when (check) {
            LicenseKeyFormat.Check.Empty -> R.string.activate_invalid_empty
            LicenseKeyFormat.Check.MissingPrefix -> R.string.activate_invalid_prefix
            LicenseKeyFormat.Check.WrongLength, is LicenseKeyFormat.Check.Valid -> R.string.activate_invalid_length
        }
    }
}
