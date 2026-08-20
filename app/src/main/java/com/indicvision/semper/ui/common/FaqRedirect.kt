package com.indicvision.semper.ui.common

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.indicvision.semper.R
import timber.log.Timber

/**
 * One leave-the-app confirm before any FAQ browser hop. Warning chips, Why?
 * snackbar actions, and engine-failure dialogs all go through here.
 */
object FaqRedirect {

    fun confirm(activity: Activity, url: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.faq_redirect_title)
            .setMessage(R.string.faq_redirect_body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.faq_redirect_open) { _, _ -> open(activity, url) }
            .show()
    }

    fun confirm(activity: Activity, @StringRes urlRes: Int) {
        confirm(activity, activity.getString(urlRes))
    }

    /** Error toast replacement: message plus a **Why?** action that confirms then opens. */
    fun snackbar(
        activity: Activity,
        message: CharSequence,
        @StringRes faqUrlRes: Int,
        anchor: View? = null,
    ) {
        val root = anchor ?: activity.findViewById(android.R.id.content) ?: return
        Snackbar.make(root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_why) {
                confirm(activity, faqUrlRes)
            }
            .show()
    }

    fun snackbar(
        activity: Activity,
        @StringRes messageRes: Int,
        @StringRes faqUrlRes: Int,
        anchor: View? = null,
    ) {
        snackbar(activity, activity.getString(messageRes), faqUrlRes, anchor)
    }

    /**
     * Alert with OK and optional **Why?**. When [faqUrlRes] is null, only OK.
     */
    fun errorDialog(
        activity: Activity,
        title: CharSequence,
        message: CharSequence,
        @StringRes faqUrlRes: Int?,
    ) {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
        if (faqUrlRes != null) {
            builder.setNeutralButton(R.string.action_why) { _, _ ->
                confirm(activity, faqUrlRes)
            }
        }
        builder.show()
    }

    private fun open(activity: Activity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No browser to open %s", url)
            Toast.makeText(activity, url, Toast.LENGTH_LONG).show()
        }
    }
}
