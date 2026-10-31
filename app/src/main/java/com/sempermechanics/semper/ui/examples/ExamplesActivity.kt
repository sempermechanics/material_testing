package com.sempermechanics.semper.ui.examples

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.examples.ExampleDownloads
import com.sempermechanics.semper.data.examples.ExampleManifest
import com.sempermechanics.semper.data.net.AppRemoteConfig
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.databinding.ActivityExamplesBinding
import com.sempermechanics.semper.databinding.ItemExampleBinding
import com.sempermechanics.semper.ui.analysis.wizard.AnalysisNavHelper
import com.sempermechanics.semper.ui.common.Insets
import com.sempermechanics.semper.ui.common.dialog.DeterminateProgressDialog
import com.sempermechanics.semper.ui.common.dialog.Dialogs
import kotlinx.coroutines.launch

/**
 * The example sets bundled with the app (ADR-031): published tests with
 * their known answers, for a student with no photos of their own yet. Each
 * opens in the wizard set up (Try it) or is copied to Downloads (Save to
 * phone); its credit and licence are on its sheet and on [LicencesActivity].
 */
@MainThread
class ExamplesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamplesBinding
    private val viewModel: ExamplesViewModel by viewModels()
    private var progress: DeterminateProgressDialog? = null

    /** Whether new work may start (version, seat, analysis limit); tests stand in for it. */
    @VisibleForTesting
    internal var canStart: suspend () -> Boolean = { AnalysisNavHelper.ensureCanStart(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExamplesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.padVertical(binding.examplesRoot)
        binding.btnExamplesBack.setOnClickListener { finish() }
        binding.btnExamplesLicences.setOnClickListener { startActivity(LicencesActivity.intent(this)) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.sets.collect(::showSets) }
                launch { viewModel.saving.collect(::showSaving) }
                viewModel.outcomes.collect(::showOutcome)
            }
        }
    }

    override fun onStop() {
        // The dialog belongs to this window; the save's progress redraws it on return.
        progress?.dismiss()
        progress = null
        super.onStop()
    }

    private fun showSets(sets: List<ExampleManifest>) {
        val list = binding.examplesList
        // The intro and the credits button stay first; the cards follow.
        while (list.childCount > FIXED_ROWS) list.removeViewAt(FIXED_ROWS)
        sets.forEach { set ->
            val card = ItemExampleBinding.inflate(layoutInflater, list, false)
            card.tvExampleTitle.text = set.title
            card.tvExampleMeta.text = metaText(set)
            card.tvExampleSummary.text = set.summary
            card.tvExampleLicence.text =
                getString(R.string.examples_licence_line_fmt, set.credit.licence, set.credit.creators)
            card.exampleCard.setOnClickListener { openSet(set) }
            list.addView(card.root)
        }
    }

    private fun metaText(set: ExampleManifest): String = getString(R.string.examples_meta_fmt, set.deformed.size + 1)

    private fun openSet(set: ExampleManifest) {
        ExampleDetailSheet.show(this, set, onTry = { tryIt(set) }, onSave = { viewModel.save(set) })
    }

    /**
     * Try it: the wizard on the set, past the same gates as the + button.
     * The frames must fit the frame limit, or the load log would no longer
     * pair with them.
     */
    private fun tryIt(set: ExampleManifest) {
        val limit = AppSettings.maxFrames(this, AppRemoteConfig.maxFrames(this))
        if (set.deformed.size > limit) {
            Dialogs.info(
                this,
                getString(R.string.examples_too_many_frames_title),
                getString(R.string.examples_too_many_frames_body_fmt, set.deformed.size, limit),
            )
            return
        }
        lifecycleScope.launch {
            if (canStart()) startActivity(ExampleHandOff.intent(this@ExamplesActivity, set))
        }
    }

    private fun showSaving(state: ExamplesViewModel.Progress?) {
        if (state == null) {
            progress?.dismiss()
            progress = null
            return
        }
        val dialog = progress ?: DeterminateProgressDialog(
            this,
            getString(R.string.examples_saving_fmt, state.title),
            onCancel = viewModel::cancelSave,
        ).also {
            progress = it
            it.show()
        }
        if (state.total > 0) dialog.update(state.done * PERCENT / state.total.toDouble())
    }

    private fun showOutcome(outcome: ExampleDownloads.Outcome) {
        when (outcome) {
            is ExampleDownloads.Outcome.Saved -> Dialogs.info(
                this,
                getString(R.string.examples_saved_title),
                getString(R.string.examples_saved_body_fmt, outcome.folder),
            )
            is ExampleDownloads.Outcome.Failed -> Dialogs.info(
                this,
                getString(R.string.examples_save_failed_title),
                getString(R.string.examples_save_failed_body_fmt, outcome.reason),
            )
        }
    }

    companion object {
        /** The intro text and the credits button above the cards in `examplesList`. */
        private const val FIXED_ROWS = 2
        private const val PERCENT = 100.0

        fun intent(context: Context): Intent = Intent(context, ExamplesActivity::class.java)
    }
}
