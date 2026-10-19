package com.sempermechanics.semper.ui.common.dialog

import android.content.Context
import android.view.View
import androidx.core.view.isVisible
import com.sempermechanics.semper.R

/**
 * Locked, not hidden (`FeatureLocks`): a feature the account's licence does
 * not include stays on screen, disabled, beside one note that says what
 * includes it. A tap on it says the same note. Nothing here opens a link, a
 * price or a purchase: the app only consumes the licence the account holds.
 */
object LicenseLock {

    /** The licence note, as a toast. */
    fun explain(context: Context) {
        Feedback.toast(context, R.string.license_lock_note, long = true)
    }

    /**
     * Locks [area]: shows [note], disables each of [controls], and makes a
     * tap anywhere in [area] [explain]. A disabled view that is clickable
     * still swallows its tap, so the controls stop being clickable too and
     * the tap reaches [area].
     */
    fun lock(area: View, note: View, controls: List<View>) {
        note.isVisible = true
        for (control in controls) {
            control.isEnabled = false
            control.isClickable = false
            control.isLongClickable = false
        }
        area.setOnClickListener { explain(it.context) }
    }
}
