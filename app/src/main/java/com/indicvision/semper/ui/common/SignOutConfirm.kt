package com.indicvision.semper.ui.common

import android.app.Activity
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.indicvision.semper.R

/**
 * "Log out?" before a user-chosen sign-out, then the sign-out itself in
 * [SignOutRun] on behalf of [activity]'s class, so a rotation cannot cut it
 * short. The screen routes on through its own [SignOutRun.observe]
 * (usually to [AuthRoute.toSignIn]).
 *
 * Settings labels the action [R.string.action_sign_out] (the default);
 * Pending says [R.string.action_log_out].
 */
fun AuthRoute.confirmSignOut(
    activity: Activity,
    @StringRes confirmLabel: Int = R.string.action_sign_out,
    signOut: suspend () -> Unit,
): AlertDialog = Dialogs.confirm(
    activity,
    R.string.logout_confirm_title,
    R.string.logout_confirm_body,
    confirmLabel,
) {
    SignOutRun.start(activity.javaClass, signOut)
}
