// Small UI/animation constants read clearest inlined directly.
@file:Suppress("MagicNumber")

package com.indicvision.semper.ui.analysis.wizard

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.indicvision.semper.R
import com.indicvision.semper.databinding.ActivityStaticAnalysisBinding
import com.indicvision.semper.databinding.WizardStepSettingsBinding
import com.indicvision.semper.databinding.WizardStepSweepBinding

/**
 * Wizard page visibility, toolbar subtitle, and bottom-nav labels for the
 * three-step analysis setup (images → settings → optional sweep).
 *
 * Step-specific side effects (card refresh, recommendations, plan rebuild)
 * stay in the Activity after [applyStep] returns.
 */
class AnalysisWizardChrome(
    private val activity: AppCompatActivity,
    wizard: ActivityStaticAnalysisBinding,
    settingsPage: WizardStepSettingsBinding,
    sweepPage: WizardStepSweepBinding,
) {
    private val scrollStepImages: View = wizard.scrollStepImages
    private val scrollStepSettings: View = settingsPage.root
    private val scrollStepSweep: View = sweepPage.root
    private val btnNext = wizard.btnNext
    private val btnBack = wizard.btnBack
    private val btnCalculateFullField = wizard.btnCalculateFullField
    private val btnRunSweep = wizard.btnRunSweep
    private val toolbar = wizard.toolbar

    /**
     * Sets page visibility, toolbar subtitle, and bottom-nav labels.
     * Returns the clamped target step that was applied.
     */
    fun applyStep(
        previous: Int,
        requestedStep: Int,
        sweepMode: Boolean,
        animate: Boolean,
    ): Int {
        val target = when {
            requestedStep >= 3 && !sweepMode -> 2
            requestedStep < 1 -> 1
            else -> requestedStep.coerceAtMost(if (sweepMode) 3 else 2)
        }

        val pages = listOf(scrollStepImages, scrollStepSettings, scrollStepSweep)
        val showing = pages[target - 1]
        pages.forEach { page ->
            page.visibility = if (page === showing) View.VISIBLE else View.GONE
        }
        if (animate && previous != target) {
            val forward = target > previous
            showing.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(
                    activity,
                    if (forward) R.anim.slide_in_right else R.anim.slide_in_left,
                ),
            )
        }

        toolbar.subtitle = activity.getString(
            R.string.step_of_fmt,
            target,
            if (sweepMode) 3 else 2,
        )
        updateBottomNav(target, sweepMode)
        return target
    }

    /** Bottom nav labels and visibility for the current wizard step + mode. */
    fun updateBottomNav(step: Int, sweepMode: Boolean) {
        toolbar.subtitle = activity.getString(
            R.string.step_of_fmt,
            step,
            if (sweepMode) 3 else 2,
        )
        when (step) {
            1 -> {
                btnNext.visibility = View.VISIBLE
                btnNext.setText(R.string.next_settings)
                btnBack.visibility = View.GONE
                btnCalculateFullField.visibility = View.GONE
                btnRunSweep.visibility = View.GONE
            }
            2 -> {
                btnBack.visibility = View.VISIBLE
                if (sweepMode) {
                    btnNext.visibility = View.VISIBLE
                    btnNext.setText(R.string.next_sweep)
                    btnCalculateFullField.visibility = View.GONE
                } else {
                    btnNext.visibility = View.GONE
                    btnCalculateFullField.visibility = View.VISIBLE
                }
                btnRunSweep.visibility = View.GONE
            }
            else -> {
                btnBack.visibility = View.VISIBLE
                btnNext.visibility = View.GONE
                btnCalculateFullField.visibility = View.GONE
                btnRunSweep.visibility = View.VISIBLE
            }
        }
    }
}
