package com.sempermechanics.semper.ui.home

import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sempermechanics.semper.R
import com.sempermechanics.semper.data.net.AccountCache
import com.sempermechanics.semper.data.prefs.AppSettings
import com.sempermechanics.semper.data.prefs.CoachPrefs
import com.sempermechanics.semper.databinding.DialogDiagnosticsConsentBinding
import com.sempermechanics.semper.diagnostics.Diagnostics
import com.sempermechanics.semper.ui.common.CoachMarkController

/**
 * What Home shows on first arrival: the beta notice, then the diagnostics
 * choice, then the coach mark on the new-analysis button. One overlay at a
 * time, and the diagnostics choice is made before anything is collected.
 */
internal class FirstRunPrompts(private val activity: AppCompatActivity) {

    /** Shows whichever of the three are still due, in order, the coach mark pointing at [fab]. */
    fun show(fab: View) {
        maybeShowBetaNotice {
            maybeAskDiagnostics {
                fab.post {
                    CoachMarkController(activity).maybeShow(
                        CoachPrefs.Screen.HOME,
                        listOf(
                            CoachMarkController.Step(
                                fab,
                                activity.getString(R.string.coach_home_fab),
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * One-time beta / data-use declaration after the account first reaches Home,
     * then [next]. The only way out is "I understand", so [next] runs from there.
     */
    private fun maybeShowBetaNotice(next: () -> Unit) {
        if (AccountCache.hasAckedBetaNotice(activity)) {
            next()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.beta_notice_title)
            .setMessage(R.string.beta_notice_body)
            .setCancelable(false)
            .setPositiveButton(R.string.beta_notice_ack) { _, _ ->
                AccountCache.setBetaNoticeAcked(activity)
                next()
            }
            .show()
    }

    /**
     * First-run diagnostics choice, then [next].
     *
     * Crashlytics and Analytics are disabled in the manifest, so nothing has been
     * collected before this point — the app previously started reporting on first
     * launch with no notice and no way to decline. Asked once: a "Not now" is
     * recorded, so this does not nag, and the toggle stays in Settings.
     */
    private fun maybeAskDiagnostics(next: () -> Unit) {
        if (AppSettings.wasDiagnosticsAsked(activity)) {
            next()
            return
        }
        val builder = MaterialAlertDialogBuilder(activity)
        // The body and both choices are one view: the body scrolls in what
        // height is left, and the choices sit side by side at equal weight
        // under it, both whole at any font scale. The stock stacked bar cut
        // whichever came second to 26 dp at font scale 2.0 (readiness AE1-103).
        val content = DialogDiagnosticsConsentBinding.inflate(LayoutInflater.from(builder.context))
        val dialog = builder
            .setTitle(R.string.diagnostics_prompt_title)
            .setView(content.root)
            .setCancelable(false)
            .setOnDismissListener { next() }
            .create()
        content.btnDiagnosticsDecline.setOnClickListener {
            Diagnostics.setEnabled(activity, false)
            dialog.dismiss()
        }
        content.btnDiagnosticsAccept.setOnClickListener {
            Diagnostics.setEnabled(activity, true)
            dialog.dismiss()
        }
        dialog.show()
    }
}
