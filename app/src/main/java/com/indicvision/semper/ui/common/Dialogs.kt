package com.indicvision.semper.ui.common

import android.content.Context
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.indicvision.semper.R

/**
 * The two alert shapes every screen repeats with [MaterialAlertDialogBuilder]:
 *
 * - [info]: a title, a body and **OK** (`android.R.string.ok`), which only closes.
 *   Behind each "i" button ([bindInfo]) and the one-line "this happened" notices.
 * - [confirm]: a title, a body, a positive action that runs [confirm]'s
 *   `onConfirm`, and a negative **Cancel** that only closes.
 *
 * Both return the shown dialog, so a caller that must react to a later
 * dismiss can still reach it.
 */
object Dialogs {

    /** Title, body and OK. [context] must be able to show a window (an Activity). */
    fun info(context: Context, @StringRes title: Int, @StringRes body: Int): AlertDialog =
        info(context, context.getText(title), context.getText(body))

    /** [info] with text already formatted (a plural, a `%s` argument). */
    fun info(context: Context, title: CharSequence, body: CharSequence): AlertDialog =
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()

    /**
     * Title, body, [confirmLabel] running [onConfirm], and [cancelLabel] that
     * only closes. The cancel label defaults to `R.string.action_cancel`; some
     * screens pass `R.string.cancel` (the same "Cancel" text) to keep their
     * resource.
     */
    @Suppress("LongParameterList") // the dialog's five parts plus the action; a holder would only rename them
    fun confirm(
        context: Context,
        @StringRes title: Int,
        @StringRes body: Int,
        @StringRes confirmLabel: Int,
        @StringRes cancelLabel: Int = R.string.action_cancel,
        onConfirm: () -> Unit,
    ): AlertDialog = confirm(
        context,
        context.getText(title),
        context.getText(body),
        confirmLabel,
        cancelLabel,
        onConfirm,
    )

    /** [confirm] with title and body already formatted. */
    @Suppress("LongParameterList") // as above
    fun confirm(
        context: Context,
        title: CharSequence,
        body: CharSequence,
        @StringRes confirmLabel: Int,
        @StringRes cancelLabel: Int = R.string.action_cancel,
        onConfirm: () -> Unit,
    ): AlertDialog = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }
        .setNegativeButton(cancelLabel, null)
        .show()
}

/**
 * Makes this view (an "i" button) open [Dialogs.info] with [titleRes] and
 * [bodyRes]. [context] is the view's own by default; a view inside a bottom
 * sheet can pass its Activity to theme the dialog as the Activity's.
 */
fun View.bindInfo(@StringRes titleRes: Int, @StringRes bodyRes: Int, context: Context = this.context) {
    setOnClickListener { Dialogs.info(context, titleRes, bodyRes) }
}
