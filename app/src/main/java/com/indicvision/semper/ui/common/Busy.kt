package com.indicvision.semper.ui.common

import android.view.View

/**
 * Busy state for a screen with one spinner: this view (the progress
 * indicator) shows while [busy], and every view in [controls] is disabled
 * while it does.
 *
 * Idle, the spinner goes to [idleVisibility]: [View.INVISIBLE] by default, so
 * the layout does not jump while it spins (Terms, Seat required, Session
 * limit); Auth, Pending and Admin pass [View.GONE].
 */
fun View.setBusy(busy: Boolean, vararg controls: View, idleVisibility: Int = View.INVISIBLE) {
    require(idleVisibility == View.INVISIBLE || idleVisibility == View.GONE) {
        "idleVisibility must be INVISIBLE or GONE, was $idleVisibility"
    }
    visibility = if (busy) View.VISIBLE else idleVisibility
    for (control in controls) control.isEnabled = !busy
}
